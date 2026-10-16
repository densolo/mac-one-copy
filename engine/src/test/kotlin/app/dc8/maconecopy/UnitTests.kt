package app.dc8.maconecopy

import app.dc8.maconecopy.chunk.ChunkCodec
import app.dc8.maconecopy.chunk.ChunkLayout
import app.dc8.maconecopy.chunk.LayoutItem
import app.dc8.maconecopy.chunk.SegHash
import app.dc8.maconecopy.chunk.SegmentMap
import app.dc8.maconecopy.chunk.Segment
import app.dc8.maconecopy.fs.AtomicFiles
import app.dc8.maconecopy.fs.CopyNames
import app.dc8.maconecopy.fs.HomeDir
import app.dc8.maconecopy.fs.PathSafetyException
import app.dc8.maconecopy.job.ManifestBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkCodecTest {
    @TempDir lateinit var dir: Path

    private val segs = listOf(SegHash(0, 1000, "11".repeat(32)), SegHash(1000, 500, "22".repeat(32)))

    private fun writeSample(path: Path, data: ByteArray): ChunkCodec.Header {
        val header = ChunkCodec.Header(UUID.randomUUID(), 42, 3, 3)
        AtomicFiles.writeWith(path) { out ->
            val w = ChunkCodec.Writer(out, header)
            w.data(7, 1000, data.size) { it.write(data) }
            w.fileEnd(7, 0, segs)
            w.fileEnd(8, ChunkCodec.SOURCE_CHANGED, emptyList())
            w.finish()
        }
        return header
    }

    private fun readAll(path: Path, seen: MutableList<String> = mutableListOf()) = ChunkCodec.read(path, object : ChunkCodec.Visitor {
        override fun data(file: Int, offset: Long, length: Int, input: InputStream) {
            seen += "data $file $offset $length ${input.readAllBytes().size}"
        }
        override fun fileEnd(file: Int, flags: Int, segments: List<SegHash>) { seen += "end $file $flags ${segments.size}" }
    })

    @Test
    fun `round trip in one pass`() {
        val data = Random(1).nextBytes(50_000)
        val path = dir.resolve("000042.chunk")
        val header = writeSample(path, data)
        val seen = mutableListOf<String>()
        assertEquals(header, readAll(path, seen))
        assertEquals(listOf("data 7 1000 50000 50000", "end 7 0 2", "end 8 1 0"), seen)
        var got: ByteArray? = null
        var gotSegs: List<SegHash>? = null
        ChunkCodec.read(path, object : ChunkCodec.Visitor {
            override fun data(file: Int, offset: Long, length: Int, input: InputStream) { got = input.readAllBytes() }
            override fun fileEnd(file: Int, flags: Int, segments: List<SegHash>) { if (file == 7) gotSegs = segments }
        })
        assertContentEquals(data, got)
        assertEquals(segs, gotSegs)
    }

    @Test
    fun `corrupt, truncated, padded and placeholder chunks are rejected`() {
        val path = dir.resolve("c.chunk")
        writeSample(path, Random(2).nextBytes(10_000))
        val good = Files.readAllBytes(path)
        for (bad in listOf(
            good.copyOf().also { it[500] = (it[500] + 1).toByte() },
            good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
            good.copyOf(good.size - 1),
            good.copyOf(good.size / 2),
            good + byteArrayOf(0),
            ByteArray(good.size), // OneDrive placeholder: full size, no data yet
            ByteArray(3),
        )) {
            Files.write(path, bad)
            assertNull(readAll(path))
        }
        Files.write(path, good)
        assertNotNull(readAll(path))
    }

    @Test
    fun `writer rejects wrong record count`() {
        assertFailsWith<IllegalStateException> {
            AtomicFiles.writeWith(dir.resolve("x.chunk")) { out ->
                ChunkCodec.Writer(out, ChunkCodec.Header(UUID.randomUUID(), 0, 0, 2)).apply { fileEnd(0, 0, emptyList()) }.finish()
            }
        }
        assertTrue(Files.list(dir).use { it.toList() }.isEmpty(), "temp file cleaned up")
    }
}

