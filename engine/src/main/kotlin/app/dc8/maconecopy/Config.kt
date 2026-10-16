package app.dc8.maconecopy

import app.dc8.maconecopy.fs.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

val USER_HOME: Path = Path(System.getProperty("user.home"))

/** Expands a leading `~` and makes the path absolute. */
fun expandPath(p: String): Path = (if (p == "~" || p.startsWith("~/")) USER_HOME.resolve(p.removePrefix("~").removePrefix("/")) else Path(p))
    .toAbsolutePath().normalize()

/** The app data folder: machine id, settings, job state, history, trash. A second copy just uses another one. */
data class AppPaths(val appData: Path) {
    val settingsFile: Path get() = appData.resolve("settings.json")
    val machineFile: Path get() = appData.resolve("machine.json")
    val lockFile: Path get() = appData.resolve("lock")
    val jobsDir: Path get() = appData.resolve("jobs")
    val historyFile: Path get() = appData.resolve("history.jsonl")
    val trashDir: Path get() = appData.resolve("trash")
    val inboxDir: Path get() = appData.resolve("inbox")
    val snapshotFile: Path get() = appData.resolve("snapshot.json")

    companion object {
        const val ENV_DATA_DIR = "MAC_ONE_COPY_DATA_DIR"
        const val ENV_NAME = "MAC_ONE_COPY_NAME"
        const val ENV_HOME = "MAC_ONE_COPY_HOME"
        val DEFAULT: Path = USER_HOME.resolve("Library/Application Support/mac-one-copy")

        fun of(dataDir: String? = System.getenv(ENV_DATA_DIR)): AppPaths =
            AppPaths(dataDir?.takeIf { it.isNotBlank() }?.let { expandPath(it) } ?: DEFAULT)
    }
}

@Serializable
data class Settings(
    /** The folder inside OneDrive chosen by the user; the relay lives in `<relayDir>/relay`. */
    val relayDir: String? = null,
    val displayName: String? = null,
    /** All transfer paths are relative to this folder; defaults to the user's home. */
    val homeDir: String? = null,
    val windowChunks: Int = 5,
    val chunkSize: Long = 200L * 1024 * 1024,
    val pollMillis: Long = 5_000,
    val heartbeatMillis: Long = 45_000,
    val onlineWindowMillis: Long = 180_000,
    val readTimeoutMillis: Long = 120_000,
    /** Re-read each finished file once before the final rename. */
    val verifyFromDisk: Boolean = false,
    /** Free space kept on the receiver beyond the bytes still to come. */
    val diskMarginBytes: Long = 64L * 1024 * 1024,
    val trashRetentionDays: Int = 14,
) {
    val home: Path get() = homeDir?.let { expandPath(it) } ?: USER_HOME

    /** What is wrong with these settings, for the settings screen; empty if they are fine. */
    fun problems(): List<String> = buildList {
        if (displayName.isNullOrBlank()) add("Display name is empty")
        if (windowChunks !in 1..1000) add("Window must be 1–1000 chunks")
        if (chunkSize !in (64L * 1024)..(1L shl 30)) add("Chunk size must be between 64 KB and 1 GB")
        if (pollMillis !in 100..600_000) add("Poll interval must be between 0.1 s and 10 min")
        if (trashRetentionDays !in 0..3650) add("Trash retention must be 0–3650 days")
        relayDir?.let { if (!Files.isDirectory(expandPath(it).parent ?: expandPath(it))) add("Relay folder's parent does not exist") }
        homeDir?.let {
            if (it.isBlank()) add("Home folder is empty")
            else if (app.dc8.maconecopy.fs.ProtectedFolders.contains(expandPath(it))) add("Home folder is inside a macOS-protected folder (Desktop, Pictures, Library, …)")
        }
    }

    fun save(paths: AppPaths) = AtomicFiles.write(paths.settingsFile, json.encodeToString(serializer(), this).toByteArray())

    companion object {
        fun load(paths: AppPaths): Settings =
            if (paths.settingsFile.exists()) json.decodeFromString(serializer(), paths.settingsFile.readText()) else Settings()

        /** OneDrive's folder under `~/Library/CloudStorage`, if there is one, plus `mac-one-copy`. */
        fun defaultRelayDir(): Path? = runCatching {
            Files.list(USER_HOME.resolve("Library/CloudStorage")).use { s -> s.toList() }
                .filter { it.fileName.toString().startsWith("OneDrive") && Files.isDirectory(it) }
                .minByOrNull { it.fileName.toString() }
                ?.resolve("mac-one-copy")
        }.getOrNull()
    }
}

@Serializable
private data class MachineFile(val id: String)

object MachineIdentity {
    fun load(paths: AppPaths): String {
        if (paths.machineFile.exists()) return json.decodeFromString(MachineFile.serializer(), paths.machineFile.readText()).id
        val id = UUID.randomUUID().toString()
        AtomicFiles.write(paths.machineFile, json.encodeToString(MachineFile.serializer(), MachineFile(id)).toByteArray())
        return id
    }

    val hostName: String by lazy { runCatching { InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrDefault("Mac") }

    /** Replaces `{host}` in a configured name, so run configurations can say `{host} 2`. */
    fun expandName(name: String) = name.replace("{host}", hostName)
}

/** Keeps two processes from using the same app data. */
class DataDirLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        channel.close()
    }

    companion object {
        fun acquire(paths: AppPaths): DataDirLock {
            Files.createDirectories(paths.appData)
            val ch = FileChannel.open(paths.lockFile, CREATE, WRITE)
            val lock = try {
                ch.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                ch.close()
                throw EngineException("${paths.appData} is in use by another mac-one-copy process")
            }
            return DataDirLock(ch, lock)
        }
    }
}

class Logger(private val name: String, private val sink: (String) -> Unit = { System.err.println(it) }) {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    fun info(msg: String) = sink("[$name] $msg")
    fun warn(msg: String) = sink("[$name] WARN $msg")

    /** Logs a message only the first time [key] is seen. */
    fun once(key: String, msg: String) {
        if (seen.add(key)) warn(msg)
    }
}
