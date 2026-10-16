package app.dc8.maconecopy

import app.dc8.maconecopy.fs.AtomicFiles
import app.dc8.maconecopy.fs.HomeDir
import app.dc8.maconecopy.fs.ProtectedFolders
import app.dc8.maconecopy.fs.deleteRecursively
import app.dc8.maconecopy.job.HistoryLog
import app.dc8.maconecopy.job.JobRecord
import app.dc8.maconecopy.job.JobStore
import app.dc8.maconecopy.job.ManifestBuilder
import app.dc8.maconecopy.job.Receiver
import app.dc8.maconecopy.job.Sender
import app.dc8.maconecopy.relay.ControlFile
import app.dc8.maconecopy.relay.ControlRequest
import app.dc8.maconecopy.relay.FileEntry
import app.dc8.maconecopy.relay.Manifest
import app.dc8.maconecopy.relay.PeerInfo
import app.dc8.maconecopy.relay.Relay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.io.path.readText

/** Counters for tests and diagnostics. */
class EngineStats {
    val sourceBytesRead = AtomicLong()
    val chunkReads = AtomicLong()
    val partBytesVerified = AtomicLong()
}

/**
 * The transfer engine. All state changes happen on one loop coroutine; commands are queued to it,
 * so there is no locking. Heavy IO (chunk writes and reads) also runs on that loop, and queued
 * commands are handled between chunks.
 */
