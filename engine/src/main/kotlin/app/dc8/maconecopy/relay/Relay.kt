package app.dc8.maconecopy.relay

import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Logger
import app.dc8.maconecopy.fs.AtomicFiles
import app.dc8.maconecopy.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

@Serializable
data class PeerInfo(
    val id: String,
    val displayName: String,
    val user: String,
    val freeBytes: Long,
    val appVersion: String,
    val lastSeen: Long,
)

@Serializable
data class FileEntry(val path: String, val size: Long, val mtime: Long)

@Serializable
data class Manifest(
    val jobId: String,
    val from: String,
    val fromName: String,
    val to: String,
    val createdAt: Long,
    val roots: List<String>,
    /** Sorted by path; chunk records refer to files by their index in this list. */
    val files: List<FileEntry>,
    val emptyDirs: List<String> = emptyList(),
)

@Serializable
data class DecisionFile(val decision: Decision, val skip: List<Int> = emptyList(), val at: Long)

@Serializable
enum class ControlRequest {
    @SerialName("pause") Pause,
    @SerialName("resume") Resume,
    @SerialName("cancel") Cancel,
}

@Serializable
enum class ReceiverStatus {
    @SerialName("transferring") Transferring,
    @SerialName("completed") Completed,
    @SerialName("failed") Failed,
}

@Serializable
data class SegmentRef(val file: Int, val offset: Long, val length: Long)

/**
 * Each side owns one control file per job. `seq` counts that side's requests; `ackPeerSeq` confirms the
 * other side's requests. The receiver also reports its status for resend generation `statusGen`:
 * whole files that failed (source changed) and segments whose hash did not match.
 */
@Serializable
data class ControlFile(
    val seq: Int = 0,
    val request: ControlRequest? = null,
    val reason: String? = null,
    val at: Long = 0,
    val ackPeerSeq: Int = 0,
    val status: ReceiverStatus? = null,
    val statusGen: Int = 0,
    val failed: List<Int> = emptyList(),
    val badSegments: List<SegmentRef> = emptyList(),
)

/**
 * Written by the sender when a failed job is resumed: files resent in full (with their new size/mtime),
 * single segments resent, and files given up on.
 */
@Serializable
data class ResendFile(
    val gen: Int,
    val files: Map<Int, FileEntry> = emptyMap(),
    val segments: List<SegmentRef> = emptyList(),
    val abandoned: List<Int> = emptyList(),
)

object RelayNames {
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    val ID = Regex("^$UUID$")
    val PEER = Regex("^($UUID)\\.json$")
    val CHUNK = Regex("^(\\d{6})\\.chunk$")
    val CONTROL = Regex("^control-($UUID)\\.json$")
    val RESEND = Regex("^resend-(\\d{4})\\.json$")
    const val MANIFEST = "manifest.json"
    const val DECISION = "decision.json"

    fun chunk(seq: Int) = "%06d.chunk".format(seq)
    fun control(machineId: String) = "control-$machineId.json"
    fun resend(gen: Int) = "resend-%04d.json".format(gen)
}

/**
 * The relay folder. Only exact expected names are read; everything else (temp files, OneDrive
 * conflict copies) is ignored. Reads run with a timeout because opening a OneDrive placeholder
 * can block while it downloads.
 */
class Relay(val root: Path, private val myId: String, private val readTimeoutMs: Long, private val log: Logger) {
    val peersDir: Path = root.resolve("peers")
    val jobsDir: Path = root.resolve("jobs")
    val tmpTag = myId.take(8)

    fun jobDir(jobId: String): Path = jobsDir.resolve(jobId)
    fun manifest(jobId: String): Path = jobDir(jobId).resolve(RelayNames.MANIFEST)
    fun decision(jobId: String): Path = jobDir(jobId).resolve(RelayNames.DECISION)
    fun control(jobId: String, machineId: String): Path = jobDir(jobId).resolve(RelayNames.control(machineId))
    fun chunk(jobId: String, seq: Int): Path = jobDir(jobId).resolve(RelayNames.chunk(seq))
    fun resend(jobId: String, gen: Int): Path = jobDir(jobId).resolve(RelayNames.resend(gen))