class ChunkLayoutTest {
    private fun whole(vararg sizes: Long) = ChunkLayout.wholeFiles(sizes.mapIndexed { i, s -> i to s })

    @Test
    fun `small files share chunks and big files span several`() {
        val plans = ChunkLayout.plan(whole(10, 0, 25, 5), chunkSize = 20)
        assertEquals(
            listOf(
                listOf(Segment(0, 0, 10, true), Segment(1, 0, 0, true), Segment(2, 0, 10, false)),
                listOf(Segment(2, 10, 15, true), Segment(3, 0, 5, true)),
            ),
            plans.map { it.segments },
        )
        assertEquals(listOf(0, 1), plans.map { it.seq })
        assertEquals(40, plans.sumOf { it.dataBytes })
    }

    @Test
    fun `layout is deterministic and covers every byte once`() {
        val rnd = Random(3)
        val files = (0 until 200).map { it to rnd.nextLong(0, 5000) }
        val a = ChunkLayout.plan(ChunkLayout.wholeFiles(files), 4096, startSeq = 7)
        assertEquals(a, ChunkLayout.plan(ChunkLayout.wholeFiles(files), 4096, startSeq = 7))
        assertEquals(7, a.first().seq)
        assertTrue(a.all { it.dataBytes <= 4096 })
        for ((index, size) in files) {
            val segs = a.flatMap { it.segments }.filter { it.file == index }
            assertEquals(size, segs.sumOf { it.length })
            assertEquals(1, segs.count { it.end })
            assertTrue(segs.last().end)
        }
    }

    @Test
    fun `skip list removes files from the layout`() {
        val files = listOf(0 to 100L, 1 to 100L, 2 to 100L).filter { it.first != 1 }
        val plans = ChunkLayout.plan(ChunkLayout.wholeFiles(files), 150, startSeq = 1)
        assertEquals(listOf(listOf(Segment(0, 0, 100, true), Segment(2, 0, 50, false)), listOf(Segment(2, 50, 50, true))), plans.map { it.segments })
    }

    @Test
    fun `resent segments are never split`() {
        val items = listOf(LayoutItem(0, 300, 100, end = false, atomic = true), LayoutItem(1, 0, 80, end = false, atomic = true), LayoutItem(1, 80, 90, end = false, atomic = true))
        val plans = ChunkLayout.plan(items, 200)
        assertEquals(listOf(listOf(Segment(0, 300, 100, false), Segment(1, 0, 80, false)), listOf(Segment(1, 80, 90, false))), plans.map { it.segments })
    }
}

class OffsetWriteTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `segment map keeps sorted non-overlapping segments`() {
        val m = SegmentMap()
        m.put(SegHash(20, 10, "a"))
        m.put(SegHash(0, 10, "b"))
        assertEquals(20, m.covered)
        assertEquals(listOf(0L, 20L), m.all.map { it.offset })
        m.put(SegHash(5, 20, "c")) // overlaps both: they are about to be overwritten
        assertEquals(listOf(SegHash(5, 20, "c")), m.all)
        assertNotNull(m.get(5, 20))
        assertNull(m.get(5, 10))
        m.remove(SegHash(5, 20, "x"))
        assertEquals(0, m.covered)
    }

    @Test
    fun `pieces written at their offsets in any order give the original file`() {
        val data = Random(4).nextBytes(100_000)
        val pieces = (0 until data.size step 7_000).map { it to minOf(it + 7_000, data.size) }.shuffled(Random(5))
        val part = dir.resolve("f.part")
        val segs = SegmentMap()
        FileChannel.open(part, CREATE, WRITE).use { ch ->
            for ((s, e) in pieces) {
                ch.write(ByteBuffer.wrap(data, s, e - s), s.toLong())
                segs.put(SegHash(s.toLong(), (e - s).toLong(), ""))
            }
        }
        assertEquals(data.size.toLong(), segs.covered)
        assertContentEquals(data, Files.readAllBytes(part))
    }
}

