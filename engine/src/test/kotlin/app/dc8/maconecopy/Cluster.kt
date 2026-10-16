package app.dc8.maconecopy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

val TEST_SETTINGS = Settings(
    windowChunks = 5,
    chunkSize = 8 * 1024,
    pollMillis = 20,
    heartbeatMillis = 100,
    onlineWindowMillis = 60_000,
    readTimeoutMillis = 5_000,
)

class Machine(val name: String, base: Path, private val settings: Settings) {
    val relayView: Path = base.resolve("onedrive-$name")
    val paths = AppPaths(base.resolve("$name/appdata"))
    val home: Path = base.resolve("$name/home")
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Free space the engine sees on its home volume. */
    val freeSpace = AtomicLong(Long.MAX_VALUE)
    /** Every engine instance started for this machine, so counters can be summed across restarts. */
    val engines = mutableListOf<Engine>()
    lateinit var engine: Engine
    private var job: Job? = null

    private var configure: (Settings) -> Settings = { it }

    /** Starts (or restarts) the engine; a restart keeps the settings tweaks of the first start. */
    fun start(scope: CoroutineScope, configure: ((Settings) -> Settings)? = null) {
        configure?.let { this.configure = it }
        val s = this.configure(settings.copy(relayDir = relayView.toString(), displayName = name, homeDir = home.toString()))
        engine = Engine(paths, s, { logs += it; println(it) }) { freeSpace.get() }
        engines += engine
        job = engine.start(scope)
    }

    suspend fun kill() {
        job!!.cancelAndJoin()
    }

    fun view(id: String) = engine.jobs.value.firstOrNull { it.id == id }
    fun state(id: String) = view(id)?.state ?: engine.history.value.lastOrNull { it.jobId == id }?.outcome
    fun historyOf(id: String) = engine.history.value.last { it.jobId == id }
}

/** Several engines with separate fake homes sharing one simulated OneDrive relay. */
class Cluster(base: Path, names: List<String>, seed: Long, settings: Settings = TEST_SETTINGS, configure: (String, Settings) -> Settings = { _, s -> s }) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val machines = names.map { Machine(it, base, settings) }
    val drive = FakeOneDrive(machines.map { it.relayView }, Random(seed))

    init {
        drive.start(scope)
        machines.forEach { m -> m.start(scope) { configure(m.name, it) } }
    }

    operator fun get(name: String) = machines.first { it.name == name }

    suspend fun awaitPeers() = eventually("peers visible") {
        machines.all { m -> m.engine.peers.value.map { it.id }.toSet() == machines.filter { it != m }.map { it.engine.machineId }.toSet() }
    }

    override fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
}

suspend fun eventually(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > end) fail("timed out waiting for: $what")
        delay(20)
    }
}

/** Writes a file with random content and an mtime in the past. */
fun writeFile(home: Path, rel: String, size: Int, rnd: Random, mtime: Long = 1_600_000_000_000L + rnd.nextLong(0, 100_000_000_000L)): Path {
    val p = home.resolve(rel)
    Files.createDirectories(p.parent)
    Files.write(p, rnd.nextBytes(size))
    Files.setLastModifiedTime(p, FileTime.fromMillis(mtime))
    return p
}

fun makeProject(home: Path, rnd: Random, bigSize: Int = 60_000) {
    writeFile(home, "Projects/a.txt", 3_000, rnd)
    writeFile(home, "Projects/sub/big.bin", bigSize, rnd)
    writeFile(home, "Projects/sub/empty.dat", 0, rnd)
    writeFile(home, "Projects/x/y/z.txt", 100, rnd)
    Files.createDirectories(home.resolve("Projects/emptydir"))
    writeFile(home, "Documents/report.xlsx", 5_000, rnd)
}

/** Byte-identical files, equal mtimes, same empty directories. */
fun assertSameTree(expected: Path, actual: Path) {
    fun list(root: Path) = Files.walk(root).use { s -> s.filter { it != root }.toList() }
        .associateBy { root.relativize(it).joinToString("/") }
    val e = list(expected)
    val a = list(actual)
    assertEquals(e.keys.sorted(), a.keys.sorted(), "entries under $actual")
    for ((rel, ep) in e) {
        val ap = a.getValue(rel)
        if (Files.isDirectory(ep, NOFOLLOW_LINKS)) continue
        assertContentEquals(Files.readAllBytes(ep), Files.readAllBytes(ap), "content of $rel")
        assertEquals(Files.getLastModifiedTime(ep).toMillis(), Files.getLastModifiedTime(ap).toMillis(), "mtime of $rel")
    }
}

fun partFiles(root: Path): List<Path> = Files.walk(root).use { s -> s.filter { it.fileName.toString().endsWith(".part") }.toList() }
