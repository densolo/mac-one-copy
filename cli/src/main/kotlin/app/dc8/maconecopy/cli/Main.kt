package app.dc8.maconecopy.cli

import app.dc8.maconecopy.AppPaths
import app.dc8.maconecopy.DataDirLock
import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Engine
import app.dc8.maconecopy.EngineException
import app.dc8.maconecopy.InboxCommand
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.JobView
import app.dc8.maconecopy.Settings
import app.dc8.maconecopy.Snapshot
import app.dc8.maconecopy.expandPath
import app.dc8.maconecopy.fs.AtomicFiles
import app.dc8.maconecopy.fs.HomeDir
import app.dc8.maconecopy.fs.PathSafetyException
import app.dc8.maconecopy.job.HistoryLog
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

private val json = Json { ignoreUnknownKeys = true }

/** App data plus settings, with `--name` / `--home` applied and saved. */
class Ctx(val paths: AppPaths, val settings: Settings)

class Root : CliktCommand(name = "mac-one-copy-cli") {
    private val dataDir by option("--data-dir", envvar = AppPaths.ENV_DATA_DIR, help = "app data folder (default: ~/Library/Application Support/mac-one-copy)")
    private val name by option("--name", envvar = AppPaths.ENV_NAME, help = "display name; {host} is replaced by the host name")
    private val home by option("--home", envvar = AppPaths.ENV_HOME, help = "folder all transfer paths are relative to (default: ~)")

    override fun help(context: Context) = "Transfer files between Macs through a OneDrive folder."

    override fun run() {
        val paths = AppPaths.of(dataDir)
        var settings = Settings.load(paths)
        val updated = settings.copy(displayName = name ?: settings.displayName, homeDir = home?.let { expandPath(it).toString() } ?: settings.homeDir)
        if (updated != settings || !paths.settingsFile.exists()) { settings = updated; settings.save(paths) }
        currentContext.findOrSetObject { Ctx(paths, settings) }
    }
}

class Run : CliktCommand() {
    private val ctx by requireObject<Ctx>()
    private val relay by option("--relay", help = "folder inside OneDrive used as relay (saved)")
    private val auto by option("--auto", help = "decide incoming offers automatically").choice("accept", "copy", "decline")

    override fun help(context: Context) = "Run the engine in the foreground."

