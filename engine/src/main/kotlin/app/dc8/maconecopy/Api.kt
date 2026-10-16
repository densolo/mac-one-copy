package app.dc8.maconecopy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val APP_VERSION = "0.1.0"

@Serializable
enum class Direction { Sent, Received }

@Serializable
enum class JobState {
    Offered, Transferring, Paused, Completed, Cancelled, Failed, Declined;

    /** Final states never change again. Failed is not final: it can be resumed. */
    val isFinal get() = this == Completed || this == Cancelled || this == Declined
}

@Serializable
enum class Decision {
    @SerialName("accept") Accept,
    @SerialName("accept-copy") AcceptCopy,
    @SerialName("decline") Decline,
}

/** A request made on this side that the peer has not confirmed yet. */
@Serializable
enum class Pending { Pausing, Resuming, Cancelling }

@Serializable
data class Peer(
    val id: String,
    val displayName: String,
    val user: String,
    val freeBytes: Long,
    val appVersion: String,
    val lastSeen: Long,
    val online: Boolean,
)

@Serializable
data class JobView(
    val id: String,
    val direction: Direction,
    val peerId: String,
    val peerName: String,
    val roots: List<String>,
    val state: JobState,
    val pending: Pending?,
    /** Set when the job paused itself, e.g. "disk full". */
    val pauseReason: String?,
    val decision: Decision?,
    val totalFiles: Int,
    val totalBytes: Long,
    val doneFiles: Int,
    val doneBytes: Long,
    val bytesPerSecond: Double,
    val etaSeconds: Long?,
    val currentFile: String?,
    val createdAt: Long,
) {
    val incomingOffer get() = direction == Direction.Received && state == JobState.Offered
    val progress get() = if (totalBytes == 0L) (if (state == JobState.Completed) 1.0 else 0.0) else doneBytes.toDouble() / totalBytes
}

@Serializable
data class HistoryEntry(
    val jobId: String,
    val direction: Direction,
    val peerName: String,
    val roots: List<String>,
    val decision: Decision?,
    val filesTransferred: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val bytesTransferred: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val avgBytesPerSecond: Double,
    val outcome: JobState,
)

@Serializable
enum class FileStatus { New, Changed, Same }

@Serializable
data class OfferFile(val path: String, val size: Long, val mtime: Long, val status: FileStatus)

/** Everything the accept dialog needs. */
@Serializable
data class OfferDetails(
    val job: JobView,
    val files: List<OfferFile>,
    val emptyDirs: List<String>,
    val totalBytes: Long,
    val freeBytesHere: Long,
)

data class SendResult(val jobId: String, val warnings: List<String>)

class EngineException(message: String) : Exception(message)
