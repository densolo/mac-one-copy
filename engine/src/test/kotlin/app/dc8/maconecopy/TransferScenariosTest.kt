package app.dc8.maconecopy

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferScenariosTest {
    @TempDir lateinit var tmp: Path

    private fun cluster(seed: Long, vararg names: String = arrayOf("A", "B")) = Cluster(tmp, names.toList(), seed)

    private suspend fun offer(c: Cluster, from: String, to: String, vararg roots: String): String {
        val id = c[from].engine.send(roots.toList(), c[to].engine.machineId).jobId
        eventually("offer $id reaches $to") { c[to].view(id)?.incomingOffer == true }
        return id
    }

    private suspend fun awaitBoth(c: Cluster, id: String, state: JobState, vararg names: String = arrayOf("A", "B")) =
        eventually("job $id is $state on ${names.toList()}") { names.all { c[it].state(id) == state } }

    @Test
    fun `peers see each other but never themselves`() = runBlocking {
        cluster(1, "A", "B", "C").use { c ->
            c.awaitPeers()
            val a = c["A"].engine
            assertEquals(setOf("B", "C"), a.peers.value.map { it.displayName }.toSet())
            assertTrue(a.peers.value.all { it.online })
        }
    }

    @Test
    fun `accept replaces changed files, skips identical ones and trashes originals`() = runBlocking {
        cluster(2).use { c ->
            val rnd = Random(2)
            makeProject(c["A"].home, rnd)
            // B already has an identical a.txt (skipped) and an older report (replaced and trashed).
            val a = c["A"].home.resolve("Projects/a.txt")
            Files.createDirectories(c["B"].home.resolve("Projects"))
            Files.copy(a, c["B"].home.resolve("Projects/a.txt"))
            Files.setLastModifiedTime(c["B"].home.resolve("Projects/a.txt"), Files.getLastModifiedTime(a))
            val oldReport = writeFile(c["B"].home, "Documents/report.xlsx", 1234, rnd).let { Files.readAllBytes(it) }
            c.awaitPeers()

            val id = offer(c, "A", "B", "Projects", "Documents/report.xlsx")
            val details = c["B"].engine.inspectOffer(id)
            assertEquals(FileStatus.Same, details.files.first { it.path == "Projects/a.txt" }.status)
            assertEquals(FileStatus.Changed, details.files.first { it.path == "Documents/report.xlsx" }.status)
            assertEquals(FileStatus.New, details.files.first { it.path == "Projects/sub/big.bin" }.status)

            c["B"].engine.decide(id, Decision.Accept)
            awaitBoth(c, id, JobState.Completed)

            assertSameTree(c["A"].home.resolve("Projects"), c["B"].home.resolve("Projects"))
            assertSameTree(c["A"].home.resolve("Documents"), c["B"].home.resolve("Documents"))
            assertContentEquals(oldReport, Files.readAllBytes(c["B"].paths.trashDir.resolve("$id/Documents/report.xlsx")))
            assertEquals(1, c["B"].historyOf(id).filesSkipped)
            assertEquals(4, c["B"].historyOf(id).filesTransferred)
            assertEquals(1, c["A"].historyOf(id).filesSkipped)
            assertTrue(partFiles(c["B"].home).isEmpty())
            eventually("job folder removed") { !Files.exists(c["A"].relayView.resolve("relay/jobs/$id/manifest.json")) }
        }
    }

    @Test
    fun `an offer into a protected folder is declined automatically`() = runBlocking {
        cluster(15).use { c ->
            c.awaitPeers()
            // An older or foreign sender could still offer Desktop/...; write such a manifest by hand.
            val id = java.util.UUID.randomUUID().toString()
            val m = app.dc8.maconecopy.relay.Manifest(id, c["A"].engine.machineId, "A", c["B"].engine.machineId, System.currentTimeMillis(),
                listOf("Desktop/shot.png"), listOf(app.dc8.maconecopy.relay.FileEntry("Desktop/shot.png", 3, 0)))
            val relay = app.dc8.maconecopy.relay.Relay(c["A"].relayView.resolve("relay"), c["A"].engine.machineId, 5_000, Logger("t"))
            relay.write(relay.manifest(id), app.dc8.maconecopy.relay.Manifest.serializer(), m)
            eventually("declined on B") { c["B"].state(id) == JobState.Declined }
            eventually("decision reaches A") { Files.exists(c["A"].relayView.resolve("relay/jobs/$id/decision.json")) }
            assertFalse(Files.exists(c["B"].home.resolve("Desktop")))
            assertTrue(c["B"].logs.any { "Desktop is a macOS-protected folder" in it })
        }
    }

    @Test
    fun `accept as copy places file and directory roots next to the originals`() = runBlocking {
        cluster(3).use { c ->
            val rnd = Random(3)
            makeProject(c["A"].home, rnd)
            val b = c["B"].home
            writeFile(b, "Projects/mine.txt", 10, rnd)
            writeFile(b, "Projects (copy)/older.txt", 10, rnd)
            val report = writeFile(b, "Documents/report.xlsx", 77, rnd).let { Files.readAllBytes(it) }
            c.awaitPeers()

            val id = offer(c, "A", "B", "Projects", "Documents/report.xlsx")
            c["B"].engine.decide(id, Decision.AcceptCopy)
            awaitBoth(c, id, JobState.Completed)

            assertSameTree(c["A"].home.resolve("Projects"), b.resolve("Projects (copy 2)"))
            assertContentEquals(Files.readAllBytes(c["A"].home.resolve("Documents/report.xlsx")), Files.readAllBytes(b.resolve("Documents/report (copy).xlsx")))
            assertEquals(listOf("mine.txt"), Files.list(b.resolve("Projects")).use { s -> s.map { it.fileName.toString() }.toList() })
            assertEquals(listOf("older.txt"), Files.list(b.resolve("Projects (copy)")).use { s -> s.map { it.fileName.toString() }.toList() })
            assertContentEquals(report, Files.readAllBytes(b.resolve("Documents/report.xlsx")))
            assertFalse(Files.exists(c["B"].paths.trashDir.resolve(id)))
        }
    }

    @Test
    fun `decline removes the job on both sides`() = runBlocking {
        cluster(4).use { c ->
            makeProject(c["A"].home, Random(4))
            c.awaitPeers()
            val id = offer(c, "A", "B", "Projects")
            c["B"].engine.decide(id, Decision.Decline)
            awaitBoth(c, id, JobState.Declined)
            assertFalse(Files.exists(c["B"].home.resolve("Projects")))
            eventually("job folder removed") { !Files.exists(c["A"].relayView.resolve("relay/jobs/$id/manifest.json")) }
        }
    }

    @Test
    fun `pause and resume from either side, surviving a restart while paused`() = runBlocking {
        cluster(5).use { c ->
            makeProject(c["A"].home, Random(5), bigSize = 400_000)
            c.awaitPeers()
            val id = offer(c, "A", "B", "Projects")
            c["B"].engine.decide(id, Decision.Accept)
            eventually("transfer started") { (c["B"].view(id)?.doneBytes ?: 0) > 0 }

            c["B"].engine.pause(id)
            eventually("paused and confirmed") {
                c["A"].state(id) == JobState.Paused && c["B"].view(id)?.let { it.state == JobState.Paused && it.pending == null } == true }
            val done = c["B"].view(id)!!.doneBytes
            delay(500)
            assertEquals(done, c["B"].view(id)!!.doneBytes, "receiver consumes nothing while paused")

            // Paused jobs stay paused across a restart.
            c["B"].kill()
            c["B"].start(c.scope)
            eventually("restarted engine reloads job") { c["B"].view(id) != null }
            delay(300)
            assertEquals(JobState.Paused, c["B"].state(id))

            c["A"].engine.resume(id)
            eventually("resumed") { c["B"].state(id) == JobState.Transferring && c["A"].view(id)?.pending == null }
            c["A"].engine.pause(id)
            eventually("paused by sender") { c["B"].state(id) == JobState.Paused && c["A"].view(id)?.pending == null }
            c["B"].engine.resume(id)
            awaitBoth(c, id, JobState.Completed)
            assertSameTree(c["A"].home.resolve("Projects"), c["B"].home.resolve("Projects"))
        }
    }

    @Test
    fun `cancel from the sender`() = runBlocking { cancelScenario(6, "A") }

    @Test
    fun `cancel from the receiver`() = runBlocking { cancelScenario(7, "B") }

    private suspend fun cancelScenario(seed: Long, canceller: String) {
        cluster(seed).use { c ->
            makeProject(c["A"].home, Random(seed), bigSize = 400_000)
            c.awaitPeers()
            val id = offer(c, "A", "B", "Projects")
            c["B"].engine.decide(id, Decision.Accept)
            eventually("transfer started") { (c["B"].view(id)?.doneBytes ?: 0) > 20_000 }
            c[canceller].engine.cancel(id)
            awaitBoth(c, id, JobState.Cancelled)
            eventually("part files removed") { partFiles(c["B"].home).isEmpty() }
            eventually("job folder removed") { !Files.exists(c["A"].relayView.resolve("relay/jobs/$id/manifest.json")) }
            assertFalse(Files.exists(c["B"].home.resolve("Projects/sub/big.bin")), "the big file never completed")
        }
    }

    @Test
    fun `concurrent jobs in both directions share the window`() = runBlocking {
        cluster(8, "A", "B", "C").use { c ->
            val rnd = Random(8)
            for (m in listOf("A", "B")) for (j in 1..2) {
                writeFile(c[m].home, "out$j/big.bin", 120_000, rnd)
                writeFile(c[m].home, "out$j/small.txt", 500, rnd)
            }
            c.awaitPeers()
            val jobs = listOf("A" to "B", "A" to "B", "B" to "A", "B" to "A").mapIndexed { i, (from, to) ->
                Triple(c[from].engine.send(listOf("out${i % 2 + 1}"), c[to].engine.machineId).jobId, from, to)
            }
            for ((id, _, to) in jobs) {
                eventually("offer $id") { c[to].view(id)?.incomingOffer == true }
                c[to].engine.decide(id, Decision.AcceptCopy)
            }
            for ((id, from, to) in jobs) awaitBoth(c, id, JobState.Completed, from, to)

            for (m in listOf("A", "B")) {
                val other = if (m == "A") "B" else "A"
                for (j in 1..2) assertSameTree(c[other].home.resolve("out$j"), c[m].home.resolve("out$j (copy)"))
            }
            val window = TEST_SETTINGS.windowChunks
            println("max chunks in relay: ${c.drive.maxChunks}")
            // Two senders: each keeps to its share (3 of 5), so sync lag can overshoot by one chunk at most.
            assertTrue(c.drive.maxChunks <= window + 1, "window overshoot: ${c.drive.maxChunks} > $window + 1")
        }
    }

    @Test
    fun `sender and receiver restarted mid-transfer`() = runBlocking {
        cluster(9).use { c ->
            makeProject(c["A"].home, Random(9), bigSize = 400_000)
            c.awaitPeers()
            val id = offer(c, "A", "B", "Projects", "Documents")
            c["B"].engine.decide(id, Decision.Accept)

            eventually("20% done") { (c["B"].view(id)?.progress ?: 0.0) > 0.2 }
            c["A"].kill()
            delay(300)
            c["A"].start(c.scope)

            eventually("60% done") { (c["B"].view(id)?.progress ?: 0.0) > 0.6 }
            c["B"].kill()
            delay(300)
            c["B"].start(c.scope)

            awaitBoth(c, id, JobState.Completed)
            assertSameTree(c["A"].home.resolve("Projects"), c["B"].home.resolve("Projects"))
            assertSameTree(c["A"].home.resolve("Documents"), c["B"].home.resolve("Documents"))
        }
    }

    @Test
    fun `a file changed during send fails the job and resume resends only that file`() = runBlocking {
        cluster(10).use { c ->
            val rnd = Random(10)
            makeProject(c["A"].home, rnd)
            c.awaitPeers()
            val id = offer(c, "A", "B", "Projects")
            // Changed after the manifest was written, before its chunk is.
            val z = c["A"].home.resolve("Projects/x/y/z.txt")
            Files.write(z, rnd.nextBytes(150))
            Files.setLastModifiedTime(z, FileTime.fromMillis(1_650_000_000_000L))

            c["B"].engine.decide(id, Decision.Accept)
            awaitBoth(c, id, JobState.Failed)
            assertEquals(1, c["B"].historyOf(id).filesFailed)

            c["A"].engine.resume(id)
            awaitBoth(c, id, JobState.Completed)
            assertSameTree(c["A"].home.resolve("Projects"), c["B"].home.resolve("Projects"))
            assertTrue(c["A"].logs.any { "resending 1 file(s) and 0 segment(s)" in it })
        }
    }

    // ---- large files -----------------------------------------------------------------------

    @Test
    fun `restart mid-file on both sides without re-reading anything`() = runBlocking {
        cluster(11).use { c ->
            val size = 600_000
            writeFile(c["A"].home, "Big/movie.mov", size, Random(11))
            c.awaitPeers()
            val id = offer(c, "A", "B", "Big")
            c["B"].engine.decide(id, Decision.Accept)

            eventually("30% done") { (c["B"].view(id)?.progress ?: 0.0) > 0.3 }
            c["A"].kill(); delay(200); c["A"].start(c.scope)
            eventually("60% done") { (c["B"].view(id)?.progress ?: 0.0) > 0.6 }
            c["B"].kill(); delay(200); c["B"].start(c.scope)

            awaitBoth(c, id, JobState.Completed)
            assertSameTree(c["A"].home.resolve("Big"), c["B"].home.resolve("Big"))
            // The sender read each byte once, plus at most the chunk it was writing when killed.
            val read = c["A"].engines.sumOf { it.stats.sourceBytesRead.get() }
            assertTrue(read <= size + TEST_SETTINGS.chunkSize, "source read $read bytes for a $size byte file")
            assertEquals(0, c["B"].engines.sumOf { it.stats.partBytesVerified.get() }, "receiver never re-read the file")
        }
    }

    @Test
    fun `a corrupted segment is resent alone`() = runBlocking {
        Cluster(tmp, listOf("A", "B"), 12) { name, s -> if (name == "B") s.copy(verifyFromDisk = true) else s }.use { c ->
            val size = 400_000
            writeFile(c["A"].home, "Big/disk.img", size, Random(12))
            c.awaitPeers()
            val id = offer(c, "A", "B", "Big")
            c["B"].engine.decide(id, Decision.Accept)
            val part = c["B"].home.resolve("Big/disk.img.part")
            eventually("first segments received") { (c["B"].view(id)?.progress ?: 0.0) > 0.3 }
            // Bit rot in a segment that has already been received and checked off.
            java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE).use { it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 100) }

            awaitBoth(c, id, JobState.Failed)
            assertTrue(Files.exists(part), "the file stays as .part")
            c["B"].engine.resume(id)
            awaitBoth(c, id, JobState.Completed)
            assertSameTree(c["A"].home.resolve("Big"), c["B"].home.resolve("Big"))
            assertTrue(c["A"].logs.any { "resending 0 file(s) and 1 segment(s)" in it })
        }
    }

    @Test
    fun `a source modified mid-send fails fast and is resent in full`() = runBlocking {
        cluster(13).use { c ->
            val rnd = Random(13)
            val big = writeFile(c["A"].home, "Big/log.txt", 600_000, rnd)
            writeFile(c["A"].home, "Big/zz-after.txt", 1_000, rnd)
            c.awaitPeers()
            val id = offer(c, "A", "B", "Big")
            c["B"].engine.decide(id, Decision.Accept)
            eventually("transfer started") { (c["B"].view(id)?.progress ?: 0.0) > 0.1 }
            Files.write(big, rnd.nextBytes(10), java.nio.file.StandardOpenOption.APPEND)

            awaitBoth(c, id, JobState.Failed)
            val sent = c["A"].engines.sumOf { it.stats.sourceBytesRead.get() }
            assertTrue(sent < 400_000, "gave up after $sent bytes instead of streaming the whole file")
            assertTrue(Files.exists(c["B"].home.resolve("Big/zz-after.txt")), "other files still arrive")

            c["A"].engine.resume(id)
            awaitBoth(c, id, JobState.Completed)
            assertSameTree(c["A"].home.resolve("Big"), c["B"].home.resolve("Big"))
        }
    }

    @Test
    fun `disk full pauses the job and resume continues it`() = runBlocking {
        cluster(14).use { c ->
            writeFile(c["A"].home, "Big/backup.zip", 400_000, Random(14))
            c.awaitPeers()
            val id = offer(c, "A", "B", "Big")
            c["B"].engine.decide(id, Decision.Accept)
            eventually("transfer started") { (c["B"].view(id)?.progress ?: 0.0) > 0.1 }
            c["B"].freeSpace.set(50_000)

            eventually("paused for disk full on both sides") {
                listOf("A", "B").all { c[it].view(id)?.let { v -> v.state == JobState.Paused && v.pauseReason == "disk full" } == true }
            }
            c["B"].freeSpace.set(Long.MAX_VALUE)
            c["B"].engine.resume(id)
            awaitBoth(c, id, JobState.Completed)
            assertSameTree(c["A"].home.resolve("Big"), c["B"].home.resolve("Big"))
        }
    }
}
