package app.dc8.maconecopy.app

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.dc8.maconecopy.AppPaths
import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Engine
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.Path
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Renders every screen of a real app model (two engines on a temporary relay) into PNGs under
 * `app/target/screenshots`, as a smoke test of the UI against live engine state.
 */
class ScreenshotTest {
    @TempDir lateinit var tmp: Path

    private val out: Path = Files.createDirectories(Path("target/screenshots"))

    private fun settings(name: String) = Settings(
        relayDir = tmp.resolve("relay").toString(), displayName = name, homeDir = tmp.resolve("$name/home").toString(),
        chunkSize = 256 * 1024, windowChunks = 3, pollMillis = 100, heartbeatMillis = 300,
    )

    private fun file(home: Path, rel: String, size: Int) {
        val p = home.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, Random(rel.hashCode()).nextBytes(size))
        Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_000_000))
    }

    private suspend fun eventually(what: String, cond: () -> Boolean) = withTimeout(30_000) {
        while (!cond()) delay(50)
    }.also { println("ok: $what") }

    private suspend fun shot(name: String, w: Int = 1000, h: Int = 700, content: @androidx.compose.runtime.Composable () -> Unit) = withContext(Dispatchers.Swing) {
        val scene = ImageComposeScene(w, h, Density(1f)) { AppTheme { Surface { content() } } }
        try {
            scene.render(0)
            delay(300) // let collected flows and LaunchedEffects settle
            scene.render(400_000_000)
            val png = scene.render(800_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            Files.write(out.resolve("$name.png"), png)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `render all screens`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val studio = Engine(AppPaths(tmp.resolve("studio/data")), settings("Studio"))
        val book = Engine(AppPaths(tmp.resolve("book/data")), settings("MacBook"))
        try {
            studio.start(scope); book.start(scope)
            val sHome = studio.settings.home
            file(sHome, "Projects/data.bin", 3_000_000)
            file(sHome, "Projects/src/Main.kt", 1_200)
            file(sHome, "Projects/src/Util.kt", 800)
            file(sHome, "Documents/report.xlsx", 200_000)
            file(sHome, "Documents/notes.md", 3_000)
            for (d in listOf("Desktop", "Pictures", "Library", "Movies")) Files.createDirectories(sHome.resolve(d))
            Files.createSymbolicLink(sHome.resolve("link-to-tmp"), tmp)
            Files.createSymbolicLink(sHome.resolve("Projects/latest"), java.nio.file.Path.of("src"))
            Files.createSymbolicLink(sHome.resolve("Projects/outside"), tmp)
            Files.createSymbolicLink(sHome.resolve("Documents/old-notes"), java.nio.file.Path.of("../../elsewhere"))
            file(book.settings.home, "Documents/report.xlsx", 100)
            file(book.settings.home, "Projects/src/Main.kt", 1_200)
            file(book.settings.home, "Videos/trip.mov", 8_000_000)

            eventually("peers") { studio.peers.value.isNotEmpty() && book.peers.value.isNotEmpty() }
            // A completed job for the history screen.
            val done = studio.send(listOf("Documents"), book.machineId).jobId
            eventually("offer 1") { book.jobs.value.any { it.id == done && it.incomingOffer } }
            book.decide(done, Decision.Accept)
            eventually("history") { studio.history.value.any { it.jobId == done && it.outcome == JobState.Completed } }

            // A waiting offer and a paused transfer for the jobs screen and the accept dialog.
            val offer = studio.send(listOf("Projects", "Documents/report.xlsx"), book.machineId).jobId
            eventually("offer 2") { book.jobs.value.any { it.id == offer && it.incomingOffer } }
            val big = book.send(listOf("Videos"), studio.machineId).jobId
            eventually("offer 3") { studio.jobs.value.any { it.id == big && it.incomingOffer } }
            studio.decide(big, Decision.AcceptCopy)
            eventually("progress") { (studio.jobs.value.firstOrNull { it.id == big }?.doneBytes ?: 0) > 0 }
            runCatching { studio.pause(big) }

            val model = withContext(Dispatchers.Swing) { AppModel(book, scope) }
            for (s in Screen.entries) {
                withContext(Dispatchers.Swing) { model.screen = s }
                shot("book-${s.name.lowercase()}") { App(model) }
            }
            val studioModel = withContext(Dispatchers.Swing) { AppModel(studio, scope) }
            withContext(Dispatchers.Swing) { studioModel.screen = Screen.Files }
            shot("studio-files") { App(studioModel) }
            withContext(Dispatchers.Swing) { studioModel.screen = Screen.Transfers }
            shot("studio-transfers") { App(studioModel) }
            val details = book.inspectOffer(offer)
            shot("accept-dialog", 640, 520) { AcceptContent(details, onDecide = {}) }
            shot("accept-all-items", 760, 640) { AllItemsContent(details) }
            val many = (1..40).map { app.dc8.maconecopy.JobIssue("Projects/build/out-$it.o", if (it % 4 == 0) app.dc8.maconecopy.IssueKind.Failed else app.dc8.maconecopy.IssueKind.Skipped,
                if (it % 4 == 0) "changed while being sent; Resume sends it again" else "symlink pointing outside Projects (→ /tmp/cache/$it)") }
            shot("issues", 720, 560) { IssuesContent(many) }
            val moved = mapOf("*" to "Clients/2026")
            val movedDetails = book.inspectOffer(offer, moved)
            shot("accept-dialog-moved", 640, 520) { AcceptContent(movedDetails, onDecide = {}, folders = moved) }
            val fresh = withContext(Dispatchers.Swing) { AppModel(Engine(AppPaths(tmp.resolve("new/data")), Settings(displayName = "New Mac")), scope) }
            shot("setup") { App(fresh) }

            val files = Files.list(out).use { it.toList() }
            assertTrue(files.size >= 9 && files.all { Files.size(it) > 5_000 }, "rendered ${files.map { it.fileName }}")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
