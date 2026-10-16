package app.dc8.maconecopy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.random.Random

/**
 * Simulates the OneDrive client: every machine has its own local copy of the relay folder ("view"),
 * and changes travel through a cloud copy with random delays. Deliveries are non-atomic and arrive in
 * any order; some arrive first as truncated or zero-filled placeholders; some leave conflict copies.
 */
class FakeOneDrive(
    private val views: List<Path>,
    private val rnd: Random,
    private val maxDelayMs: Long = 120,
    private val partialProbability: Double = 0.3,
    private val conflictProbability: Double = 0.05,
) {
    private data class Version(val ver: Long, val bytes: ByteArray?, val origin: Int)
    private data class Sig(val size: Long, val mtime: Long, val key: Any?)
    private data class Delivery(val view: Int, val rel: String, val version: Version, val due: Long, val full: Boolean)

    private val cloud = HashMap<String, Version>()
    private val synced = views.map { HashMap<String, Sig>() }
    private val seen = views.map { HashMap<String, Long>() }
    private val scheduled = views.map { HashMap<String, Long>() }
    private val queue = mutableListOf<Delivery>()
    private var counter = 0L

    /** Highest number of chunk files alive in the cloud at once. */
    @Volatile var maxChunks = 0
        private set

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            round()
            delay(5)
        }
    }

    @Synchronized
    private fun round() {
        views.indices.forEach { upload(it) }
        val now = System.currentTimeMillis()
        for ((rel, v) in cloud) for (i in views.indices) {
            if (v.origin == i || (seen[i][rel] ?: -1) >= v.ver || (scheduled[i][rel] ?: -1) >= v.ver) continue
            scheduled[i][rel] = v.ver
            val partial = v.bytes != null && v.bytes.isNotEmpty() && rnd.nextDouble() < partialProbability
            queue += Delivery(i, rel, v, now + rnd.nextLong(0, maxDelayMs + 1), full = !partial)
        }
        val due = queue.filter { it.due <= now }
        queue.removeAll(due)
        due.shuffled(rnd).forEach { deliver(it, now) }
        maxChunks = maxOf(maxChunks, cloud.count { (rel, v) -> v.bytes != null && CHUNK.containsMatchIn(rel) })
    }

    private fun upload(i: Int) {
        val root = views[i]
        val present = HashSet<String>()
        val files = try {
            Files.walk(root).use { s -> s.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList() }
        } catch (_: IOException) {
            return
        } catch (_: java.io.UncheckedIOException) {
            return
        }
        for (f in files) {
            val rel = root.relativize(f).joinToString("/")
            if (rel.endsWith(".tmp")) continue
            val sig = sig(f) ?: continue
            present += rel
            if (synced[i][rel] == sig) continue
            val bytes = try { Files.readAllBytes(f) } catch (_: IOException) { continue }
            val v = Version(++counter, bytes, i)
            cloud[rel] = v
            synced[i][rel] = sig
            seen[i][rel] = v.ver
        }
        for (rel in synced[i].keys - present) {
            synced[i].remove(rel)
            val v = Version(++counter, null, i)
            cloud[rel] = v
            seen[i][rel] = v.ver
        }
    }

    private fun deliver(d: Delivery, now: Long) {
        val current = cloud[d.rel]
        if (current == null || current.ver != d.version.ver) return // superseded; the newer version gets its own delivery
        val target = views[d.view].resolve(d.rel)
        try {
            if (d.version.bytes == null) {
                Files.deleteIfExists(target)
                synced[d.view].remove(d.rel)
                seen[d.view][d.rel] = d.version.ver
                return
            }
            Files.createDirectories(target.parent)
            val bytes = d.version.bytes
            if (!d.full) {
                // A placeholder or a half-downloaded file; the full content follows later.
                val stub = if (rnd.nextBoolean()) ByteArray(bytes.size) else bytes.copyOf(bytes.size / 2)
                Files.write(target, stub)
                synced[d.view][d.rel] = sig(target)!!
                queue += d.copy(due = now + rnd.nextLong(1, maxDelayMs + 1), full = true)
                return
            }
            Files.write(target, bytes)
            synced[d.view][d.rel] = sig(target)!!
            seen[d.view][d.rel] = d.version.ver
            if (rnd.nextDouble() < conflictProbability) {
                val name = target.fileName.toString()
                val dot = name.lastIndexOf('.')
                val conflict = target.resolveSibling(if (dot > 0) "${name.substring(0, dot)}-MacBook${name.substring(dot)}" else "$name-MacBook")
                Files.write(conflict, bytes)
                synced[d.view][views[d.view].relativize(conflict).joinToString("/")] = sig(conflict)!!
            }
        } catch (_: IOException) {
            scheduled[d.view].remove(d.rel) // retry on a later round
        }
    }

    private fun sig(p: Path): Sig? = try {
        val a = Files.readAttributes(p, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        Sig(a.size(), a.lastModifiedTime().toMillis(), a.fileKey())
    } catch (_: IOException) {
        null
    }

    companion object {
        private val CHUNK = Regex("(^|/)jobs/[^/]+/\\d{6}\\.chunk$")
    }
}
