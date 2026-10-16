package app.dc8.maconecopy.fs

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat

class PathSafetyException(message: String) : Exception(message)

/** Write `.tmp`, fsync, rename. The tag keeps temp names of different writers apart. */
object AtomicFiles {
    fun write(target: Path, bytes: ByteArray, tag: String = "w") = writeWith(target, tag) { it.write(bytes) }

    fun writeWith(target: Path, tag: String = "w", body: (OutputStream) -> Unit) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.$tag.tmp")
        try {
            FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE).use { ch ->
                val out = Channels.newOutputStream(ch).buffered(1 shl 16)
                body(out)
                out.flush()
                ch.force(true)
            }
            Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

/**
 * Top-level folders of a Mac home that macOS guards with privacy prompts (TCC): reading them needs
 * special access, so they are never sent into or out of. Library also holds iCloud Drive
 * (`Library/Mobile Documents`) and the OneDrive folder itself. Documents is allowed.
 */
object ProtectedFolders {
    val NAMES = listOf("Desktop", "Downloads", "Movies", "Music", "Pictures", "Library", ".Trash")
    private val absolute: List<Path> by lazy { NAMES.map { app.dc8.maconecopy.USER_HOME.resolve(it) } }

    /** The protected folder a relative path lies in (matched case-insensitively, like APFS), or null. */
    fun of(rel: String): String? = rel.trimStart('/').substringBefore('/').let { top -> NAMES.firstOrNull { it.equals(top, ignoreCase = true) } }

    /** True if an absolute, real path lies in one of the user's protected folders. */
    fun contains(real: Path): Boolean = absolute.any { real.startsWith(it) }

    fun message(name: String) = "$name is a macOS-protected folder and is not synced (only Documents is)"
}

/**
 * The root all transfer paths are relative to. Paths use '/' separators, may not be absolute,
 * may not contain `..`, and may not resolve outside the root through symlinks.
 */
class HomeDir(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()
    private val realRoot: Path = Files.createDirectories(this.root).toRealPath()

    fun normalize(rel: String): String {
        if (rel.startsWith("/") || rel.startsWith("~")) throw PathSafetyException("absolute path not allowed: $rel")
        val parts = rel.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) throw PathSafetyException("empty path")
        if (parts.any { it == ".." }) throw PathSafetyException("'..' not allowed: $rel")
        if (parts.any { '\u0000' in it }) throw PathSafetyException("invalid character in $rel")
        return parts.joinToString("/")
    }

    /**
     * Resolves [rel] under the root, checking that the nearest existing ancestor really lives inside it
     * and that neither the path nor where it really points is a protected macOS folder.
     */
    fun resolve(rel: String): Path {
        val n = normalize(rel)
        ProtectedFolders.of(n)?.let { throw PathSafetyException(ProtectedFolders.message(it)) }
        val path = root.resolve(n)
        var p: Path? = path.parent
        while (p != null && p != root && !Files.exists(p)) p = p.parent
        val real = (p ?: root).toRealPath()
        if (!real.startsWith(realRoot)) throw PathSafetyException("$rel resolves outside home")
        if (ProtectedFolders.contains(real)) throw PathSafetyException("$rel lies in a macOS-protected folder and is not synced")
        return path
    }

    /** Turns an absolute path inside the root into a relative one. */
    fun relativize(abs: Path): String {
        val n = abs.toAbsolutePath().normalize()
        if (!n.startsWith(root) || n == root) throw PathSafetyException("$abs is not inside $root")
        return root.relativize(n).joinToString("/")
    }
}

object CopyNames {
    /** `report.xlsx` → `report (copy).xlsx`, `report (copy 2).xlsx`; directories get the suffix at the end. */
    fun nth(name: String, isDir: Boolean, n: Int): String {
        val tag = if (n <= 1) "(copy)" else "(copy $n)"
        val dot = name.lastIndexOf('.')
        return if (isDir || dot <= 0) "$name $tag" else "${name.substring(0, dot)} $tag${name.substring(dot)}"
    }

    fun firstFree(name: String, isDir: Boolean, taken: (String) -> Boolean): String =
        generateSequence(1) { it + 1 }.map { nth(name, isDir, it) }.first { !taken(it) }
}

object Hashing {
    fun hex(b: ByteArray): String = HexFormat.of().formatHex(b)
    fun unhex(s: String): ByteArray = HexFormat.of().parseHex(s)
    fun sha256() = MessageDigest.getInstance("SHA-256")!!

    /** Hex sha256 of `[offset, offset + length)` of an open file; null if the file is shorter. */
    fun sha256(ch: FileChannel, offset: Long, length: Long): String? {
        val md = sha256()
        val buf = ByteBuffer.allocate(1 shl 20)
        var pos = offset
        while (pos < offset + length) {
            buf.clear().limit(minOf(buf.capacity().toLong(), offset + length - pos).toInt())
            val n = ch.read(buf, pos)
            if (n < 0) return null
            buf.flip(); md.update(buf); pos += n
        }
        return hex(md.digest())
    }
}

/** Reads exactly [limit] bytes of [input], never past them. */
internal class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    fun drain() {
        val buf = ByteArray(1 shl 16)
        while (left > 0) if (read(buf, 0, buf.size) < 0) break
    }
}

fun Path.deleteRecursively() {
    if (!Files.exists(this, NOFOLLOW_LINKS)) return
    if (Files.isDirectory(this, NOFOLLOW_LINKS)) Files.list(this).use { s -> s.toList() }.forEach { it.deleteRecursively() }
    Files.deleteIfExists(this)
}
