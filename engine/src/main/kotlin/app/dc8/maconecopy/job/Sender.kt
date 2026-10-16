package app.dc8.maconecopy.job

import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Direction
import app.dc8.maconecopy.Engine
import app.dc8.maconecopy.EngineException
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.chunk.ChunkCodec
import app.dc8.maconecopy.chunk.ChunkLayout
import app.dc8.maconecopy.chunk.ChunkPlan
import app.dc8.maconecopy.chunk.LayoutItem
import app.dc8.maconecopy.chunk.SegHash
import app.dc8.maconecopy.chunk.Segment
import app.dc8.maconecopy.fs.AtomicFiles
import app.dc8.maconecopy.fs.Hashing
import app.dc8.maconecopy.relay.ControlRequest
import app.dc8.maconecopy.relay.DecisionFile
import app.dc8.maconecopy.relay.FileEntry
import app.dc8.maconecopy.relay.Manifest
import app.dc8.maconecopy.relay.ReceiverStatus
import app.dc8.maconecopy.relay.Relay
import app.dc8.maconecopy.relay.ResendFile
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Sender side of a job: writes chunks into the shared window and follows the receiver's decision and status. */
internal class Sender(private val e: Engine) {
    private class Layout(val key: String, val plans: List<ChunkPlan>)

    private val layouts = HashMap<String, Layout>()
    private var roundRobin = 0

    fun create(m: Manifest, peerName: String) {
        val relay = e.relay() ?: throw EngineException("relay folder not set")
        val rec = JobRecord(m.jobId, Direction.Sent, m.to, peerName, m.roots, m.createdAt, send = SendState(chunkSize = e.settings.chunkSize))
        e.register(rec, m)
        relay.write(relay.manifest(m.jobId), Manifest.serializer(), m)
    }

    fun forget(jobId: String) {
        layouts.remove(jobId)
    }

    /** Follows decision, control and status for one job. */
    suspend fun step(relay: Relay, rec: JobRecord) {
        if (rec.state.isFinal) return
        val s = rec.send!!
        if (!Files.exists(relay.manifest(rec.id))) return e.finish(rec, JobState.Cancelled, s.ackedFileEnds)
        if (rec.state == JobState.Offered) {
            relay.read(relay.decision(rec.id), DecisionFile.serializer())?.let { applyDecision(relay, rec, it) }
            if (rec.state.isFinal) return
        }
        val pc = e.readPeerControl(relay, rec)
        if (rec.state.isFinal) return
        collectAcks(relay, rec)
        if (rec.myRequest == ControlRequest.Cancel) {
            deleteChunks(relay, rec)
            if (rec.peerAck >= rec.mySeq) cancelled(relay, rec)
            return
        }
        if (pc == null || pc.statusGen < s.gen) return
        when (pc.status) {
            ReceiverStatus.Completed -> {
                relay.deleteJob(rec.id)
                e.finish(rec, JobState.Completed, expected(rec).size - s.abandoned.size, s.abandoned.size)
            }
            ReceiverStatus.Failed -> if (rec.state != JobState.Failed) {
                s.receiverFailed = pc.failed
                s.receiverBad = pc.badSegments
                e.finish(rec, JobState.Failed, s.ackedFileEnds, (pc.failed + pc.badSegments.map { it.file }).distinct().size + s.abandoned.size)
            }
            else -> {}
        }
    }

    private fun applyDecision(relay: Relay, rec: JobRecord, d: DecisionFile) {
        val s = rec.send!!
        rec.decision = d.decision
        if (d.decision == Decision.Decline) {
            relay.deleteJob(rec.id)
            return e.finish(rec, JobState.Declined)
        }
        val count = e.manifest(rec).files.size
        s.skip = d.skip.filter { it in 0 until count }.distinct()
        rec.filesSkipped = s.skip.size
        if (s.skip.isNotEmpty()) {
            // New layout without the skipped files; chunks already pre-sent still apply harmlessly.
            val skip = s.skip.toSet()
            s.layoutFiles = (0 until count).filter { it !in skip }
            s.layoutStart = s.nextSeq
            s.inProgress.clear()
        }
        rec.state = JobState.Transferring
        rec.startedAt = e.now()
        e.save(rec)
    }

    /** A chunk the receiver deleted counts as acknowledged. */
    private fun collectAcks(relay: Relay, rec: JobRecord) {
        val s = rec.send!!
        if (s.unacked.isEmpty()) return
        val present = relay.listChunks(rec.id).map { it.first }.toSet()
        val acked = s.unacked.keys.filter { it !in present }
        if (acked.isEmpty()) return
        for (seq in acked) {
            val a = s.unacked.remove(seq)!!
            rec.bytesTransferred += a.bytes
            s.ackedFileEnds += a.fileEnds
        }
        e.save(rec)
    }

