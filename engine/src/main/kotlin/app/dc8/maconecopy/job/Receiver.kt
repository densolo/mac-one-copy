package app.dc8.maconecopy.job

import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Direction
import app.dc8.maconecopy.Engine
import app.dc8.maconecopy.EngineException
import app.dc8.maconecopy.FileStatus
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.OfferDetails
import app.dc8.maconecopy.OfferFile
import app.dc8.maconecopy.chunk.ChunkCodec
import app.dc8.maconecopy.chunk.SegHash
import app.dc8.maconecopy.fs.CopyNames
import app.dc8.maconecopy.fs.Hashing
import app.dc8.maconecopy.fs.PathSafetyException
import app.dc8.maconecopy.relay.ControlRequest
import app.dc8.maconecopy.relay.DecisionFile
import app.dc8.maconecopy.relay.Manifest
import app.dc8.maconecopy.relay.ReceiverStatus
import app.dc8.maconecopy.relay.Relay
import app.dc8.maconecopy.relay.ResendFile
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Receiver side of a job: applies verified chunks into `.part` files and finalizes complete files. */
internal class Receiver(private val e: Engine) {

    fun create(m: Manifest) {
        val rec = JobRecord(m.jobId, Direction.Received, m.from, m.fromName, m.roots, m.createdAt)
        e.register(rec, m)
        e.log.info("offer ${m.jobId.take(8)} from ${m.fromName}: ${m.files.size} file(s)")
    }

    /** Every path in an incoming manifest must be a safe relative path under one of its roots. */
    fun isValid(m: Manifest): Boolean = runCatching {
        val roots = m.roots.map { e.home.normalize(it) }
        require(roots == m.roots && roots.isNotEmpty())
        fun underRoot(p: String) = roots.any { p == it || p.startsWith("$it/") }
        (m.files.map { it.path } + m.emptyDirs).all { e.home.normalize(it) == it && underRoot(it) } &&
            m.files.all { it.size >= 0 } && m.files.map { it.path }.distinct().size == m.files.size
    }.getOrDefault(false)

    fun decide(rec: JobRecord, decision: Decision) {
        if (rec.direction != Direction.Received || rec.state != JobState.Offered) throw EngineException("job ${rec.id} is not an incoming offer")
        val relay = e.relay() ?: throw EngineException("relay folder not set")
        val m = e.manifest(rec)
        rec.decision = decision
        if (decision == Decision.Decline) {
            relay.write(relay.decision(rec.id), DecisionFile.serializer(), DecisionFile(decision, at = e.now()))
            return e.finish(rec, JobState.Declined)
        }
        val skip = if (decision == Decision.Accept) m.files.indices.filter { statusOf(m.files[it].path, m.files[it].size, m.files[it].mtime) == FileStatus.Same } else emptyList()
        val rootMap = if (decision == Decision.AcceptCopy) copyRoots(m) else m.roots.associateWith { it }
        rec.recv = RecvState(rootMap, skip.toSet())
        rec.filesSkipped = skip.size
        rec.state = JobState.Transferring
        rec.startedAt = e.now()
        relay.write(relay.decision(rec.id), DecisionFile.serializer(), DecisionFile(decision, skip, e.now()))
        e.writeControl(relay, rec)
        e.save(rec)
    }

    /** `report.xlsx` → `report (copy).xlsx`, `Projects` → `Projects (copy)`, then `(copy 2)`, … */
    private fun copyRoots(m: Manifest): Map<String, String> {
        val reserved = HashSet<String>()
        return m.roots.associateWith { root ->
            val parent = root.substringBeforeLast('/', "")
            val isDir = m.files.none { it.path == root }
            val free = CopyNames.firstFree(root.substringAfterLast('/'), isDir) { candidate ->
                val rel = if (parent.isEmpty()) candidate else "$parent/$candidate"
                rel in reserved || Files.exists(e.home.root.resolve(rel), NOFOLLOW_LINKS)
            }
            (if (parent.isEmpty()) free else "$parent/$free").also { reserved += it }
        }
    }

