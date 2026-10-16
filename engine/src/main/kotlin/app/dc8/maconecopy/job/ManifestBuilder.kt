package app.dc8.maconecopy.job

import app.dc8.maconecopy.EngineException
import app.dc8.maconecopy.fs.HomeDir
import app.dc8.maconecopy.fs.ProtectedFolders
import app.dc8.maconecopy.relay.FileEntry
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class ManifestContent(val roots: List<String>, val files: List<FileEntry>, val emptyDirs: List<String>, val warnings: List<String>)

/** Walks the roots (relative to home) without following symlinks; symlinks are skipped with a warning. */
object ManifestBuilder {
    private val IGNORED = setOf(".DS_Store")

    fun build(home: HomeDir, roots: List<String>): ManifestContent {
        val warnings = mutableListOf<String>()
        val normalized = roots.map { home.normalize(it) }.distinct().sorted()
        normalized.firstNotNullOfOrNull { ProtectedFolders.of(it) }?.let { throw EngineException(ProtectedFolders.message(it)) }
        // Drop roots that sit inside another root.
        val top = normalized.filter { r -> normalized.none { o -> o != r && r.startsWith("$o/") } }
        val files = mutableListOf<FileEntry>()
        val emptyDirs = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (root in top) {
            val path = home.resolve(root)
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: IOException) {
                throw EngineException("not found: $root")
            }
            when {
                attrs.isSymbolicLink -> warnings += "skipped symlink $root"
                attrs.isRegularFile -> { files += FileEntry(root, attrs.size(), attrs.lastModifiedTime().toMillis()); kept += root }
                attrs.isDirectory -> { walk(home, path, files, emptyDirs, warnings); kept += root }
                else -> warnings += "skipped special file $root"
            }
        }
        if (kept.isEmpty()) throw EngineException("nothing to send")
        return ManifestContent(kept, files.sortedBy { it.path }, emptyDirs.sorted(), warnings)
    }

    private fun walk(home: HomeDir, start: Path, files: MutableList<FileEntry>, emptyDirs: MutableList<String>, warnings: MutableList<String>) {
        val counts = ArrayDeque<Int>()
        fun rel(p: Path) = home.relativize(p)
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                counts.addLast(0); return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                when {
                    attrs.isSymbolicLink -> warnings += "skipped symlink ${rel(file)}"
                    attrs.isRegularFile && file.fileName.toString() !in IGNORED -> {
                        files += FileEntry(rel(file), attrs.size(), attrs.lastModifiedTime().toMillis())
                        counts.addLast(counts.removeLast() + 1)
                    }
                    attrs.isRegularFile -> {}
                    else -> warnings += "skipped special file ${rel(file)}"
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                warnings += "cannot read ${rel(file)}: ${exc.message}"; return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (counts.removeLast() == 0) emptyDirs += rel(dir)
                if (counts.isNotEmpty()) counts.addLast(counts.removeLast() + 1)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
