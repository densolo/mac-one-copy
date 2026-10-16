package app.dc8.maconecopy.chunk

import app.dc8.maconecopy.fs.Hashing
import app.dc8.maconecopy.fs.LimitedInputStream
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** One DATA record of a chunk; `end` means the file's FILE_END record follows it. */
data class Segment(val file: Int, val offset: Long, val length: Long, val end: Boolean)

data class ChunkPlan(val seq: Int, val segments: List<Segment>) {
    val dataBytes get() = segments.sumOf { it.length }
    val fileEnds get() = segments.count { it.end }
}

/** Something to lay out: a byte range of a file. Atomic ranges (resent segments) are never split. */
data class LayoutItem(val file: Int, val offset: Long, val length: Long, val end: Boolean, val atomic: Boolean = false)

object ChunkLayout {
    fun wholeFiles(files: List<Pair<Int, Long>>) = files.map { (i, size) -> LayoutItem(i, 0, size, end = true) }

    /**
     * Packs items, in the given order, into chunks of at most [chunkSize] data bytes. Small files share
     * chunks, big files span several, atomic items move to the next chunk instead of being split.
     * Same input → same layout.
     */
    fun plan(items: List<LayoutItem>, chunkSize: Long, startSeq: Int = 0): List<ChunkPlan> {
        require(chunkSize > 0)
        val out = mutableListOf<ChunkPlan>()
        var cur = mutableListOf<Segment>()
        var used = 0L
        fun flush() {
            if (cur.isEmpty()) return
            out += ChunkPlan(startSeq + out.size, cur)
            cur = mutableListOf(); used = 0
        }
        for (item in items) {
            if (item.length == 0L) {
                cur += Segment(item.file, item.offset, 0, item.end); continue
            }
            if (item.atomic) {
                require(item.length <= chunkSize)
                if (used + item.length > chunkSize) flush()
                cur += Segment(item.file, item.offset, item.length, item.end); used += item.length
                continue
            }
            var off = 0L
            while (off < item.length) {
                if (used == chunkSize) flush()
                val n = minOf(item.length - off, chunkSize - used)
                cur += Segment(item.file, item.offset + off, n, item.end && off + n == item.length)
                used += n; off += n
            }
        }
        flush()
        return out
    }
}

/** Hash of one DATA record's bytes at `[offset, offset + length)` of a file. */
@Serializable
data class SegHash(val offset: Long, val length: Long, val sha256: String)

/**
 * Binary chunk: header (magic, version, jobId, seq, gen, recordCount), records, then a SHA-256 trailer
 * over everything before it. Only a matching trailer proves the chunk is complete.
 *
 * - DATA: fileIndex, offset, length, bytes
 * - FILE_END: fileIndex, flags, segment count, segments (offset, length, sha256), file hash = sha256 over the
 *   segment hashes. Flag [SOURCE_CHANGED] means the sender gave up on the file: it changed while being sent.
 */
object ChunkCodec {
    const val MAGIC = 0x4D4F4331 // "MOC1"
    const val VERSION = 1
    const val SOURCE_CHANGED = 1
    private const val DATA = 1
    private const val FILE_END = 2

    data class Header(val jobId: UUID, val seq: Int, val gen: Int, val recordCount: Int)

    interface Visitor {
        /** Return false to stop reading (wrong job or seq). */
        fun header(header: Header): Boolean = true

        /** May read the data from [input]; whatever is left is skipped. */
        fun data(file: Int, offset: Long, length: Int, input: InputStream)
        fun fileEnd(file: Int, flags: Int, segments: List<SegHash>)
    }

    fun fileHash(segments: List<SegHash>): ByteArray {
        val md = Hashing.sha256()
        segments.forEach { md.update(Hashing.unhex(it.sha256)) }
        return md.digest()
    }

    class Writer(private val raw: OutputStream, private val header: Header) {
        private val digest = Hashing.sha256()
        private val out = DataOutputStream(DigestOutputStream(raw, digest))
        private var records = 0

        init {
            out.writeInt(MAGIC); out.writeByte(VERSION)
            out.writeLong(header.jobId.mostSignificantBits); out.writeLong(header.jobId.leastSignificantBits)
            out.writeInt(header.seq); out.writeInt(header.gen); out.writeInt(header.recordCount)
        }