    override fun run() = runBlocking {
        var settings = ctx.settings
        val relayDir = relay?.let { expandPath(it).toString() } ?: settings.relayDir ?: Settings.defaultRelayDir()?.toString()
            ?: throw CliktError("no relay folder: pass --relay <folder inside OneDrive>")
        if (relayDir != settings.relayDir) { settings = settings.copy(relayDir = relayDir); settings.save(ctx.paths) }
        val engine = Engine(ctx.paths, settings, logSink = { println(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = try {
            engine.start(scope)
        } catch (e: EngineException) {
            throw CliktError(e.message)
        }
        println("${engine.displayName} (${engine.machineId}) · home ${settings.home} · relay $relayDir")
        val decision = when (auto) { "accept" -> Decision.Accept; "copy" -> Decision.AcceptCopy; "decline" -> Decision.Decline; else -> null }
        scope.launch {
            val seen = HashMap<String, Pair<JobState, String?>>()
            engine.jobs.collect { jobs ->
                for (j in jobs) {
                    val key = j.state to j.pending?.name
                    if (seen[j.id] == key) continue
                    seen[j.id] = key
                    println("job ${j.id.take(8)} ${line(j)}")
                    if (j.incomingOffer && decision != null) runCatching { engine.decide(j.id, decision) }.onFailure { println("decide failed: ${it.message}") }
                }
            }
        }
        loop.join()
    }
}

class Send : CliktCommand() {
    private val ctx by requireObject<Ctx>()
    private val to by option("--to", help = "peer name or id (prefix)").required()
    private val paths by argument(help = "files or folders: relative to home, or absolute / relative to the current folder").multiple(required = true)

    override fun help(context: Context) = "Offer files or folders to a peer."

    override fun run() {
        val home = HomeDir(ctx.settings.home)
        val roots = paths.map { p ->
            try {
                when {
                    p.startsWith("/") || p.startsWith("~") -> home.relativize(expandPath(p))
                    Files.exists(home.root.resolve(p)) -> home.normalize(p)
                    else -> home.relativize(Path(p).toAbsolutePath().normalize())
                }
            } catch (e: PathSafetyException) {
                throw CliktError("${e.message}")
            }
        }
        inbox(ctx, InboxCommand("send", roots = roots, peer = to))
        println("queued: send ${roots.joinToString()} to $to")
    }
}

class Decide : CliktCommand() {
    private val ctx by requireObject<Ctx>()
    private val job by argument(help = "job id (prefix)")
    private val decision by argument().choice("accept" to Decision.Accept, "copy" to Decision.AcceptCopy, "decline" to Decision.Decline)
    private val into by option("--into", help = "put every item in this folder instead of the sender's (relative to home, or absolute inside it)")

    override fun help(context: Context) = "Accept, accept as copy, or decline an incoming offer."

    override fun run() {
        val folders = into?.let { dir ->
            val home = HomeDir(ctx.settings.home)
            val rel = try {
                if (dir.startsWith("/") || dir.startsWith("~")) expandPath(dir).let { if (it == home.root) "" else home.relativize(it) } else dir.trim('/')
            } catch (e: PathSafetyException) {
                throw CliktError("${e.message}")
            }
            mapOf("*" to rel)
        } ?: emptyMap()
        inbox(ctx, InboxCommand("decide", job = job, decision = decision, folders = folders))
    }
}

class Control(private val op: String) : CliktCommand(name = op) {
    private val ctx by requireObject<Ctx>()
    private val job by argument(help = "job id (prefix)")
    override fun help(context: Context) = "${op.replaceFirstChar { it.uppercase() }} a job (from either side)."
    override fun run() = inbox(ctx, InboxCommand(op, job = job))
}

class Jobs : CliktCommand() {
    private val ctx by requireObject<Ctx>()
    override fun help(context: Context) = "List running and waiting jobs."
    override fun run() {
        val snap = snapshot(ctx) ?: return
        if (snap.jobs.isEmpty()) println("no jobs")
        snap.jobs.forEach { println("${it.id.take(8)}  ${line(it)}") }
    }
}

class Peers : CliktCommand() {
    private val ctx by requireObject<Ctx>()
    override fun help(context: Context) = "List other machines on the relay."
    override fun run() {
        val snap = snapshot(ctx) ?: return
        println("this machine: ${snap.displayName} (${snap.machineId.take(8)}), home ${snap.home}")
        if (snap.peers.isEmpty()) println("no peers")
        for (p in snap.peers) {
            val seen = if (p.online) "online" else "last seen ${ago(p.lastSeen)}"
            println("${p.id.take(8)}  ${p.displayName.padEnd(24)} $seen, ${bytes(p.freeBytes)} free")
        }
    }
}

class Issues : CliktCommand() {
    private val ctx by requireObject<Ctx>()
    private val job by argument(help = "job id (prefix)")
    override fun help(context: Context) = "List what a job left out or failed to transfer, with reasons."
    override fun run() {
        val running = snapshot(ctx)?.jobs.orEmpty().map { it.id to it.issues }
        val finished = HistoryLog(ctx.paths).entries.value.map { it.jobId to it.issues }
        val matches = (running + finished).filter { it.first.startsWith(job) }.distinctBy { it.first }
        val (id, issues) = matches.singleOrNull() ?: throw CliktError(if (matches.isEmpty()) "no job $job" else "ambiguous job id $job")
        if (issues.isEmpty()) return println("${id.take(8)}: nothing left out")
        for (i in issues.sortedWith(compareBy({ it.kind }, { it.path }))) println("${i.kind.name.lowercase().padEnd(8)} ${i.path}  — ${i.message}")
    }
}

class History : CliktCommand() {
    private val ctx by requireObject<Ctx>()
    override fun help(context: Context) = "Show finished jobs and totals per peer."
    override fun run() {
        val entries = HistoryLog(ctx.paths).entries.value
        if (entries.isEmpty()) return println("no history")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
        for (h in entries) {
            val dir = if (h.direction.name == "Sent") "→" else "←"
            println(
                "${fmt.format(Date(h.startedAt))}  ${h.jobId.take(8)} $dir ${h.peerName}  ${h.outcome}  ${h.roots.joinToString()}  " +
                    "${h.filesTransferred} files, ${h.filesSkipped} skipped, ${h.filesFailed} failed, ${bytes(h.bytesTransferred)} " +
                    "in ${h.durationMs / 1000}s (${bytes(h.avgBytesPerSecond.toLong())}/s)" +
                    if (h.issues.isEmpty()) "" else "  ⚠ ${h.issues.size} not transferred (issues ${h.jobId.take(8)})"
            )
        }
        println("totals:")
        entries.groupBy { it.peerName }.forEach { (peer, list) ->
            println("  $peer: ${list.size} jobs, ${bytes(list.sumOf { it.bytesTransferred })}")
        }
    }
}

private fun inbox(ctx: Ctx, cmd: InboxCommand) {
    val file = ctx.paths.inboxDir.resolve("${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
    AtomicFiles.write(file, json.encodeToString(InboxCommand.serializer(), cmd).toByteArray())
    if (!engineRunning(ctx)) println("note: the engine is not running; the command runs when `run` starts")
}

private fun engineRunning(ctx: Ctx): Boolean = runCatching { DataDirLock.acquire(ctx.paths).close() }.isFailure

private fun snapshot(ctx: Ctx): Snapshot? {
    if (!ctx.paths.snapshotFile.exists()) { println("no state yet: start `run` first"); return null }
    if (!engineRunning(ctx)) println("(engine not running: showing last known state)")
    return json.decodeFromString(Snapshot.serializer(), ctx.paths.snapshotFile.readText())
}

private fun line(j: JobView): String {
    val dir = if (j.direction.name == "Sent") "→" else "←"
    val state = j.pending?.let { "${it.name}…" } ?: (j.state.name + (j.pauseReason?.let { " ($it)" } ?: ""))
    val eta = j.etaSeconds?.let { " eta ${it}s" } ?: ""
    return "$dir ${j.peerName}  $state  ${j.roots.joinToString()}  ${(j.progress * 100).toInt()}%  " +
        "${bytes(j.doneBytes)}/${bytes(j.totalBytes)}  ${bytes(j.bytesPerSecond.toLong())}/s$eta" + (j.currentFile?.let { "  $it" } ?: "") +
        if (j.issues.isEmpty()) "" else "  ⚠ ${j.issues.size} not transferred"
}

private fun bytes(n: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$n B" else "%.1f %s".format(v, units[i])
}

private fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when { s < 120 -> "${s}s ago"; s < 7200 -> "${s / 60} min ago"; s < 172800 -> "${s / 3600} h ago"; else -> "${s / 86400} days ago" }
}

fun main(args: Array<String>) = Root()
    .subcommands(Run(), Send(), Jobs(), Decide(), Control("pause"), Control("resume"), Control("cancel"), Issues(), History(), Peers())
    .main(args)
