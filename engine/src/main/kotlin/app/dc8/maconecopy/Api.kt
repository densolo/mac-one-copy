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

@Serializable
enum class IssueKind {
    /** Left out on purpose: a symlink pointing outside the item, a special file, an unreadable entry. */
    Skipped,
    /** Could not be transferred: changed while sending, failed its check, could not be written, … */
    Failed,
}

/** Something of a job that did not make it, with the reason. */
@Serializable
data class JobIssue(val path: String, val kind: IssueKind, val message: String)

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
    /** What has not made it so far (skipped or failed), with reasons. */
    val issues: List<JobIssue> = emptyList(),
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
    val issues: List<JobIssue> = emptyList(),
)

@Serializable
enum class FileStatus { New, Changed, Same }

@Serializable
data class OfferFile(val path: String, val size: Long, val mtime: Long, val status: FileStatus)

/**
 * Where one root of an offer would land on this machine: [path] with Accept (merging into or replacing
 * what is there if [exists]), [copyPath] with Accept as copy. Both are absolute. [folder] is the folder
 * it goes into, relative to home ("" for home itself); [moved] is true if it differs from the sender's.
 */
@Serializable
data class OfferTarget(
    val root: String,
    val isDir: Boolean,
    val path: String,
    val copyPath: String,
    val exists: Boolean,
    val folder: String = "",
    val moved: Boolean = false,
)

/** Everything the accept dialog needs. */
@Serializable
data class OfferDetails(
    val job: JobView,
    val files: List<OfferFile>,
    val emptyDirs: List<String>,
    val totalBytes: Long,
    val freeBytesHere: Long,
    val home: String = "",
    val targets: List<OfferTarget> = emptyList(),
    /** Symlinks that will be recreated, as path → target. */
    val links: Map<String, String> = emptyMap(),
    /** What the sender left out, with reasons. */
    val skipped: List<JobIssue> = emptyList(),
)

data class SendResult(val jobId: String, val warnings: List<String>)

class EngineException(message: String) : Exception(message)