    /** Runs blocking IO on the IO dispatcher; null on timeout. */
    suspend fun <T> io(timeoutMs: Long = readTimeoutMs, block: () -> T): T? =
        withTimeoutOrNull(timeoutMs) { runInterruptible(Dispatchers.IO) { block() } }

    /** Null if missing, unparseable (logged once) or not downloaded in time. */
    suspend fun <T> read(path: Path, serializer: KSerializer<T>): T? {
        val bytes = try {
            io { Files.readAllBytes(path) } ?: return null.also { log.once("timeout:$path", "timed out reading $path") }
        } catch (_: NoSuchFileException) {
            return null
        } catch (e: java.io.IOException) {
            log.once("io:$path", "cannot read $path: $e"); return null
        }
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: Exception) {
            log.once("parse:$path", "ignoring unparseable $path (${bytes.size} bytes)"); null
        }
    }

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) =
        AtomicFiles.write(path, json.encodeToString(serializer, value).toByteArray(), tmpTag)

    fun listJobIds(): List<String> = list(jobsDir).filter { RelayNames.ID.matches(it.fileName.toString()) && Files.isDirectory(it) }
        .map { it.fileName.toString() }

    fun listPeerFiles(): List<Path> = list(peersDir).filter { RelayNames.PEER.matches(it.fileName.toString()) }

    /** Chunk files of a job sorted by seq. */
    fun listChunks(jobId: String): List<Pair<Int, Path>> = list(jobDir(jobId))
        .mapNotNull { p -> RelayNames.CHUNK.matchEntire(p.fileName.toString())?.let { it.groupValues[1].toInt() to p } }
        .sortedBy { it.first }

    fun listResends(jobId: String): List<Pair<Int, Path>> = list(jobDir(jobId))
        .mapNotNull { p -> RelayNames.RESEND.matchEntire(p.fileName.toString())?.let { it.groupValues[1].toInt() to p } }
        .sortedBy { it.first }

    /** The shared window: every chunk of every job and sender. */
    fun countAllChunks(): Int = listJobIds().sumOf { id -> countChunks(id) }

    fun countChunks(jobId: String): Int = list(jobDir(jobId)).count { RelayNames.CHUNK.matches(it.fileName.toString()) }

    private val senderOf = HashMap<String, String>()

    /** Machines currently sending: senders of jobs that hold chunks or have been accepted. */
    suspend fun activeSenders(): Set<String> = listJobIds().mapNotNull { id ->
        if (countChunks(id) == 0 && !Files.exists(decision(id))) return@mapNotNull null
        senderOf[id] ?: read(manifest(id), Manifest.serializer())?.from?.also { senderOf[id] = it }
    }.toSet()

    /** Deletes chunks first and the manifest last, so a peer never sees a job without a manifest but with data. */
    fun deleteJob(jobId: String) {
        val dir = jobDir(jobId)
        val files = list(dir)
        files.filter { RelayNames.CHUNK.matches(it.fileName.toString()) }.forEach { Files.deleteIfExists(it) }
        files.filter { it.fileName.toString() != RelayNames.MANIFEST }.forEach { runCatching { Files.deleteIfExists(it) } }
        Files.deleteIfExists(manifest(jobId))
        runCatching { Files.deleteIfExists(dir) }
    }

    /** Removes temp files this machine left behind after a crash. */
    fun cleanupOwnTemps() {
        val suffix = ".$tmpTag.tmp"
        (list(peersDir) + listJobIds().flatMap { list(jobDir(it)) })
            .filter { it.fileName.toString().endsWith(suffix) }
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun list(dir: Path): List<Path> = try {
        Files.list(dir).use { it.toList() }
    } catch (_: java.io.IOException) {
        emptyList()
    }
}