    private fun statusOf(rel: String, size: Long, mtime: Long): FileStatus {
        val attrs = runCatching { Files.readAttributes(e.home.resolve(rel), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull()
            ?: return FileStatus.New
        return if (attrs.isRegularFile && attrs.size() == size && attrs.lastModifiedTime().toMillis() == mtime) FileStatus.Same else FileStatus.Changed
    }

    fun inspect(rec: JobRecord): OfferDetails {
        val m = e.manifest(rec)
        val files = m.files.map { OfferFile(it.path, it.size, it.mtime, statusOf(it.path, it.size, it.mtime)) }
        val view = e.jobs.value.firstOrNull { it.id == rec.id } ?: throw EngineException("job ${rec.id} is not visible yet")
        return OfferDetails(view, files, m.emptyDirs, m.files.sumOf { it.size }, e.freeSpace(e.home.root))
    }

    // ---- loop step -------------------------------------------------------------------------

    suspend fun step(relay: Relay, rec: JobRecord): Boolean {
        if (rec.state.isFinal) return false
        if (!Files.exists(relay.manifest(rec.id))) {
            cancelled(relay, rec, byPeer = true)
            return false
        }
        e.readPeerControl(relay, rec)
        val r = rec.recv ?: return false // offered: nothing is applied before a decision
        if (rec.state.isFinal) return false
        applyResends(relay, rec)
        var worked = false
        for ((seq, path) in relay.listChunks(rec.id)) {
            e.drainCommands()
            if (rec.state != JobState.Transferring || rec.myRequest == ControlRequest.Cancel) break
            if (!enoughSpace(rec)) { diskFull(rec); break }
            val applied = try {
                relay.io { apply(rec, seq, path) } ?: false
            } catch (ex: IOException) {
                if (ex.message?.contains("No space left", ignoreCase = true) == true) { diskFull(rec); break }
                e.log.once("apply:$path", "cannot apply chunk $seq of ${rec.id.take(8)}: $ex")
                false
            }
            e.save(rec)
            if (!applied) {
                e.log.once("chunk:$path:${runCatching { Files.size(path) }.getOrDefault(-1)}", "chunk $seq of ${rec.id.take(8)} not complete yet")
                continue
            }
            Files.deleteIfExists(path) // deletion is the ack
            worked = true
        }
        if (rec.state == JobState.Transferring) checkDone(relay, rec, r)
        return worked
    }

    private fun enoughSpace(rec: JobRecord): Boolean {
        val p = progress(rec)
        return e.freeSpace(e.home.root) >= (p.totalBytes - p.doneBytes) + e.settings.diskMarginBytes
    }

    private fun diskFull(rec: JobRecord) {
        e.log.warn("job ${rec.id.take(8)} paused: disk full")
        e.pauseFor(rec, "disk full")
    }

    private suspend fun applyResends(relay: Relay, rec: JobRecord) {
        val r = rec.recv!!
        for ((gen, path) in relay.listResends(rec.id)) {
            if (gen <= r.gen) continue
            val rf = relay.read(path, ResendFile.serializer()) ?: return
            for ((i, entry) in rf.files) {
                r.overrides[i] = entry
                r.resetGen[i] = gen
                r.done -= i; r.failed -= i; r.bad -= i
                r.partial.remove(i)
                runCatching { Files.deleteIfExists(partPath(rec, i)) }
            }
            for (ref in rf.segments) r.bad -= ref.file
            r.abandoned += rf.abandoned
            r.failed -= rf.abandoned.toSet()
            r.gen = gen
            if (rec.state == JobState.Failed) rec.state = JobState.Transferring
            e.writeControl(relay, rec)
            e.save(rec)
        }
    }

    private fun expected(rec: JobRecord): List<Int> {
        val r = rec.recv!!
        return e.manifest(rec).files.indices.filter { it !in r.skip && it !in r.abandoned }
    }

    private fun isWanted(r: RecvState, file: Int, gen: Int, count: Int) =
        file in 0 until count && file !in r.skip && file !in r.abandoned && file !in r.done && file !in r.failed && file !in r.bad &&
            gen >= (r.resetGen[file] ?: 0)

    /**
     * Applies a chunk in one pass: DATA bytes go straight into the `.part` files while being hashed, but
     * the segments only count as received once the trailer verifies. A bad chunk leaves garbage at
     * unmarked ranges, which later data overwrites. Returns false if the chunk is not complete yet.
     */
    private fun apply(rec: JobRecord, seq: Int, chunk: Path): Boolean {
        val r = rec.recv!!
        val count = e.manifest(rec).files.size
        val channels = HashMap<Int, FileChannel>()
        val newSegs = mutableListOf<Pair<Int, SegHash>>()
        val ends = mutableListOf<Pair<Int, List<SegHash>>>()
        val changed = mutableListOf<Int>()
        var chunkGen = 0
        val header = try {
            ChunkCodec.read(chunk, object : ChunkCodec.Visitor {
                // A chunk from a generation whose resend file has not synced yet waits until it has.
                override fun header(header: ChunkCodec.Header): Boolean {
                    chunkGen = header.gen
                    return header.seq == seq && header.jobId.toString() == rec.id && header.gen <= r.gen
                }

                override fun data(file: Int, offset: Long, length: Int, input: InputStream) {
                    if (!isWanted(r, file, chunkGen, count)) return
                    if (offset < 0 || offset + length > e.entry(rec, file).size) return
                    val part = r.partial.getOrPut(file) { PartFile() }
                    if (part.segs.get(offset, length.toLong()) != null) return // already have exactly this segment
                    part.segs.removeOverlapping(offset, offset + length)
                    val ch = channels.getOrPut(file) {
                        val p = partPath(rec, file)
                        Files.createDirectories(p.parent)
                        FileChannel.open(p, CREATE, WRITE, NOFOLLOW_LINKS)
                    }
                    val md = Hashing.sha256()
                    val buf = ByteArray(1 shl 16)
                    var pos = offset
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        while (bb.hasRemaining()) pos += ch.write(bb, pos)
                    }
                    if (pos == offset + length) newSegs += file to SegHash(offset, length.toLong(), Hashing.hex(md.digest()))
                    e.currentFile[rec.id] = e.entry(rec, file).path
                }

                override fun fileEnd(file: Int, flags: Int, segments: List<SegHash>) {
                    if (!isWanted(r, file, chunkGen, count)) return
                    if (flags and ChunkCodec.SOURCE_CHANGED != 0) changed += file else ends += file to segments
                }
            })
        } finally {
            channels.values.forEach { runCatching { it.force(true) }; it.close() }
        }
        e.stats.chunkReads.incrementAndGet()
        if (header == null) return false
        for ((file, seg) in newSegs) {
            r.partial.getOrPut(file) { PartFile() }.segs.put(seg)
            rec.bytesTransferred += seg.length
        }
        for ((file, segs) in ends) r.partial.getOrPut(file) { PartFile() }.ends.let { if (segs !in it) it += segs }
        for (file in changed) {
            e.log.warn("sender reports ${e.entry(rec, file).path} changed while sending")
            r.failed += file
            r.partial.remove(file)
            runCatching { Files.deleteIfExists(partPath(rec, file)) }
        }
        for (file in (newSegs.map { it.first } + ends.map { it.first }).distinct()) tryFinish(rec, file)
        return true
    }

    /**
     * A file is complete once every segment some FILE_END lists is present. Its hashes are compared with
     * the ones computed while receiving (and optionally re-read from disk); mismatching segments are
     * dropped and reported, the rest of the `.part` is kept for the resend.
     */
    private fun tryFinish(rec: JobRecord, file: Int) {
        val r = rec.recv!!
        if (file in r.done || file in r.failed || file in r.bad) return
        val part = r.partial[file] ?: return
        val entry = e.entry(rec, file)
        val end = part.ends.lastOrNull { list -> list.all { part.segs.get(it.offset, it.length) != null } } ?: return
        val contiguous = (end.firstOrNull()?.offset ?: 0L) == 0L && end.zipWithNext().all { (a, b) -> a.offset + a.length == b.offset }
        if (!contiguous || end.sumOf { it.length } != entry.size) {
            e.log.warn("bad segment list for ${entry.path}")
            r.failed += file
            return
        }
        var bad = end.filter { part.segs.get(it.offset, it.length)!!.sha256 != it.sha256 }
        try {
            val dest = destPath(rec, file)
            val partFile = partPath(rec, file)
            Files.createDirectories(partFile.parent)
            if (!Files.exists(partFile, NOFOLLOW_LINKS)) Files.createFile(partFile)
            if (bad.isEmpty() && e.settings.verifyFromDisk) FileChannel.open(partFile, READ, NOFOLLOW_LINKS).use { ch ->
                bad = end.filter { Hashing.sha256(ch, it.offset, it.length) != it.sha256 }
                e.stats.partBytesVerified.addAndGet(entry.size)
            }
            if (bad.isNotEmpty()) {
                e.log.warn("${bad.size} bad segment(s) in ${entry.path}")
                bad.forEach { part.segs.remove(it) }
                r.bad[file] = bad
                return
            }
            if (Files.exists(dest, NOFOLLOW_LINKS)) e.trash(rec.id, e.home.relativize(dest), dest)
            Files.move(partFile, dest, ATOMIC_MOVE, REPLACE_EXISTING)
            Files.setLastModifiedTime(dest, FileTime.fromMillis(entry.mtime))
            r.done += file
            r.partial.remove(file)
        } catch (ex: Exception) {
            if (ex is IOException && ex.message?.contains("No space left", ignoreCase = true) == true) throw ex
            e.log.warn("cannot finalize ${entry.path}: $ex")
            r.failed += file
            r.partial.remove(file)
        }
    }

    private fun checkDone(relay: Relay, rec: JobRecord, r: RecvState) {
        val expected = expected(rec)
        if (expected.any { it !in r.done && it !in r.failed && it !in r.bad }) return
        for (dir in e.manifest(rec).emptyDirs) runCatching { Files.createDirectories(e.home.resolve(mapRoot(rec, dir))) }
        if (r.failed.isEmpty() && r.bad.isEmpty()) e.finish(rec, JobState.Completed, r.done.size, r.abandoned.size)
        else e.finish(rec, JobState.Failed, r.done.size, r.failed.size + r.bad.size + r.abandoned.size)
        e.writeControl(relay, rec)
    }

    fun status(rec: JobRecord): ReceiverStatus = when (rec.state) {
        JobState.Completed -> ReceiverStatus.Completed
        JobState.Failed -> ReceiverStatus.Failed
        else -> ReceiverStatus.Transferring
    }

    fun cancelled(relay: Relay, rec: JobRecord, byPeer: Boolean) {
        if (rec.state.isFinal) return
        deleteParts(rec)
        e.finish(rec, JobState.Cancelled, rec.recv?.done?.size ?: 0)
        if (byPeer && Files.exists(relay.manifest(rec.id))) e.writeControl(relay, rec)
    }

    /** Removes `.part` files; completed files and trashed originals stay. */
    fun deleteParts(rec: JobRecord) {
        val r = rec.recv ?: return
        for (i in expected(rec)) if (i !in r.done) runCatching { Files.deleteIfExists(partPath(rec, i)) }
        r.partial.clear()
        r.bad.clear()
    }

    private fun mapRoot(rec: JobRecord, rel: String): String {
        val map = rec.recv!!.rootMap
        val root = map.keys.firstOrNull { rel == it || rel.startsWith("$it/") } ?: throw PathSafetyException("$rel is outside the job roots")
        return map.getValue(root) + rel.removePrefix(root)
    }

    private fun destPath(rec: JobRecord, file: Int): Path = e.home.resolve(mapRoot(rec, e.entry(rec, file).path))
    private fun partPath(rec: JobRecord, file: Int): Path = destPath(rec, file).let { it.resolveSibling("${it.fileName}.part") }

    fun progress(rec: JobRecord): Engine.Progress {
        val r = rec.recv
        val m = e.manifest(rec)
        val skip = r?.skip ?: emptySet()
        val expected = m.files.indices.filter { it !in skip && (r == null || it !in r.abandoned) }
        val total = expected.sumOf { e.entry(rec, it).size }
        if (r == null) return Engine.Progress(expected.size, total, 0, 0)
        val done = r.done.sumOf { e.entry(rec, it).size } + r.partial.values.sumOf { it.segs.covered }
        return Engine.Progress(expected.size, total, r.done.size, done.coerceAtMost(total))
    }

    /** Status details for the control file. */
    fun failures(rec: JobRecord) = rec.recv?.let { r ->
        r.failed.sorted() to r.bad.flatMap { (f, segs) -> segs.map { app.dc8.maconecopy.relay.SegmentRef(f, it.offset, it.length) } }
    } ?: (emptyList<Int>() to emptyList())
}
