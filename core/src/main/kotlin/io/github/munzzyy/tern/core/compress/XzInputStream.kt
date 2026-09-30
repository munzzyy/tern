package io.github.munzzyy.tern.core.compress

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Reads xz data whose blocks are packed with LZMA2 alone, which is how xz packs them unless told
 * otherwise: one stream, or several one after the other. Every header, block, index and footer is
 * held to its CRC, and what each block holds to the check the stream names (CRC32, CRC64 or
 * SHA-256). The dictionary is nearly all the memory this takes, and one larger than
 * [maxDictionary] is refused. No more than [maxOutput] bytes come out.
 */
class XzInputStream(
    input: InputStream,
    private val maxOutput: Long = Long.MAX_VALUE,
    private val maxDictionary: Int = MAX_DICTIONARY,
) : InputStream() {
    private val raw = Counted(input)
    private var streams = 0
    private var inStream = false
    private var finished = false
    private var checkType = 0
    private var streamFlags = 0
    private val records = ArrayList<Pair<Long, Long>>()

    private var block: Block? = null
    private var produced = 0L
    private val one = ByteArray(1)

    private class Block(val headerSize: Int, val compressedSize: Long, val uncompressedSize: Long, val dataStart: Long, val data: Lzma2) {
        var produced = 0L
    }

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val current = block
            if (current == null) {
                if (!nextBlock()) break
                continue
            }
            val got = current.data.read(b, off + n, len - n)
            if (got < 0) {
                endBlock(current)
                block = null
                continue
            }
            check.update(b, off + n, got)
            current.produced += got
            if (current.uncompressedSize >= 0 && current.produced > current.uncompressedSize) throw damaged()
            n += got
        }
        if (n == 0) return -1
        produced += n
        if (produced > maxOutput) throw CompressedDataException("The xz data unpacks to more than $maxOutput bytes")
        return n
    }

    private fun nextBlock(): Boolean {
        while (true) {
            if (finished) return false
            if (!inStream) {
                if (!streamHeader()) {
                    finished = true
                    return false
                }
                inStream = true
            }
            val first = raw.byte()
            if (first == 0) {
                index()
                footer()
                inStream = false
                continue
            }
            blockHeader(first)
            return true
        }
    }

    /** The header of a stream; before a second one, the zero bytes that may pad the first. */
    private fun streamHeader(): Boolean {
        val header = ByteArray(STREAM_HEADER)
        if (streams == 0) {
            if (raw.upTo(header, 0, STREAM_HEADER) < STREAM_HEADER) throw CompressedDataException("This is not xz data")
        } else {
            while (true) {
                val n = raw.upTo(header, 0, 4)
                if (n == 0) return false
                if (n < 4) throw damaged()
                if (header[0].toInt() or header[1].toInt() or header[2].toInt() or header[3].toInt() == 0) continue
                raw.fully(header, 4, STREAM_HEADER - 4)
                break
            }
        }
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) throw CompressedDataException(if (streams == 0) "This is not xz data" else "The xz data is damaged")
        }
        if (crc32(header, 6, 2) != le32(header, 8)) throw damaged()
        if (header[6].toInt() != 0 || header[7].toInt() and 0xf0 != 0) throw CompressedDataException("This xz data uses flags that are not read")
        checkType = header[7].toInt() and 0x0f
        if (checkType !in CHECK_SIZES) throw CompressedDataException("This xz data names a check that is not read")
        streamFlags = header[7].toInt() and 0xff
        records.clear()
        streams++
        return true
    }

    private fun blockHeader(sizeByte: Int) {
        val size = (sizeByte + 1) * 4
        val header = ByteArray(size)
        header[0] = sizeByte.toByte()
        raw.fully(header, 1, size - 1)
        if (crc32(header, 0, size - 4) != le32(header, size - 4)) throw damaged()
        val flags = header[1].toInt() and 0xff
        if (flags and 0x3c != 0) throw CompressedDataException("This xz data uses flags that are not read")
        val reader = HeaderReader(header, 2, size - 4)
        val compressedSize = if (flags and 0x40 != 0) reader.number() else -1L
        val uncompressedSize = if (flags and 0x80 != 0) reader.number() else -1L
        if (compressedSize == 0L) throw damaged()
        if ((flags and 0x03) + 1 != 1) throw CompressedDataException("This xz data uses filters besides LZMA2, which are not read")
        if (reader.number() != LZMA2_FILTER) throw CompressedDataException("This xz data uses a filter other than LZMA2, which is not read")
        if (reader.number() != 1L) throw damaged()
        val properties = reader.byte()
        if (properties > 40) throw damaged()
        val dictionary = if (properties == 40) -1 else (2 or (properties and 1)) shl (properties / 2 + 11)
        if (dictionary < 0 || dictionary > maxDictionary) {
            throw CompressedDataException("This xz data needs a dictionary larger than the ${maxDictionary / (1024 * 1024)} MiB allowed")
        }
        reader.zeros()
        check.reset()
        block = Block(size, compressedSize, uncompressedSize, raw.count, Lzma2(raw, dictionary))
    }

    private fun endBlock(ended: Block) {
        val compressed = raw.count - ended.dataStart
        if (ended.compressedSize >= 0 && compressed != ended.compressedSize) throw damaged()
        if (ended.uncompressedSize >= 0 && ended.produced != ended.uncompressedSize) throw damaged()
        val padding = ((4 - (ended.headerSize + compressed) % 4) % 4).toInt()
        repeat(padding) { if (raw.byte() != 0) throw damaged() }
        val size = CHECK_SIZES.getValue(checkType)
        if (size > 0) {
            val stored = ByteArray(size)
            raw.fully(stored, 0, size)
            if (!stored.contentEquals(check.result())) throw CompressedDataException("A block of the xz data fails its check")
        }
        records += (ended.headerSize + compressed + size) to ended.produced
    }

    private var indexSize = 0L

    /** The index names every block by its sizes; they have to be the ones just read. Its indicator, a zero byte, is already read. */
    private fun index() {
        val start = raw.count - 1
        val crc = CRC32()
        crc.update(0)
        fun number(): Long {
            var value = 0L
            for (i in 0 until MAX_NUMBER_BYTES) {
                val b = raw.byte()
                crc.update(b)
                if (i > 0 && b == 0) throw damaged()
                value = value or ((b and 0x7f).toLong() shl (7 * i))
                if (b and 0x80 == 0) return value
            }
            throw damaged()
        }
        if (number() != records.size.toLong()) throw damaged()
        for ((unpadded, uncompressed) in records) {
            if (number() != unpadded || number() != uncompressed) throw damaged()
        }
        val padding = ((4 - (raw.count - start) % 4) % 4).toInt()
        repeat(padding) {
            val b = raw.byte()
            crc.update(b)
            if (b != 0) throw damaged()
        }
        val stored = ByteArray(4)
        raw.fully(stored, 0, 4)
        if (le32(stored, 0) != crc.value.toInt()) throw damaged()
        indexSize = raw.count - start
    }

    private fun footer() {
        val footer = ByteArray(STREAM_HEADER)
        raw.fully(footer, 0, STREAM_HEADER)
        if (crc32(footer, 4, 6) != le32(footer, 0)) throw damaged()
        val backward = (le32(footer, 4).toLong() and 0xffffffffL) + 1
        if (backward * 4 != indexSize) throw damaged()
        if (footer[8].toInt() != 0 || footer[9].toInt() and 0xff != streamFlags) throw damaged()
        if (footer[10] != 'Y'.code.toByte() || footer[11] != 'Z'.code.toByte()) throw damaged()
    }

    private val check = Check()

    /** The check of one block's data, of the kind its stream names. */
    private inner class Check {
        private val crc32 = CRC32()
        private var crc64 = -1L
        private var sha256: MessageDigest? = null

        fun reset() {
            crc32.reset()
            crc64 = -1L
            sha256 = if (checkType == CHECK_SHA256) MessageDigest.getInstance("SHA-256") else null
        }

        fun update(b: ByteArray, off: Int, len: Int) {
            when (checkType) {
                CHECK_CRC32 -> crc32.update(b, off, len)
                CHECK_CRC64 -> for (i in off until off + len) crc64 = CRC64_TABLE[((crc64 xor b[i].toLong()) and 0xff).toInt()] xor (crc64 ushr 8)
                CHECK_SHA256 -> sha256?.update(b, off, len)
            }
        }

        fun result(): ByteArray = when (checkType) {
            CHECK_CRC32 -> ByteArray(4) { i -> (crc32.value ushr (8 * i)).toByte() }
            CHECK_CRC64 -> crc64.inv().let { value -> ByteArray(8) { i -> (value ushr (8 * i)).toByte() } }
            CHECK_SHA256 -> sha256?.digest() ?: ByteArray(0)
            else -> ByteArray(0)
        }
    }

    /** The fields of a block header, read without leaving it. */
    private inner class HeaderReader(private val header: ByteArray, private var at: Int, private val end: Int) {
        fun byte(): Int {
            if (at >= end) throw damaged()
            return header[at++].toInt() and 0xff
        }

        fun number(): Long {
            var value = 0L
            for (i in 0 until MAX_NUMBER_BYTES) {
                val b = byte()
                if (i > 0 && b == 0) throw damaged()
                value = value or ((b and 0x7f).toLong() shl (7 * i))
                if (b and 0x80 == 0) return value
            }
            throw damaged()
        }

        fun zeros() {
            while (at < end) if (header[at++].toInt() != 0) throw damaged()
        }
    }

    private fun damaged() = CompressedDataException("The xz data is damaged")

    companion object {
        /** What xz -9 uses. */
        const val MAX_DICTIONARY = 64 * 1024 * 1024

        private const val STREAM_HEADER = 12
        private const val MAX_NUMBER_BYTES = 9
        private const val LZMA2_FILTER = 0x21L
        private const val CHECK_CRC32 = 1
        private const val CHECK_CRC64 = 4
        private const val CHECK_SHA256 = 10
        private val CHECK_SIZES = mapOf(0 to 0, CHECK_CRC32 to 4, CHECK_CRC64 to 8, CHECK_SHA256 to 32)
        private val MAGIC = byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)

        private val CRC64_TABLE = LongArray(256) { i ->
            var c = i.toLong()
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor -0x3693a86a2878f0beL else c ushr 1 }
            c
        }

        private fun crc32(b: ByteArray, off: Int, len: Int): Int = CRC32().apply { update(b, off, len) }.value.toInt()

        private fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
    }
}

/** The input, counted, so that a block's size can be held to what its header and the index say. */
internal class Counted(private val input: InputStream) {
    var count = 0L
        private set

    fun byte(): Int {
        val b = input.read()
        if (b < 0) throw CompressedDataException("The xz data ends early")
        count++
        return b
    }

    fun fully(b: ByteArray, off: Int, len: Int) {
        if (upTo(b, off, len) < len) throw CompressedDataException("The xz data ends early")
    }

    /** Reads until [len] bytes are there or the input ends, and says how many came. */
    fun upTo(b: ByteArray, off: Int, len: Int): Int {
        var done = 0
        while (done < len) {
            val n = input.read(b, off + done, len - done)
            if (n < 0) break
            done += n
        }
        count += done
        return done
    }
}
