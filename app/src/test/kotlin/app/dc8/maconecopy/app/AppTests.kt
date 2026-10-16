package app.dc8.maconecopy.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Direction
import app.dc8.maconecopy.FileStatus
import app.dc8.maconecopy.IssueKind
import app.dc8.maconecopy.JobIssue
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.JobView
import app.dc8.maconecopy.OfferDetails
import app.dc8.maconecopy.OfferFile
import app.dc8.maconecopy.OfferTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun job(id: String = "j1", state: JobState = JobState.Offered, direction: Direction = Direction.Received, roots: List<String> = listOf("Projects", "Documents/report.xlsx"), total: Long = 1000, done: Long = 0) =
    JobView(id, direction, "peer", "Studio", roots, state, null, null, null, 4, total, 0, done, 0.0, null, null, 0)

fun offer(free: Long = 10_000) = OfferDetails(
    job(),
    listOf(
        OfferFile("Documents/report.xlsx", 300, 1, FileStatus.Changed),
        OfferFile("Projects/a.txt", 100, 1, FileStatus.Same),
        OfferFile("Projects/src/b.kt", 200, 1, FileStatus.New),
        OfferFile("Projects/src/c.kt", 400, 1, FileStatus.New),
    ),
    listOf("Projects/empty"),
    1000,
    free,
    "/Users/me",
    listOf(
        OfferTarget("Projects", true, "/Users/me/Projects", "/Users/me/Projects (copy)", true),
        OfferTarget("Documents/report.xlsx", false, "/Users/me/Documents/report.xlsx", "/Users/me/Documents/report (copy).xlsx", false),
    ),
)

class FormatTest {
    @Test
    fun formatting() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.5 KB", Format.bytes(1536))
        assertEquals("200 MB", Format.bytes(200L * 1024 * 1024))
        assertEquals("–", Format.rate(0.0))
        assertEquals("42 s", Format.duration(42))
        assertEquals("3 min 5 s", Format.duration(185))
        assertEquals("2 h 10 min", Format.duration(7800))
        assertEquals("1 file", Format.count(1, "file"))
        assertEquals("3 files", Format.count(3, "file"))
        assertEquals("0.25", Format.plain(0.25))
        assertEquals("200", Format.plain(200.0))
        assertEquals("just now", Format.ago(1000, now = 30_000))
        assertEquals("5 min ago", Format.ago(0, now = 300_000))
        assertEquals("~/work/gova", Format.home("/Users/me/work/gova", "/Users/me"))
        assertEquals("/Volumes/x", Format.home("/Volumes/x", "/Users/me"))
        assertEquals("", relativeToHome("/Users/me", "/Users/me"))
        assertEquals("work/gova", relativeToHome("/Users/me/work/gova", "/Users/me"))
        assertEquals(null, relativeToHome("/Users/meX/a", "/Users/me"))
    }

    @Test
    fun `title summary`() {
        assertEquals("", AppModel.summary(emptyList()))
        assertEquals("1 offer", AppModel.summary(listOf(job())))
        assertEquals("2 jobs · 34%", AppModel.summary(listOf(job("a", JobState.Transferring, total = 50, done = 17), job("b", JobState.Paused, total = 50, done = 17))))
    }
}

class OfferTreeTest {
    @Test
    fun `tree rolls up status counts and sorts folders first`() {
        val tree = OfferNode.tree(offer())
        assertEquals(listOf("Projects", "Documents/report.xlsx"), tree.map { it.name }) // roots carry their full path
        val projects = tree[0]
        assertEquals(listOf("empty", "src", "a.txt"), projects.children.map { it.name })
        assertEquals(mapOf(FileStatus.Same to 1, FileStatus.New to 2), projects.counts)
        assertEquals(700, projects.size)
        assertEquals(3, projects.fileCount)
        assertEquals(FileStatus.Changed, tree[1].status)
        assertTrue(projects.children[0].children.isEmpty() && projects.children[0].isDir)
    }

    @Test
    fun `links show up as link nodes and do not count as files`() {
        val tree = OfferNode.tree(offer().copy(links = mapOf("Projects/latest" to "src")))
        val link = tree[0].children.first { it.isLink }
        assertEquals("latest → src", link.name)
        assertEquals(3, tree[0].fileCount)
    }
}

class FolderSizesTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `sizes are computed in the background, cached and invalidated`() = runBlocking {
        val dir = Files.createDirectories(tmp.resolve("d/sub"))
        Files.write(dir.resolve("x"), ByteArray(1000))
        Files.write(tmp.resolve("d/y"), ByteArray(24))
        Files.createSymbolicLink(tmp.resolve("d/link"), tmp.resolve("d/y"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sizes = FolderSizes(scope)
        sizes.request(tmp.resolve("d"))
        while (tmp.resolve("d") !in sizes.sizes.value) delay(10)
        assertEquals(1024, sizes.sizes.value[tmp.resolve("d")])
        Files.write(dir.resolve("z"), ByteArray(1))
        sizes.request(tmp.resolve("d"))
        delay(100)
        assertEquals(1024, sizes.sizes.value[tmp.resolve("d")], "cached")
        sizes.invalidate(tmp.resolve("d"))
        sizes.request(tmp.resolve("d"))
        while (tmp.resolve("d") !in sizes.sizes.value) delay(10)
        assertEquals(1025, sizes.sizes.value[tmp.resolve("d")])
        scope.cancel()
    }
}

@OptIn(ExperimentalTestApi::class)
class AcceptDialogTest {
    @Test
    fun `shows the offer and reports the decision`() = runComposeUiTest {
        var decided: Decision? = null
        setContent { AppTheme { AcceptContent(offer(), onDecide = { decided = it }) } }
        onNodeWithText("4 files · 1000 B").assertExists()
        onAllNodesWithText("new 2").assertCountEquals(3) // summary, Projects, src
        onNodeWithText("src").performClick() // expand
        onNodeWithText("b.kt").assertExists()
        onAllNodesWithTag("target", useUnmergedTree = true).assertCountEquals(2)
        onNodeWithText("→ /Users/me/Projects · exists: Accept merges into it").assertExists() // not under this test's home, so in full
        onNodeWithText("as copy → /Users/me/Projects (copy)").assertExists()
        onNodeWithText("→ /Users/me/Documents/report.xlsx · new here").assertExists()
        onNodeWithText("Accept as copy").performClick()
        assertEquals(Decision.AcceptCopy, decided)
        onNodeWithText("Accept").performClick()
        assertEquals(Decision.Accept, decided)
    }

    @Test
    fun `changing a folder asks for a preview with it, and folders outside home are refused`() = runComposeUiTest {
        var asked: Map<String, String>? = null
        var picked = "/Users/me/Clients"
        setContent { AppTheme { AcceptContent(offer(), {}, onFolders = { asked = it }, pickFolder = { picked }) } }
        onAllNodesWithText("Change…")[0].performClick() // first root in the tree: Projects
        assertEquals(mapOf("Projects" to "Clients"), asked)
        onNodeWithText("Put everything in…").performClick()
        assertEquals(mapOf("Projects" to "Clients", "Documents/report.xlsx" to "Clients"), asked)
        picked = "/Volumes/USB"
        asked = null
        onAllNodesWithText("Change…")[0].performClick()
        assertEquals(null, asked)
        onNodeWithTag("error").assertTextContains("inside your home folder", substring = true)
    }

    @Test
    fun `a moved root can be reset`() = runComposeUiTest {
        var asked: Map<String, String>? = null
        val moved = offer().let { o -> o.copy(targets = o.targets.map { if (it.root == "Projects") it.copy(moved = true, folder = "Clients") else it }) }
        setContent { AppTheme { AcceptContent(moved, {}, folders = mapOf("Projects" to "Clients"), onFolders = { asked = it }) } }
        onNodeWithText("Reset").performClick()
        assertEquals(emptyMap(), asked)
    }

    @Test
    fun `warns when there is not enough space`() = runComposeUiTest {
        setContent { AppTheme { AcceptContent(offer(free = 10), onDecide = {}) } }
        onNodeWithTag("free").assertTextContains("not enough space", substring = true)
    }
}

@OptIn(ExperimentalTestApi::class)
class IssueListTest {
    @Test
    fun `expands to each path and reason`() = runComposeUiTest {
        val issues = listOf(
            JobIssue("Proj/escape", IssueKind.Skipped, "symlink pointing outside Proj (→ ../../etc)"),
            JobIssue("Proj/big.bin", IssueKind.Failed, "changed while being sent; Resume sends it again"),
        )
        setContent { AppTheme { IssueList(issues) } }
        onNodeWithText("⚠ 2 not transferred ▸").assertExists()
        onNodeWithText("Proj/escape").assertDoesNotExist()
        onNodeWithTag("issues").performClick()
        onNodeWithText("Proj/escape").assertExists()
        onNodeWithText("changed while being sent; Resume sends it again").assertExists()
    }
}

class FilesListTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `protected folders are marked and symlinks are not sendable`() {
        for (d in listOf("Desktop", "Documents", "Pictures", "Library", "Projects")) Files.createDirectories(tmp.resolve(d))
        Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("Projects"))
        val items = listFolder(tmp, "", showHidden = false).associateBy { it.name }
        assertEquals(setOf("Desktop", "Pictures", "Library"), items.values.filter { it.isProtected }.map { it.name }.toSet())
        assertEquals(setOf("Documents", "Projects"), items.values.filter { it.sendable }.map { it.name }.toSet())
    }
}

class HelpTest {
    @Test
    fun `help lists what can be transferred and the protected folders`() {
        val titles = Help.sections.map { it.title }
        assertTrue("What can be transferred" in titles && "Protected folders" in titles)
        val protectedText = Help.sections.first { it.title == "Protected folders" }.body.joinToString(" ")
        for (n in listOf("Desktop", "Downloads", "Movies", "Music", "Pictures", "Library", "iCloud")) assertTrue(n in protectedText, n)
    }
}