        /** [fill] must write exactly [length] bytes. */
        fun data(file: Int, offset: Long, length: Int, fill: (OutputStream) -> Unit) {
            out.writeByte(DATA); out.writeInt(file); out.writeLong(offset); out.writeInt(length)
            val before = out.size()
            fill(out)
            check(out.size() - before == length) { "data record for file $file wrote ${out.size() - before} of $length bytes" }
            records++
        }

        fun fileEnd(file: Int, flags: Int, segments: List<SegHash>) {
            out.writeByte(FILE_END); out.writeInt(file); out.writeByte(flags); out.writeInt(segments.size)
            for (s in segments) {
                out.writeLong(s.offset); out.writeLong(s.length); out.write(Hashing.unhex(s.sha256))
            }
            out.write(fileHash(segments))
            records++
        }

        fun finish() {
            check(records == header.recordCount) { "wrote $records records, header says ${header.recordCount}" }
            out.flush()
            raw.write(digest.digest())
            raw.flush()
        }
    }

    /**
     * Reads a chunk in one pass, handing records to [visitor] as they stream by, and checks the trailer
     * at the end. Returns the header only if the chunk is complete and intact; otherwise null, and
     * whatever the visitor did with the records must be discarded.
     */
    fun read(path: Path, visitor: Visitor): Header? = FileChannel.open(path, READ).use { ch ->
        val size = ch.size()
        val digest = Hashing.sha256()
        val raw = BufferedInputStream(Channels.newInputStream(ch), 1 shl 16)
        val input = DataInputStream(DigestInputStream(raw, digest))
        try {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return null
            val header = Header(UUID(input.readLong(), input.readLong()), input.readInt(), input.readInt(), input.readInt())
            if (header.recordCount < 0 || !visitor.header(header)) return null
            repeat(header.recordCount) {
                when (input.readUnsignedByte()) {
                    DATA -> {
                        val file = input.readInt(); val offset = input.readLong(); val length = input.readInt()
                        if (length < 0 || length > size) return null
                        val limited = LimitedInputStream(input, length.toLong())
                        visitor.data(file, offset, length, limited)
                        limited.drain()
                    }
                    FILE_END -> {
                        val file = input.readInt(); val flags = input.readUnsignedByte(); val count = input.readInt()
                        if (count < 0 || count.toLong() * 48 > size) return null
                        val segs = List(count) {
                            val off = input.readLong(); val len = input.readLong()
                            SegHash(off, len, Hashing.hex(ByteArray(32).also { b -> input.readFully(b) }))
                        }
                        val hash = ByteArray(32).also { input.readFully(it) }
                        if (!MessageDigest.isEqual(hash, fileHash(segs))) return null
                        visitor.fileEnd(file, flags, segs)
                    }
                    else -> return null
                }
            }
            val expected = digest.digest()
            val trailer = ByteArray(32).also { DataInputStream(raw).readFully(it) }
            if (!MessageDigest.isEqual(expected, trailer) || raw.read() != -1) return null
            header
        } catch (_: EOFException) {
            null
        }
    }
}

/** Received segments of one file: sorted, non-overlapping. */
@Serializable
class SegmentMap(private val segs: MutableList<SegHash> = mutableListOf()) {
    val covered: Long get() = segs.sumOf { it.length }
    val all: List<SegHash> get() = segs

    fun get(offset: Long, length: Long): SegHash? = segs.firstOrNull { it.offset == offset && it.length == length }

    /** Forgets segments overlapping `[start, end)`, because those bytes are about to be overwritten. */
    fun removeOverlapping(start: Long, end: Long): Boolean = segs.removeAll { it.offset < end && start < it.offset + it.length }

    fun put(s: SegHash) {
        removeOverlapping(s.offset, s.offset + s.length)
        val i = segs.indexOfFirst { it.offset > s.offset }.let { if (it < 0) segs.size else it }
        segs.add(i, s)
    }

    fun remove(s: SegHash) = segs.removeAll { it.offset == s.offset && it.length == s.length }
}