class CopyNamesTest {
    @Test
    fun names() {
        assertEquals("report (copy).xlsx", CopyNames.nth("report.xlsx", false, 1))
        assertEquals("report (copy 2).xlsx", CopyNames.nth("report.xlsx", false, 2))
        assertEquals("Projects (copy)", CopyNames.nth("Projects", true, 1))
        assertEquals("my.app (copy 3)", CopyNames.nth("my.app", true, 3))
        assertEquals(".bashrc (copy)", CopyNames.nth(".bashrc", false, 1))
        assertEquals("Makefile (copy)", CopyNames.nth("Makefile", false, 1))
    }

    @Test
    fun `skips taken names`() {
        val taken = setOf("Projects (copy)", "Projects (copy 2)")
        assertEquals("Projects (copy 3)", CopyNames.firstFree("Projects", true) { it in taken })
        assertEquals("a (copy).txt", CopyNames.firstFree("a.txt", false) { false })
    }
}

class PathSafetyTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `rejects absolute, dot-dot and empty paths`() {
        val home = HomeDir(tmp.resolve("home"))
        for (bad in listOf("/etc/passwd", "~/x", "..", "a/../../b", "a/..", "", "./", "a\u0000b")) {
            assertFailsWith<PathSafetyException>(bad) { home.resolve(bad) }
        }
        assertEquals("a/b/c", home.normalize("a//b/./c/"))
        assertEquals(home.root.resolve("a/b"), home.resolve("a/b"))
    }

    @Test
    fun `rejects paths escaping through symlinks`() {
        val home = HomeDir(tmp.resolve("home"))
        val outside = Files.createDirectories(tmp.resolve("outside"))
        Files.createSymbolicLink(home.root.resolve("link"), outside)
        assertFailsWith<PathSafetyException> { home.resolve("link/file.txt") }
        assertFailsWith<PathSafetyException> { home.resolve("link/deeper/file.txt") }
        Files.createDirectories(home.root.resolve("real"))
        Files.createSymbolicLink(home.root.resolve("inside"), home.root.resolve("real"))
        home.resolve("inside/file.txt") // a link that stays inside home is fine
    }

    @Test
    fun `macOS-protected folders are refused, Documents is allowed`() {
        val home = HomeDir(tmp.resolve("home"))
        for (bad in listOf("Desktop/a.txt", "pictures/x.jpg", "Movies", "Music/x", "Downloads/y", "Library/Mobile Documents/com~apple~CloudDocs/z", ".Trash/q")) {
            val e = assertFailsWith<PathSafetyException>(bad) { home.resolve(bad) }
            assertTrue("protected" in e.message!!, e.message)
        }
        home.resolve("Documents/report.xlsx")
        home.resolve("Projects/Desktop/a.txt") // only top-level names are protected
        assertEquals("Pictures", app.dc8.maconecopy.fs.ProtectedFolders.of("PICTURES/a"))
        val e = assertFailsWith<EngineException> { ManifestBuilder.build(home, listOf("Desktop")) }
        assertTrue("Desktop is a macOS-protected folder" in e.message!!)
    }

    @Test
    fun `manifest skips symlinks and records empty dirs`() {
        val home = HomeDir(tmp.resolve("home"))
        val p = Files.createDirectories(home.root.resolve("P/sub"))
        Files.createDirectories(home.root.resolve("P/empty/nested"))
        Files.writeString(p.resolve("b.txt"), "b")
        Files.writeString(home.root.resolve("P/a.txt"), "a")
        Files.writeString(home.root.resolve("P/.DS_Store"), "x")
        Files.createSymbolicLink(home.root.resolve("P/link"), tmp)
        val m = ManifestBuilder.build(home, listOf("P", "P/sub"))
        assertEquals(listOf("P"), m.roots)
        assertEquals(listOf("P/a.txt", "P/sub/b.txt"), m.files.map { it.path })
        assertEquals(listOf("P/empty/nested"), m.emptyDirs)
        assertEquals(listOf("skipped symlink P/link"), m.warnings)
    }
}