    fun cancelled(relay: Relay, rec: JobRecord) {
        if (rec.state.isFinal) return
        relay.deleteJob(rec.id)
        e.finish(rec, JobState.Cancelled, rec.send!!.ackedFileEnds)
    }

    fun deleteChunks(relay: Relay, rec: JobRecord) {
        relay.listChunks(rec.id).forEach { Files.deleteIfExists(it.second) }
        rec.send!!.unacked.clear()
    }

    /**
     * Resume after failure, as a new generation: files whose source changed are resent in full with a
     * new manifest entry; segments that failed verification are resent alone.
     */
    fun retry(relay: Relay, rec: JobRecord) {
        val s = rec.send!!
        val files = (s.receiverFailed + s.changed).distinct().filter { it !in s.abandoned }.sorted()
        val resent = mutableMapOf<Int, FileEntry>()
        for (i in files) {
            val path = e.manifest(rec).files[i].path
            val attrs = runCatching { Files.readAttributes(e.home.resolve(path), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull()
            if (attrs == null || !attrs.isRegularFile) s.abandoned += i
            else resent[i] = FileEntry(path, attrs.size(), attrs.lastModifiedTime().toMillis())
        }
        val segments = s.receiverBad.filter { it.file !in resent && it.file !in s.abandoned }
        s.gen++
        s.overrides.putAll(resent)
        relay.write(relay.resend(rec.id, s.gen), ResendFile.serializer(), ResendFile(s.gen, resent, segments, s.abandoned.sorted()))
        s.layoutFiles = resent.keys.sorted()
        s.layoutSegments = segments
        s.layoutStart = s.nextSeq
        s.changed.clear(); s.changeReported.clear(); s.inProgress.clear()
        s.receiverFailed = emptyList(); s.receiverBad = emptyList()
        rec.state = JobState.Transferring
        e.log.info("job ${rec.id.take(8)} resending ${resent.size} file(s) and ${segments.size} segment(s), gave up on ${s.abandoned.size}")
        e.save(rec)
    }

    private fun expected(rec: JobRecord): List<Int> {
        val skip = rec.send!!.skip.toSet()
        return e.manifest(rec).files.indices.filter { it !in skip }
    }

    private fun layout(rec: JobRecord): List<ChunkPlan> {
        val s = rec.send!!
        val key = "${s.layoutStart}:${s.gen}:${s.layoutFiles?.size}"
        layouts[rec.id]?.takeIf { it.key == key }?.let { return it.plans }
        val files = (s.layoutFiles ?: e.manifest(rec).files.indices.toList()).map { it to e.entry(rec, it).size }
        val items = ChunkLayout.wholeFiles(files) + s.layoutSegments.map { LayoutItem(it.file, it.offset, it.length, end = false, atomic = true) }
        return ChunkLayout.plan(items, s.chunkSize, s.layoutStart).also { layouts[rec.id] = Layout(key, it) }
    }

    private fun hasMore(rec: JobRecord) = rec.send!!.nextSeq < rec.send!!.layoutStart + layout(rec).size

    /**
     * Fills the shared window: accepted, non-paused jobs take turns; offers may pre-send one chunk
     * each, only with room left over. Each sender also keeps to a fair share of the window, so
     * senders that cannot see each other's newest chunks yet overshoot by at most one chunk each.
     */
    suspend fun writeChunks(relay: Relay): Boolean {
        val window = e.settings.windowChunks
        val mine = e.records.values.filter { it.direction == Direction.Sent && it.myRequest != ControlRequest.Cancel }
        val senders = (relay.activeSenders() + e.machineId).size
        val share = (window + senders - 1) / senders
        var room = minOf(window - relay.countAllChunks(), share - mine.sumOf { relay.countChunks(it.id) })
        if (room <= 0) return false
        var wrote = false
        while (room > 0) {
            val active = mine.filter { it.state == JobState.Transferring && hasMore(it) }.sortedBy { it.createdAt }
            if (active.isEmpty()) break
            if (writeNext(relay, active[roundRobin++ % active.size])) room--
            wrote = true
            e.drainCommands()
        }
        for (rec in mine.filter { it.state == JobState.Offered && it.send!!.preSent < 1 }.sortedBy { it.createdAt }) {
            if (room <= 0) break
            if (!hasMore(rec)) continue
            if (writeNext(relay, rec)) room--
            rec.send!!.preSent++
            wrote = true
            e.save(rec)
        }
        return wrote
    }

    /** Writes the next chunk of the layout; returns false if nothing was left in it to write. */
    private fun writeNext(relay: Relay, rec: JobRecord): Boolean {
        val s = rec.send!!
        val plan = layout(rec)[s.nextSeq - s.layoutStart]
        // Re-stat every file this chunk touches: a changed file fails right away, not after streaming all of it.
        for (file in plan.segments.map { it.file }.distinct()) if (file !in s.changed && !unchanged(rec, file)) markChanged(rec, file)
        val records = mutableListOf<Pair<Segment, Boolean>>() // segment, true = report "source changed"
        for (seg in plan.segments) when {
            seg.file !in s.changed -> records += seg to false
            seg.file !in s.changeReported -> { records += seg.copy(length = 0, end = true) to true; s.changeReported += seg.file }
        }
        if (records.isEmpty()) {
            s.nextSeq++
            e.save(rec)
            return false
        }
        val recordCount = records.sumOf { (seg, _) -> (if (seg.length > 0) 1 else 0) + (if (seg.end) 1 else 0) }
        val header = ChunkCodec.Header(UUID.fromString(rec.id), plan.seq, s.gen, recordCount)
        AtomicFiles.writeWith(relay.chunk(rec.id, plan.seq), relay.tmpTag) { out ->
            val w = ChunkCodec.Writer(out, header)
            for ((seg, changed) in records) {
                val entry = e.entry(rec, seg.file)
                e.currentFile[rec.id] = entry.path
                if (seg.length > 0) w.data(seg.file, seg.offset, seg.length.toInt()) { copySegment(rec, seg, entry, it) }
                if (!seg.end) continue
                if (changed || seg.file in s.changed) {
                    w.fileEnd(seg.file, ChunkCodec.SOURCE_CHANGED, emptyList()); s.changeReported += seg.file
                } else {
                    w.fileEnd(seg.file, 0, s.inProgress[seg.file].orEmpty().sortedBy { it.offset })
                }
            }
            w.finish()
        }
        for ((seg, _) in records) if (seg.end) s.inProgress.remove(seg.file)
        s.unacked[plan.seq] = Ack(records.sumOf { it.first.length }, records.count { it.first.end })
        s.nextSeq++
        e.save(rec)
        return true
    }

    private fun unchanged(rec: JobRecord, file: Int): Boolean {
        val entry = e.entry(rec, file)
        return runCatching {
            val a = Files.readAttributes(e.home.resolve(entry.path), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            a.isRegularFile && a.size() == entry.size && a.lastModifiedTime().toMillis() == entry.mtime
        }.getOrDefault(false)
    }

    private fun markChanged(rec: JobRecord, file: Int) {
        rec.send!!.changed += file
        e.log.warn("source changed while sending: ${e.entry(rec, file).path}")
    }

    /**
     * Streams one segment of a source file into the chunk and hashes it on the way. If the file turns
     * out shorter or changed meanwhile, the rest is zero-filled and the file is marked changed.
     */
    private fun copySegment(rec: JobRecord, seg: Segment, entry: FileEntry, out: OutputStream) {
        val s = rec.send!!
        val md = Hashing.sha256()
        val end = seg.offset + seg.length
        var pos = seg.offset
        try {
            FileChannel.open(e.home.resolve(entry.path), READ, NOFOLLOW_LINKS).use { ch ->
                val buf = ByteBuffer.allocate(1 shl 20)
                while (pos < end) {
                    buf.clear().limit(minOf(buf.capacity().toLong(), end - pos).toInt())
                    val n = ch.read(buf, pos)
                    if (n < 0) break
                    md.update(buf.array(), 0, n)
                    out.write(buf.array(), 0, n)
                    pos += n
                }
            }
        } catch (_: IOException) {
        }
        e.stats.sourceBytesRead.addAndGet(pos - seg.offset)
        if (pos < end) {
            val zeros = ByteArray(64 * 1024)
            while (pos < end) {
                val k = minOf(end - pos, zeros.size.toLong()).toInt()
                out.write(zeros, 0, k); pos += k
            }
            if (seg.file !in s.changed) markChanged(rec, seg.file)
        } else if (seg.file !in s.changed && !unchanged(rec, seg.file)) {
            markChanged(rec, seg.file)
        }
        val list = s.inProgress.getOrPut(seg.file) { mutableListOf() }
        list.removeAll { it.offset < end && seg.offset < it.offset + it.length }
        list += SegHash(seg.offset, seg.length, Hashing.hex(md.digest()))
    }

    fun progress(rec: JobRecord): Engine.Progress {
        val s = rec.send!!
        val expected = expected(rec)
        val total = expected.sumOf { e.entry(rec, it).size }
        val doneFiles = if (rec.state == JobState.Completed) expected.size else s.ackedFileEnds.coerceAtMost(expected.size)
        return Engine.Progress(expected.size, total, doneFiles, rec.bytesTransferred.coerceAtMost(total))
    }
}
