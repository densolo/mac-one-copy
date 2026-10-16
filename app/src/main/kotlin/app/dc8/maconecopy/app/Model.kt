package app.dc8.maconecopy.app

import app.dc8.maconecopy.FileStatus
import app.dc8.maconecopy.OfferDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object Format {
    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    /** `1 file`, `3 files`. */
    fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

    /** A number without a trailing `.0`, e.g. MB values in settings. */
    fun plain(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')

    fun rate(bytesPerSecond: Double) = if (bytesPerSecond < 1) "–" else "${bytes(bytesPerSecond.toLong())}/s"

    /** `42 s`, `3 min 5 s`, `2 h 10 min`. */
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min" + (seconds % 60).let { if (it > 0) " $it s" else "" }
        else -> "${seconds / 3600} h" + (seconds % 3600 / 60).let { if (it > 0) " $it min" else "" }
    }

    fun ago(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - timestamp) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} days ago"
        }
    }
}

/** A file or folder of an offer, with new/changed/same counts rolled up from its files. */
data class OfferNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val status: FileStatus?,
    val counts: Map<FileStatus, Int>,
    val children: List<OfferNode>,
) {
    val fileCount get() = counts.values.sum()

    companion object {
        /** Builds the tree of an offer: one node per root, folders sorted before files. */
        fun tree(details: OfferDetails): List<OfferNode> {
            class Dir(val name: String, val path: String) {
                val dirs = linkedMapOf<String, Dir>()
                val files = mutableListOf<OfferNode>()
            }
            val top = Dir("", "")
            fun dir(path: String): Dir {
                var d = top
                var prefix = ""
                for (part in path.split('/')) {
                    prefix = if (prefix.isEmpty()) part else "$prefix/$part"
                    d = d.dirs.getOrPut(part) { Dir(part, prefix) }
                }
                return d
            }
            for (f in details.files) {
                val parent = f.path.substringBeforeLast('/', "")
                val target = if (parent.isEmpty()) top else dir(parent)
                target.files += OfferNode(f.path.substringAfterLast('/'), f.path, false, f.size, f.status, mapOf(f.status to 1), emptyList())
            }
            details.emptyDirs.forEach { dir(it) }
            fun build(d: Dir): OfferNode {
                val children = d.dirs.values.sortedBy { it.name.lowercase() }.map { build(it) } + d.files.sortedBy { it.name.lowercase() }
                val counts = FileStatus.entries.associateWith { s -> children.sumOf { it.counts[s] ?: 0 } }.filterValues { it > 0 }
                return OfferNode(d.name, d.path, true, children.sumOf { it.size }, null, counts, children)
            }
            val root = build(top)
            // Show each root at the top, collapsing the parent folders the roots live in (e.g. "Documents/").
            return details.job.roots.mapNotNull { r -> find(root, r) }
        }

        private fun find(node: OfferNode, path: String): OfferNode? {
            if (node.path == path) return node
            return node.children.firstNotNullOfOrNull { c -> if (path == c.path || path.startsWith("${c.path}/")) find(c, path) else null }
        }
    }
}

/**
 * Computes folder sizes in the background with bounded parallelism and caches them.
 * Symlinks are not followed. [invalidate] drops a cached size, e.g. after a refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderSizes(private val scope: CoroutineScope, private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)) {
    private val _sizes = MutableStateFlow<Map<Path, Long>>(emptyMap())
    val sizes: StateFlow<Map<Path, Long>> = _sizes
    /** Folder → generation of its latest request; a result from an older request is dropped. */
    private val requested = HashMap<Path, Int>()
    private var generation = 0

    @Synchronized
    fun request(dir: Path) {
        if (dir in requested) return
        val gen = ++generation
        requested[dir] = gen
        scope.launch(dispatcher) {
            val size = sizeOf(dir)
            synchronized(this@FolderSizes) { if (requested[dir] == gen) _sizes.update { it + (dir to size) } }
        }
    }

    @Synchronized
    fun invalidate(dir: Path) {
        requested.remove(dir)
        _sizes.update { m -> m.filterKeys { it != dir } } // not `m - dir`: a Path is an Iterable of its parts
    }

    companion object {
        fun sizeOf(dir: Path): Long {
            var total = 0L
            runCatching {
                Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
                })
            }
            return total
        }
    }
}
