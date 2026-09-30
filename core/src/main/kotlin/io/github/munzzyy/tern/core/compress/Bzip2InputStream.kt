package io.github.munzzyy.tern.core.compress

import java.io.IOException
import java.io.InputStream

/** Compressed data that cannot be read: damaged, cut short, of a form not read here, or larger than allowed. */
class CompressedDataException(message: String) : IOException(message)

/**
 * Reads bzip2 data: one stream, or several one after the other as parallel compressors write them.
 * The memory it takes is what one block of the stream's own size needs, at most 3.6 MB. Every block
 * and every stream is held to its CRC, and no more than [maxOutput] bytes come out.
 */
class Bzip2InputStream(input: InputStream, private val maxOutput: Long = Long.MAX_VALUE) : InputStream() {
    private val bits = BitInput(input)
    private var tt = IntArray(0)
    private var blockSize = 0
    private var started = false
    private var finished = false
    private var combined = 0

    // The block being given out: the inverse transform walks tt from tPos, and runs of four are expanded.
    private var inBlock = false
    private var expectedCrc = 0
    private var crc = -1
    private var blockLength = 0
    private var tPos = 0
    private var left = 0
    private var repeat = 0
    private var last = -1
    private var run = 0
    private var produced = 0L
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            if (repeat > 0) {
                val count = minOf(repeat, len - n)
                for (i in 0 until count) {
                    b[off + n + i] = last.toByte()
                    crc = crcOf(crc, last)
                }
                repeat -= count
                n += count
                continue
            }
            if (left > 0) {
                if (tPos !in 0 until blockLength) throw damaged()
                tPos = tt[tPos]
                val ch = tPos and 0xff
                tPos = tPos ushr 8
                left--
                // After four equal bytes the next one says how many more of them follow.
                if (run == 4) {
                    repeat = ch
                    run = 0
                    continue
                }
                if (ch == last) run++ else {
                    last = ch
                    run = 1
                }
                b[off + n++] = ch.toByte()
                crc = crcOf(crc, ch)
                continue
            }
            if (inBlock) endBlock()
            if (!startBlock()) break
        }
        if (n == 0) return -1
        produced += n
        if (produced > maxOutput) throw CompressedDataException("The bzip2 data unpacks to more than $maxOutput bytes")
        return n
    }

    private fun startBlock(): Boolean {
        if (finished) return false
        if (!started) {
            if (!streamHeader(first = true)) return false
            started = true
        }
        while (true) {
            val magic = (bits.bits(24).toLong() shl 24) or bits.bits(24).toLong()
            when (magic) {
                BLOCK_MAGIC -> {
                    expectedCrc = bits.bits(32)
                    decodeBlock()
                    inBlock = true
                    return true
                }
                END_MAGIC -> {
                    if (bits.bits(32) != combined) throw CompressedDataException("The bzip2 data fails its CRC check")
                    bits.align()
                    if (!streamHeader(first = false)) {
                        finished = true
                        return false
                    }
                }
                else -> throw damaged()
            }
        }
    }

    /** Reads "BZh" and the block size. After the first stream, anything else ends the data, as bzip2 itself takes it. */
    private fun streamHeader(first: Boolean): Boolean {
        val header = IntArray(4) { bits.byteOrEnd() }
        val valid = header[0] == 'B'.code && header[1] == 'Z'.code && header[2] == 'h'.code && header[3] in '1'.code..'9'.code
        if (!valid) {
            if (first) throw CompressedDataException("This is not bzip2 data")
            return false
        }
        blockSize = (header[3] - '0'.code) * 100_000
        if (tt.size < blockSize) tt = IntArray(blockSize)
        combined = 0
        return true
    }

    private fun decodeBlock() {
        if (bits.bit() == 1) throw CompressedDataException("This bzip2 data is in a form older than bzip2 0.9.5, which is not read")
        val origPtr = bits.bits(24)

        val seqToUnseq = IntArray(256)
        var inUse = 0
        val groups = bits.bits(16)
        for (i in 0 until 16) {
            if (groups and (0x8000 ushr i) == 0) continue
            val used = bits.bits(16)
            for (j in 0 until 16) if (used and (0x8000 ushr j) != 0) seqToUnseq[inUse++] = i * 16 + j
        }
        if (inUse == 0) throw damaged()
        val alphaSize = inUse + 2

        val tables = bits.bits(3)
        if (tables < 2 || tables > MAX_TABLES) throw damaged()
        val selectorCount = bits.bits(15)
        if (selectorCount < 1) throw damaged()
        // bzip2 1.0.8 reads selectors beyond the most a block can use and does not keep them; so does this.
        val kept = minOf(selectorCount, MAX_SELECTORS)
        val order = IntArray(tables) { it }
        val selectors = IntArray(kept)
        for (i in 0 until selectorCount) {
            var j = 0
            while (bits.bit() == 1) {
                j++
                if (j >= tables) throw damaged()
            }
            if (i >= kept) continue
            val table = order[j]
            System.arraycopy(order, 0, order, 1, j)
            order[0] = table
            selectors[i] = table
        }

        val decoders = Array(tables) {
            val lengths = IntArray(alphaSize)
            var length = bits.bits(5)
            for (i in 0 until alphaSize) {
                while (true) {
                    if (length < 1 || length > MAX_CODE_LENGTH) throw damaged()
                    if (bits.bit() == 0) break
                    if (bits.bit() == 0) length++ else length--
                }
                lengths[i] = length
            }
            Huffman(lengths)
        }

        val counts = IntArray(256)
        val mtf = IntArray(256) { it }
        val endOfBlock = inUse + 1
        var length = 0
        var group = -1
        var groupLeft = 0
        var decoder = decoders[0]
        fun next(): Int {
            if (groupLeft == 0) {
                group++
                if (group >= kept) throw damaged()
                groupLeft = GROUP_SIZE
                decoder = decoders[selectors[group]]
            }
            groupLeft--
            return decoder.decode(bits)
        }

        var symbol = next()
        while (symbol != endOfBlock) {
            if (symbol == RUN_A || symbol == RUN_B) {
                var count = -1
                var weight = 1
                do {
                    count += if (symbol == RUN_A) weight else 2 * weight
                    weight = weight shl 1
                    if (weight >= MAX_RUN_WEIGHT) throw damaged()
                    symbol = next()
                } while (symbol == RUN_A || symbol == RUN_B)
                count++
                if (count > blockSize - length) throw damaged()
                val byte = seqToUnseq[mtf[0]]
                counts[byte] += count
                tt.fill(byte, length, length + count)
                length += count
                continue
            }
            if (length >= blockSize) throw damaged()
            val at = symbol - 1
            val index = mtf[at]
            System.arraycopy(mtf, 0, mtf, 1, at)
            mtf[0] = index
            val byte = seqToUnseq[index]
            counts[byte]++
            tt[length++] = byte
            symbol = next()
        }
        if (origPtr >= length) throw damaged()

        // The inverse Burrows-Wheeler transform, in the form bzip2 itself uses when memory allows.
        val start = IntArray(256)
        var sum = 0
        for (i in 0 until 256) {
            start[i] = sum
            sum += counts[i]
        }
        for (i in 0 until length) {
            val byte = tt[i] and 0xff
            tt[start[byte]] = tt[start[byte]] or (i shl 8)
            start[byte]++
        }
        blockLength = length
        tPos = tt[origPtr] ushr 8
        left = length
        crc = -1
        repeat = 0
        last = -1
        run = 0
    }

    private fun endBlock() {
        inBlock = false
        val got = crc.inv()
        if (got != expectedCrc) throw CompressedDataException("A block of the bzip2 data fails its CRC check")
        combined = ((combined shl 1) or (combined ushr 31)) xor got
    }

    private fun damaged() = CompressedDataException("The bzip2 data is damaged")

    /** A code table as bzip2 builds it: the codes of each length are consecutive, so a code is known by the length it ends at. */
    private class Huffman(lengths: IntArray) {
        private val limit = IntArray(MAX_CODE_LENGTH + 2)
        private val base = IntArray(MAX_CODE_LENGTH + 2)
        private val perm = IntArray(lengths.size)
        private val minLength = lengths.min()
        private val maxLength = lengths.max()

        init {
            var p = 0
            for (length in minLength..maxLength) {
                for (symbol in lengths.indices) if (lengths[symbol] == length) perm[p++] = symbol
            }
            for (length in lengths) base[length + 1]++
            for (i in 1 until base.size) base[i] += base[i - 1]
            var code = 0
            for (length in minLength..maxLength) {
                code += base[length + 1] - base[length]
                limit[length] = code - 1
                code = code shl 1
            }
            for (length in minLength + 1..maxLength) base[length] = ((limit[length - 1] + 1) shl 1) - base[length]
        }

        fun decode(bits: BitInput): Int {
            var length = minLength
            var code = bits.bits(length)
            while (code > limit[length]) {
                length++
                if (length > maxLength) throw CompressedDataException("The bzip2 data is damaged")
                code = (code shl 1) or bits.bit()
            }
            val index = code - base[length]
            if (index < 0 || index >= perm.size) throw CompressedDataException("The bzip2 data is damaged")
            return perm[index]
        }
    }

    /** Bits from the most significant down, as bzip2 writes them. */
    private class BitInput(private val input: InputStream) {
        private val chunk = ByteArray(16 * 1024)
        private var at = 0
        private var end = 0
        private var buffer = 0L
        private var count = 0

        private fun raw(): Int {
            if (at == end) {
                var n: Int
                do {
                    n = input.read(chunk)
                } while (n == 0)
                if (n < 0) return -1
                at = 0
                end = n
            }
            return chunk[at++].toInt() and 0xff
        }

        fun bits(n: Int): Int {
            while (count < n) {
                val b = raw()
                if (b < 0) throw CompressedDataException("The bzip2 data ends early")
                buffer = (buffer shl 8) or b.toLong()
                count += 8
            }
            count -= n
            return ((buffer ushr count) and ((1L shl n) - 1)).toInt()
        }

        fun bit(): Int = bits(1)

        /** Drops what is left of the current byte; a stream ends on a byte boundary. */
        fun align() {
            count -= count % 8
        }

        /** The next whole byte, or -1 at the end of the input. Only called on a byte boundary. */
        fun byteOrEnd(): Int {
            if (count >= 8) {
                count -= 8
                return ((buffer ushr count) and 0xff).toInt()
            }
            return raw()
        }
    }

    private companion object {
        const val BLOCK_MAGIC = 0x314159265359L
        const val END_MAGIC = 0x177245385090L
        const val MAX_TABLES = 6
        const val MAX_SELECTORS = 18002
        const val MAX_CODE_LENGTH = 20
        const val GROUP_SIZE = 50
        const val RUN_A = 0
        const val RUN_B = 1
        const val MAX_RUN_WEIGHT = 2 * 1024 * 1024

        /** CRC-32 as bzip2 counts it: the polynomial 0x04C11DB7, highest bit first. */
        val CRC_TABLE = IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }

        fun crcOf(crc: Int, byte: Int): Int = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor byte) and 0xff]
    }
}
