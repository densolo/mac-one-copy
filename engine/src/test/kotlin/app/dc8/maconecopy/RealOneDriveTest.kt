package app.dc8.maconecopy

import app.dc8.maconecopy.fs.deleteRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Runs two engines on this Mac against a real OneDrive folder, e.g.
 * `MOC_REAL_ONEDRIVE=~/Library/CloudStorage/OneDrive-Personal/mac-one-copy-test ./mvnw -pl engine test -Dtest=RealOneDriveTest`.
 *
 * Both engines see the same local OneDrive folder, so this exercises the File Provider (atomic renames,
 * deletes, listing, conflict handling, uploads in the background) but not sync between Macs; for that,
 * run the CLI on two Macs. Each run uses a fresh `run-<time>` folder and removes it at the end.
 */
@EnabledIfEnvironmentVariable(named = "MOC_REAL_ONEDRIVE", matches = ".+")
class RealOneDriveTest {
    @TempDir lateinit var tmp: Path

    private val settings = Settings(
        windowChunks = 5,
        chunkSize = 1024 * 1024,
        pollMillis = 500,
        heartbeatMillis = 2_000,
        readTimeoutMillis = 60_000,
    )

    @Test
    fun `transfer through the real OneDrive folder`() = runBlocking {
        val relay = expandPath(System.getenv("MOC_REAL_ONEDRIVE")).resolve("run-${System.currentTimeMillis()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = Machine("A", tmp, settings)
        val b = Machine("B", tmp, settings)
        try {
            for (m in listOf(a, b)) m.start(scope) { it.copy(relayDir = relay.toString()) }
            eventually("peers visible", 120_000) { a.engine.peers.value.any { it.id == b.engine.machineId } && b.engine.peers.value.any { it.id == a.engine.machineId } }

            val rnd = Random(42)
            writeFile(a.home, "Data/big.bin", 12 * 1024 * 1024 + 123, rnd)
            for (i in 1..30) writeFile(a.home, "Data/small/f$i.txt", rnd.nextInt(0, 20_000), rnd)
            Files.createDirectories(a.home.resolve("Data/empty"))

            val started = System.currentTimeMillis()
            val id = a.engine.send(listOf("Data"), b.engine.machineId).jobId
            eventually("offer arrives", 120_000) { b.view(id)?.incomingOffer == true }
            b.engine.decide(id, Decision.Accept)
            eventually("completed on both sides", 600_000) { a.state(id) == JobState.Completed && b.state(id) == JobState.Completed }
            println("real OneDrive: ${b.historyOf(id).bytesTransferred} bytes in ${System.currentTimeMillis() - started} ms")

            assertSameTree(a.home.resolve("Data"), b.home.resolve("Data"))
            eventually("job folder removed", 60_000) { !Files.exists(relay.resolve("relay/jobs/$id")) || Files.list(relay.resolve("relay/jobs/$id")).use { s -> s.toList().none { it.fileName.toString().endsWith(".chunk") } } }

            // Same tree back as a copy, the other way round.
            val back = b.engine.send(listOf("Data"), a.engine.machineId).jobId
            eventually("offer arrives", 120_000) { a.view(back)?.incomingOffer == true }
            a.engine.decide(back, Decision.AcceptCopy)
            eventually("copy completed", 600_000) { a.state(back) == JobState.Completed && b.state(back) == JobState.Completed }
            assertSameTree(b.home.resolve("Data"), a.home.resolve("Data (copy)"))
            assertTrue(a.logs.none { "engine loop" in it } && b.logs.none { "engine loop" in it }, "no engine loop errors")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            relay.deleteRecursively()
        }
    }
}