class Engine(
    val paths: AppPaths,
    settings: Settings? = null,
    logSink: ((String) -> Unit)? = null,
    internal val freeSpace: (Path) -> Long = { runCatching { Files.getFileStore(it).usableSpace }.getOrDefault(Long.MAX_VALUE) },
) {
    val machineId: String = MachineIdentity.load(paths)
    var settings: Settings = (settings ?: Settings.load(paths)).let { s ->
        s.copy(displayName = MachineIdentity.expandName(s.displayName ?: "{host}"))
    }
        private set
    val displayName get() = settings.displayName!!
    internal val log = Logger(displayName, logSink ?: { System.err.println(it) })
    internal var home = HomeDir(this.settings.home)
        private set
    internal val store = JobStore(paths)
    private val historyLog = HistoryLog(paths)
    val stats = EngineStats()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    private val _jobs = MutableStateFlow<List<JobView>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers
    val jobs: StateFlow<List<JobView>> = _jobs
    val history: StateFlow<List<HistoryEntry>> = historyLog.entries

    internal val records = LinkedHashMap<String, JobRecord>()
    internal val manifests = HashMap<String, Manifest>()
    internal val currentFile = HashMap<String, String>()
    private val rates = HashMap<String, Rate>()
    private val ignoredJobs = HashSet<String>()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var relayCache: Relay? = null
    private var lastHeartbeat = 0L
    private var lastSnapshot: Snapshot? = null
    private val sender = Sender(this)
    private val receiver = Receiver(this)

    internal fun now() = System.currentTimeMillis()

    // ---- public commands -------------------------------------------------------------------

    /** Starts the engine loop. Fails if another process uses the same app data. */
    fun start(scope: CoroutineScope): Job {
        val lock = DataDirLock.acquire(paths)
        return scope.launch(Dispatchers.IO) {
            try {
                run()
            } finally {
                lock.close()
            }
        }
    }

    suspend fun send(roots: List<String>, peerId: String): SendResult {
        if (peerId == machineId) throw EngineException("cannot send to this machine")
        val content = withContext(Dispatchers.IO) { ManifestBuilder.build(home, roots) }
        val m = newManifest(content, peerId)
        content.warnings.forEach { log.warn(it) }
        call { sender.create(m, peerName(peerId)) }
        return SendResult(m.jobId, content.warnings)
    }

    /**
     * Answers an incoming offer. [folders] optionally puts roots elsewhere: root → folder relative to home
     * ("" = home itself); the root keeps its name inside it.
     */
    suspend fun decide(jobId: String, decision: Decision, folders: Map<String, String> = emptyMap()) =
        call { receiver.decide(job(jobId), decision, folders) }
    suspend fun pause(jobId: String) = call { request(job(jobId), ControlRequest.Pause) }
    suspend fun resume(jobId: String) = call { request(job(jobId), ControlRequest.Resume) }
    suspend fun cancel(jobId: String) = call { request(job(jobId), ControlRequest.Cancel) }
    suspend fun inspectOffer(jobId: String, folders: Map<String, String> = emptyMap()): OfferDetails = call { receiver.inspect(job(jobId), folders) }

    suspend fun updateSettings(transform: (Settings) -> Settings) = call {
        val next = transform(settings)
        next.problems().firstOrNull()?.let { throw EngineException(it) }
        settings = next.copy(displayName = MachineIdentity.expandName(next.displayName ?: "{host}"))
        settings.save(paths)
        home = HomeDir(settings.home)
        relayCache = null
        lastHeartbeat = 0
    }

    suspend fun emptyTrash() = call { paths.trashDir.deleteRecursively() }

    /** Bytes kept in the trash for replaced originals. */
    suspend fun trashSize(): Long = withContext(Dispatchers.IO) {
        if (!Files.exists(paths.trashDir)) 0L
        else Files.walk(paths.trashDir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
    }

    private suspend fun <T> call(block: suspend () -> T): T {
        val cmd = Command(block)
        try {
            commands.send(cmd)
        } catch (_: Exception) {
            throw EngineException("engine is not running")
        }
        @Suppress("UNCHECKED_CAST")
        return cmd.result.await() as T
    }

    private class Command(val block: suspend () -> Any?) {
        val result = CompletableDeferred<Any?>()
    }

    internal fun job(prefix: String): JobRecord {
        records[prefix]?.let { return it }
        val matches = records.values.filter { it.id.startsWith(prefix) }
        return matches.singleOrNull() ?: throw EngineException(if (matches.isEmpty()) "no job $prefix" else "ambiguous job id $prefix")
    }

    // ---- loop ------------------------------------------------------------------------------

    private suspend fun run() {
        try {
            store.loadAll().forEach { rec ->
                runCatching { manifests[rec.id] = store.loadManifest(rec.id); records[rec.id] = rec }
                    .onFailure { log.warn("lost manifest of job ${rec.id}") }
            }
            purgeTrash()
            log.info("engine started, home ${home.root}, relay ${settings.relayDir ?: "not set"}")
            var tempsCleaned = false
            while (true) {
                var busy = false
                try {
                    drainCommands()
                    processInbox()
                    val relay = relay()
                    if (relay != null) {
                        if (!tempsCleaned) { relay.cleanupOwnTemps(); tempsCleaned = true }
                        heartbeat(relay)
                        scanPeers(relay)
                        discover(relay)
                        for (rec in records.values.toList()) {
                            try {
                                if (rec.direction == Direction.Sent) sender.step(relay, rec) else busy = receiver.step(relay, rec) || busy
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                log.once("job:${rec.id}:$e", "job ${rec.id.take(8)}: $e")
                            }
                        }
                        busy = sender.writeChunks(relay) || busy
                        prune(relay)
                    }
                    publish()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log.once("tick:$e", "engine loop: $e\n${e.stackTraceToString().lines().take(8).joinToString("\n")}")
                }
                if (!busy) withTimeoutOrNull(settings.pollMillis) { handle(commands.receive()) }
            }
        } finally {
            commands.close()
            while (true) (commands.tryReceive().getOrNull() ?: break).result.completeExceptionally(EngineException("engine stopped"))
        }
    }

    /** Runs queued commands; called between chunks so the UI stays responsive. */
    internal suspend fun drainCommands() {
        while (true) handle(commands.tryReceive().getOrNull() ?: return)
    }

    private suspend fun handle(cmd: Command) {
        try {
            cmd.result.complete(cmd.block())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            cmd.result.completeExceptionally(e)
        }
    }

    internal fun relay(): Relay? {
        val dir = settings.relayDir ?: return null
        return relayCache ?: Relay(expandPath(dir).resolve("relay"), machineId, settings.readTimeoutMillis, log).also {
            Files.createDirectories(it.jobsDir); Files.createDirectories(it.peersDir)
            relayCache = it
        }
    }

    private fun heartbeat(relay: Relay) {
        if (now() - lastHeartbeat < settings.heartbeatMillis) return
        val info = PeerInfo(machineId, displayName, System.getProperty("user.name"), freeSpace(home.root), APP_VERSION, now())
        relay.write(relay.peersDir.resolve("$machineId.json"), PeerInfo.serializer(), info)
        lastHeartbeat = now()
    }

    private suspend fun scanPeers(relay: Relay) {
        _peers.value = relay.listPeerFiles().filter { it.fileName.toString() != "$machineId.json" }
            .mapNotNull { relay.read(it, PeerInfo.serializer()) }
            .filter { it.id != machineId }
            .map { Peer(it.id, it.displayName, it.user, it.freeBytes, it.appVersion, it.lastSeen, now() - it.lastSeen < settings.onlineWindowMillis) }
            .sortedBy { it.displayName.lowercase() }
    }

    internal fun peerName(peerId: String) = _peers.value.firstOrNull { it.id == peerId }?.displayName ?: peerId.take(8)

    /** Picks up new offers addressed to this machine. */
    private suspend fun discover(relay: Relay) {
        for (id in relay.listJobIds()) {
            if (id in records || id in ignoredJobs) continue
            val m = relay.read(relay.manifest(id), Manifest.serializer()) ?: continue
            if (m.to != machineId) { ignoredJobs += id; continue }
            if (m.jobId != id || !receiver.isValid(m)) {
                log.once("invalid:$id", "ignoring job $id with an invalid manifest"); ignoredJobs += id; continue
            }
            receiver.create(m)
            // Never write into Desktop, Pictures, Library (iCloud) and the like: decline right away so the sender is not left waiting.
            m.roots.firstNotNullOfOrNull { ProtectedFolders.of(it) }?.let { folder ->
                log.warn("declining job ${id.take(8)}: ${ProtectedFolders.message(folder)}")
                receiver.decide(records.getValue(id), Decision.Decline)
            }
        }
    }

    /** Forgets finished jobs a week after their relay folder disappeared. */
    private fun prune(relay: Relay) {
        for (rec in records.values.toList()) {
            if (!rec.state.isFinal) continue
            if (Files.exists(relay.manifest(rec.id))) { rec.goneAt = 0; continue }
            if (rec.goneAt == 0L) { rec.goneAt = now(); save(rec) }
            else if (now() - rec.goneAt > 7 * 24 * 3600_000L) {
                records.remove(rec.id); manifests.remove(rec.id); store.delete(rec.id)
            }
        }
    }

    private fun purgeTrash() {
        val cutoff = now() - settings.trashRetentionDays * 24 * 3600_000L
        runCatching {
            Files.list(paths.trashDir).use { it.toList() }
                .filter { Files.getLastModifiedTime(it).toMillis() < cutoff }
                .forEach { it.deleteRecursively() }
        }
    }

    // ---- shared job helpers ----------------------------------------------------------------

    internal fun save(rec: JobRecord) = store.save(rec)

    private fun newManifest(c: app.dc8.maconecopy.job.ManifestContent, peerId: String) =
        Manifest(UUID.randomUUID().toString(), machineId, displayName, peerId, now(), c.roots, c.files, c.emptyDirs, c.links, c.skipped)

    /** Records what did not make it; one entry per path and kind, the latest reason wins. */
    internal fun addIssue(rec: JobRecord, path: String, kind: IssueKind, message: String) {
        rec.issues.removeAll { it.path == path && it.kind == kind }
        if (rec.issues.size < MAX_ISSUES) rec.issues += JobIssue(path, kind, message)
    }

    /** A path that made it after all (e.g. on resume) is no longer a failure. */
    internal fun clearFailure(rec: JobRecord, path: String) {
        rec.issues.removeAll { it.path == path && it.kind == IssueKind.Failed }
    }

    internal fun manifest(rec: JobRecord) = manifests.getValue(rec.id)

    internal fun entry(rec: JobRecord, index: Int): FileEntry =
        rec.send?.overrides?.get(index) ?: rec.recv?.overrides?.get(index) ?: manifest(rec).files[index]

    internal fun register(rec: JobRecord, m: Manifest) {
        store.saveManifest(m)
        manifests[rec.id] = m
        records[rec.id] = rec
        save(rec)
    }

    internal fun writeControl(relay: Relay, rec: JobRecord) {
        val (failed, bad) = receiver.failures(rec)
        val control = ControlFile(
            seq = rec.mySeq, request = rec.myRequest, reason = rec.pauseReason.takeIf { rec.myRequest == ControlRequest.Pause },
            at = rec.myRequestAt, ackPeerSeq = rec.ackedPeerSeq,
            status = rec.recv?.let { receiver.status(rec) }, statusGen = rec.recv?.gen ?: 0, failed = failed, badSegments = bad,
        )
        relay.write(relay.control(rec.id, machineId), ControlFile.serializer(), control)
    }

    /**
     * Reads the peer's control file, records its confirmations and applies new requests.
     * Pause/resume conflicts are settled by request time (latest wins); cancel always wins.
     */
    internal suspend fun readPeerControl(relay: Relay, rec: JobRecord): ControlFile? {
        val pc = relay.read(relay.control(rec.id, rec.peerId), ControlFile.serializer()) ?: return null
        var dirty = false
        if (pc.ackPeerSeq > rec.peerAck) { rec.peerAck = pc.ackPeerSeq; dirty = true }
        if (pc.seq > rec.ackedPeerSeq) {
            rec.ackedPeerSeq = pc.seq
            dirty = true
            when (pc.request) {
                ControlRequest.Cancel -> if (rec.direction == Direction.Sent) sender.cancelled(relay, rec) else receiver.cancelled(relay, rec, byPeer = true)
                ControlRequest.Pause -> if (pc.at >= rec.pauseChangedAt) {
                    rec.pauseChangedAt = pc.at
                    rec.pauseReason = pc.reason
                    if (rec.state == JobState.Transferring) rec.state = JobState.Paused
                }
                ControlRequest.Resume -> if (pc.at >= rec.pauseChangedAt) {
                    rec.pauseChangedAt = pc.at
                    rec.pauseReason = null
                    if (rec.state == JobState.Paused) rec.state = JobState.Transferring
                    else if (rec.state == JobState.Failed && rec.direction == Direction.Sent) sender.retry(relay, rec)
                }
                null -> {}
            }
            if (!rec.state.isFinal) writeControl(relay, rec)
        }
        if (dirty) save(rec)
        return pc
    }

    /** Pauses a job on this side with a reason, e.g. "disk full"; the peer is told like for any pause. */
    internal fun pauseFor(rec: JobRecord, reason: String) {
        if (rec.state == JobState.Transferring) request(rec, ControlRequest.Pause, reason)
    }

    private fun request(rec: JobRecord, req: ControlRequest, reason: String? = null) {
        val relay = relay() ?: throw EngineException("relay folder not set")
        if (rec.state.isFinal) throw EngineException("job is already ${rec.state}")
        when (req) {
            ControlRequest.Pause -> if (rec.state != JobState.Transferring) throw EngineException("can only pause a running job")
            ControlRequest.Resume -> if (rec.state != JobState.Paused && rec.state != JobState.Failed) throw EngineException("job is not paused or failed")
            ControlRequest.Cancel -> if (rec.state == JobState.Offered) {
                // Nothing started yet: a sender withdraws the offer, a receiver declines it.
                if (rec.direction == Direction.Sent) { relay.deleteJob(rec.id); finish(rec, JobState.Cancelled) }
                else receiver.decide(rec, Decision.Decline)
                return
            }
        }
        rec.mySeq++
        rec.myRequest = req
        rec.myRequestAt = now()
        when (req) {
            ControlRequest.Pause -> { rec.pauseChangedAt = rec.myRequestAt; rec.pauseReason = reason; rec.state = JobState.Paused }
            ControlRequest.Resume -> {
                rec.pauseChangedAt = rec.myRequestAt
                rec.pauseReason = null
                if (rec.state == JobState.Paused) rec.state = JobState.Transferring
                else if (rec.direction == Direction.Sent) sender.retry(relay, rec)
            }
            ControlRequest.Cancel -> if (rec.direction == Direction.Sent) sender.deleteChunks(relay, rec) else receiver.deleteParts(rec)
        }
        writeControl(relay, rec)
        save(rec)
    }

    /** Moves a job to an end state (or Failed) and records it in history. */
    internal fun finish(rec: JobRecord, state: JobState, filesTransferred: Int = 0, filesFailed: Int = 0) {
        rec.state = state
        rec.endedAt = now()
        if (rec.startedAt == 0L) rec.startedAt = rec.createdAt
        val duration = (rec.endedAt - rec.startedAt).coerceAtLeast(0)
        historyLog.append(
            HistoryEntry(
                rec.id, rec.direction, rec.peerName, rec.roots, rec.decision, filesTransferred, rec.filesSkipped, filesFailed,
                rec.bytesTransferred, rec.startedAt, rec.endedAt, duration,
                if (duration > 0) rec.bytesTransferred * 1000.0 / duration else 0.0, state, rec.issues.toList(),
            )
        )
        save(rec)
        currentFile.remove(rec.id)
        sender.forget(rec.id)
        log.info("job ${rec.id.take(8)} ${rec.direction.name.lowercase()} → $state")
    }

    /** Moves a file or directory that is about to be replaced into `trash/<job>/<relPath>`. */
    internal fun trash(jobId: String, rel: String, path: Path) {
        val jobTrash = paths.trashDir.resolve(jobId)
        val target = jobTrash.resolve(rel)
        Files.createDirectories(target.parent)
        target.deleteRecursively()
        Files.move(path, target)
        Files.setLastModifiedTime(jobTrash, FileTime.fromMillis(now()))
    }

    // ---- views -----------------------------------------------------------------------------

    private fun publish() {
        val views = records.values.filter { !it.state.isFinal }.sortedBy { it.createdAt }.map { view(it) }
        _jobs.value = views
        val snap = Snapshot(machineId, displayName, home.root.toString(), _peers.value, views)
        if (snap != lastSnapshot) {
            runCatching { AtomicFiles.write(paths.snapshotFile, json.encodeToString(Snapshot.serializer(), snap).toByteArray()) }
            lastSnapshot = snap
        }
    }

    private fun view(rec: JobRecord): JobView {
        val (totalFiles, totalBytes, doneFiles, doneBytes) = if (rec.direction == Direction.Sent) sender.progress(rec) else receiver.progress(rec)
        val rate = rates.getOrPut(rec.id) { Rate() }.sample(now(), doneBytes, rec.state == JobState.Transferring)
        val pending = rec.myRequest?.takeIf { rec.peerAck < rec.mySeq }?.let {
            when (it) {
                ControlRequest.Pause -> Pending.Pausing
                ControlRequest.Resume -> Pending.Resuming
                ControlRequest.Cancel -> Pending.Cancelling
            }
        }
        val eta = if (rate > 1 && rec.state == JobState.Transferring) ((totalBytes - doneBytes).coerceAtLeast(0) / rate).toLong() else null
        val name = _peers.value.firstOrNull { it.id == rec.peerId }?.displayName ?: rec.peerName
        return JobView(
            rec.id, rec.direction, rec.peerId, name, rec.roots, rec.state, pending, rec.pauseReason.takeIf { rec.state == JobState.Paused },
            rec.decision, totalFiles, totalBytes, doneFiles, doneBytes, rate, eta, currentFile[rec.id], rec.createdAt, rec.issues.toList(),
        )
    }

    internal data class Progress(val totalFiles: Int, val totalBytes: Long, val doneFiles: Int, val doneBytes: Long)

    /** Exponential moving average of bytes per second. */
    private class Rate {
        private var lastAt = 0L
        private var lastBytes = 0L
        private var value = 0.0
        fun sample(at: Long, bytes: Long, active: Boolean): Double {
            if (!active || lastAt == 0L) { if (!active) value = 0.0; lastAt = at; lastBytes = bytes; return value }
            if (at - lastAt >= 500) {
                val inst = (bytes - lastBytes).coerceAtLeast(0) * 1000.0 / (at - lastAt)
                value = if (value == 0.0) inst else value * 0.7 + inst * 0.3
                lastAt = at; lastBytes = bytes
            }
            return value
        }
    }

    // ---- CLI inbox -------------------------------------------------------------------------

    private suspend fun processInbox() {
        val files = runCatching { Files.list(paths.inboxDir).use { it.toList() } }.getOrDefault(emptyList())
            .filter { it.fileName.toString().endsWith(".json") }.sorted()
        for (f in files) {
            val cmd = runCatching { json.decodeFromString(InboxCommand.serializer(), f.readText()) }.getOrNull()
            Files.deleteIfExists(f)
            if (cmd == null) continue
            try {
                when (cmd.op) {
                    "send" -> {
                        val peer = resolvePeer(cmd.peer ?: "")
                        val content = ManifestBuilder.build(home, cmd.roots)
                        content.warnings.forEach { log.warn(it) }
                        val m = newManifest(content, peer)
                        sender.create(m, peerName(peer))
                        log.info("job ${m.jobId} offered to ${peerName(peer)}")
                    }
                    "decide" -> receiver.decide(job(cmd.job!!), cmd.decision!!, cmd.folders)
                    "pause" -> request(job(cmd.job!!), ControlRequest.Pause)
                    "resume" -> request(job(cmd.job!!), ControlRequest.Resume)
                    "cancel" -> request(job(cmd.job!!), ControlRequest.Cancel)
                    else -> log.warn("unknown inbox command ${cmd.op}")
                }
            } catch (e: Exception) {
                log.warn("${cmd.op} failed: ${e.message}")
            }
        }
    }

    internal fun resolvePeer(key: String): String {
        val peers = _peers.value
        peers.firstOrNull { it.id == key }?.let { return it.id }
        val matches = peers.filter { it.id.startsWith(key) || it.displayName.equals(key, ignoreCase = true) }
        return matches.singleOrNull()?.id ?: throw EngineException(if (matches.isEmpty()) "unknown peer $key" else "ambiguous peer $key")
    }
}

private const val MAX_ISSUES = 1000

/** What the engine last published, for the CLI. */
@Serializable
data class Snapshot(val machineId: String, val displayName: String, val home: String, val peers: List<Peer>, val jobs: List<JobView>)

/** A command dropped into `<appdata>/inbox/` by the CLI and executed by the running engine. */
@Serializable
data class InboxCommand(
    val op: String,
    val job: String? = null,
    val roots: List<String> = emptyList(),
    val peer: String? = null,
    val decision: Decision? = null,
    /** For decide: root → folder relative to home to put it in; key "*" means every root. */
    val folders: Map<String, String> = emptyMap(),
)
