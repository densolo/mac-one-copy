package app.dc8.maconecopy.job

import app.dc8.maconecopy.AppPaths
import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Direction
import app.dc8.maconecopy.HistoryEntry
import app.dc8.maconecopy.JobIssue
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.chunk.SegHash
import app.dc8.maconecopy.chunk.SegmentMap
import app.dc8.maconecopy.relay.SegmentRef
import app.dc8.maconecopy.fs.AtomicFiles
import app.dc8.maconecopy.json
import app.dc8.maconecopy.relay.ControlRequest
import app.dc8.maconecopy.relay.FileEntry
import app.dc8.maconecopy.relay.Manifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Local state of one job on this machine. Persisted after every change. */
@Serializable
data class JobRecord(
    val id: String,
    val direction: Direction,
    val peerId: String,
    var peerName: String,
    val roots: List<String>,
    val createdAt: Long,
    var state: JobState = JobState.Offered,
    var decision: Decision? = null,
    var startedAt: Long = 0,
    var endedAt: Long = 0,
    /** When the job folder was seen gone after the job finished; the record is pruned later. */
    var goneAt: Long = 0,
    // Control: my requests and what the peer confirmed.
    var mySeq: Int = 0,
    var myRequest: ControlRequest? = null,
    var myRequestAt: Long = 0,
    var peerAck: Int = 0,
    var ackedPeerSeq: Int = 0,
    var pauseChangedAt: Long = 0,
    /** Why the job is paused, when it was not a person (e.g. "disk full"). */
    var pauseReason: String? = null,
    // Stats.
    var bytesTransferred: Long = 0,
    var filesSkipped: Int = 0,
    /** What has not made it, with reasons; shown per job and kept in history. */
    val issues: MutableList<JobIssue> = mutableListOf(),
    var send: SendState? = null,
    var recv: RecvState? = null,
)

@Serializable
data class SendState(
    /** Chunk size is fixed per job so the layout never changes under a running transfer. */
    val chunkSize: Long,
    var skip: List<Int> = emptyList(),
    /** Files laid out in full in the current generation; null means all files. */
    var layoutFiles: List<Int>? = null,
    /** Single segments resent in the current generation, after [layoutFiles]. */
    var layoutSegments: List<SegmentRef> = emptyList(),
    var layoutStart: Int = 0,
    var nextSeq: Int = 0,
    /** Resend generation, written into every chunk header. */
    var gen: Int = 0,
    var preSent: Int = 0,
    /** Written but not yet deleted by the receiver: seq → (data bytes, files ended). */
    val unacked: MutableMap<Int, Ack> = mutableMapOf(),
    var ackedFileEnds: Int = 0,
    /** Segment hashes of files whose FILE_END is not written yet; no file is ever re-read to hash it. */
    val inProgress: MutableMap<Int, MutableList<SegHash>> = mutableMapOf(),
    /** Files whose source changed or vanished while being sent, and those already reported in a FILE_END. */
    val changed: MutableSet<Int> = mutableSetOf(),
    val changeReported: MutableSet<Int> = mutableSetOf(),
    val overrides: MutableMap<Int, FileEntry> = mutableMapOf(),
    val abandoned: MutableSet<Int> = mutableSetOf(),
    var receiverFailed: List<Int> = emptyList(),
    var receiverBad: List<SegmentRef> = emptyList(),
)

@Serializable
data class Ack(val bytes: Long, val fileEnds: Int)

@Serializable
data class RecvState(
    /** Root → destination root (identity unless accepted as copy). */
    val rootMap: Map<String, String>,
    val skip: Set<Int> = emptySet(),
    var gen: Int = 0,
    val done: MutableSet<Int> = mutableSetOf(),
    val failed: MutableSet<Int> = mutableSetOf(),
    /** Files kept as `.part` whose listed segments failed verification, waiting for a resend. */
    val bad: MutableMap<Int, List<SegHash>> = mutableMapOf(),
    val abandoned: MutableSet<Int> = mutableSetOf(),
    val partial: MutableMap<Int, PartFile> = mutableMapOf(),
    val overrides: MutableMap<Int, FileEntry> = mutableMapOf(),
    /** File → resend gen it was reset at; records from older chunks are ignored. */
    val resetGen: MutableMap<Int, Int> = mutableMapOf(),
    /** Symlinks are created once, after every file is in place. */
    var linksDone: Boolean = false,
)

/** A file being received: verified segments, and the segment lists of the FILE_END records seen. */
@Serializable
data class PartFile(val segs: SegmentMap = SegmentMap(), val ends: MutableList<List<SegHash>> = mutableListOf())

class JobStore(private val paths: AppPaths) {
    private fun recordFile(id: String) = paths.jobsDir.resolve("$id.json")
    private fun manifestFile(id: String) = paths.jobsDir.resolve("$id.manifest.json")

    fun save(rec: JobRecord) = AtomicFiles.write(recordFile(rec.id), json.encodeToString(JobRecord.serializer(), rec).toByteArray())
    fun saveManifest(m: Manifest) = AtomicFiles.write(manifestFile(m.jobId), json.encodeToString(Manifest.serializer(), m).toByteArray())
    fun loadManifest(id: String): Manifest = json.decodeFromString(Manifest.serializer(), manifestFile(id).readText())

    fun loadAll(): List<JobRecord> {
        if (!paths.jobsDir.exists()) return emptyList()
        return Files.list(paths.jobsDir).use { s -> s.toList() }
            .filter { it.fileName.toString().let { n -> n.endsWith(".json") && !n.endsWith(".manifest.json") } }
            .mapNotNull { runCatching { json.decodeFromString(JobRecord.serializer(), it.readText()) }.getOrNull() }
    }

    fun delete(id: String) {
        Files.deleteIfExists(recordFile(id)); Files.deleteIfExists(manifestFile(id))
    }
}

/** Append-only JSON lines; the last entry per job wins (a failed job can later complete). */
class HistoryLog(private val paths: AppPaths) {
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    private fun load(): List<HistoryEntry> {
        if (!paths.historyFile.exists()) return emptyList()
        val byJob = LinkedHashMap<String, HistoryEntry>()
        paths.historyFile.readText().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            runCatching { json.decodeFromString(HistoryEntry.serializer(), line) }.getOrNull()?.let { byJob.remove(it.jobId); byJob[it.jobId] = it }
        }
        return byJob.values.toList()
    }

    fun append(entry: HistoryEntry) {
        Files.createDirectories(paths.appData)
        Files.write(paths.historyFile, (json.encodeToString(HistoryEntry.serializer(), entry) + "\n").toByteArray(), CREATE, WRITE, APPEND)
        _entries.value = _entries.value.filter { it.jobId != entry.jobId } + entry
    }
}
