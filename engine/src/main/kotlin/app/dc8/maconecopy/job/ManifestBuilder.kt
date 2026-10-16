package app.dc8.maconecopy.job

import app.dc8.maconecopy.EngineException
import app.dc8.maconecopy.IssueKind
import app.dc8.maconecopy.JobIssue
import app.dc8.maconecopy.fs.HomeDir
import app.dc8.maconecopy.fs.ProtectedFolders
import app.dc8.maconecopy.relay.FileEntry
import app.dc8.maconecopy.relay.LinkEntry
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path

class ManifestContent(
    val roots: List<String>,
    val files: List<FileEntry>,
    val emptyDirs: List<String>,
    val links: List<LinkEntry>,
    /** What was left out, with the reason; travels with the offer so both sides can show it. */
    val skipped: List<JobIssue>,
) {
    val warnings: List<String> get() = skipped.map { "${it.path}: ${it.message}" }
}

/**
 * Walks the roots (relative to home) without following symlinks. A symlink is sent as a link if its
 * target stays inside the root it belongs to (absolute targets there are made relative); any other
 * symlink, special file or unreadable entry is left out and recorded.
 */
object ManifestBuilder {
    private val IGNORED = setOf(".DS_Store")

    fun build(home: HomeDir, roots: List<String>): ManifestContent {
        val normalized = roots.map { home.normalize(it) }.distinct().sorted()
        normalized.firstNotNullOfOrNull { ProtectedFolders.of(it) }?.let { throw EngineException(ProtectedFolders.message(it)) }
        // Drop roots that sit inside another root.
        val top = normalized.filter { r -> normalized.none { o -> o != r && r.startsWith("$o/") } }
        val out = Collector(home)
        val kept = mutableListOf<String>()
        for (root in top) {
            val path = home.resolve(root)
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: IOException) {
                throw EngineException("not found: $root")
            }
            when {
                attrs.isSymbolicLink -> out.skip(root, "is a symlink itself; send what it points to instead")
                attrs.isRegularFile -> { out.files += FileEntry(root, attrs.size(), attrs.lastModifiedTime().toMillis()); kept += root }
                attrs.isDirectory -> { out.walk(root, path); kept += root }
                else -> out.skip(root, "special file (socket, pipe or device)")
            }
        }
        if (kept.isEmpty()) throw EngineException("nothing to send" + out.skipped.firstOrNull()?.let { ": ${it.path} ${it.message}" }.orEmpty())
        return ManifestContent(kept, out.files.sortedBy { it.path }, out.emptyDirs.sorted(), out.links.sortedBy { it.path }, out.skipped)
    }

    /** The link target as a relative path if, seen from the link's folder, it stays inside [root]; else null. */
    fun relativeTarget(home: HomeDir, root: String, linkRel: String, target: Path): String? {
        val parent = linkRel.substringBeforeLast('/', "")
        val rel = if (target.isAbsolute) {
            val abs = target.normalize()
            val homeRoot = listOf(home.root, runCatching { home.root.toRealPath() }.getOrNull()).firstOrNull { it != null && abs.startsWith(it) } ?: return null
            homeRoot.resolve(parent).relativize(abs).toString().ifEmpty { "." }
        } else target.toString()
        return rel.takeIf { staysInside(root, parent, it) }
    }

    /** Lexically: does `parent/target` stay inside `root` (both relative to home)? */
    fun staysInside(root: String, parent: String, target: String): Boolean {
        if (target.isEmpty() || target.startsWith("/")) return false
        val resolved = (if (parent.isEmpty()) Path(target) else Path(parent).resolve(target)).normalize().toString()
        return resolved == root || resolved.startsWith("$root/")
    }

    private class Collector(val home: HomeDir) {
        val files = mutableListOf<FileEntry>()
        val emptyDirs = mutableListOf<String>()
        val links = mutableListOf<LinkEntry>()
        val skipped = mutableListOf<JobIssue>()

        fun skip(path: String, why: String) {
            skipped += JobIssue(path, IssueKind.Skipped, why)
        }

        fun walk(root: String, start: Path) {
            val counts = ArrayDeque<Int>()
            fun rel(p: Path) = home.relativize(p)
            fun counted() = counts.addLast(counts.removeLast() + 1)
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    counts.addLast(0); return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val r = rel(file)
                    when {
                        attrs.isSymbolicLink -> {
                            val target = runCatching { Files.readSymbolicLink(file) }.getOrNull()
                            val relTarget = target?.let { relativeTarget(home, root, r, it) }
                            if (relTarget != null) { links += LinkEntry(r, relTarget); counted() }
                            else skip(r, "symlink pointing outside $root (→ $target)")
                        }
                        attrs.isRegularFile && file.fileName.toString() !in IGNORED -> {
                            files += FileEntry(r, attrs.size(), attrs.lastModifiedTime().toMillis())
                            counted()
                        }
                        attrs.isRegularFile -> {}
                        else -> skip(r, "special file (socket, pipe or device)")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    skip(rel(file), "cannot be read: ${exc.message ?: exc.javaClass.simpleName}"); return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (counts.removeLast() == 0) emptyDirs += rel(dir)
                    if (counts.isNotEmpty()) counted()
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
