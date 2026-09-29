package io.github.munzzyy.tern.core.qr

/** The squares of a QR code, [size] by [size], without the quiet border a drawing has to leave around it. */
class QrMatrix internal constructor(val version: Int, val mask: Int, val size: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]

    /** Row by row, from the top left. */
    fun squares(): BooleanArray = dark.copyOf()
}

/**
 * Writes text as a QR code in byte mode with error correction level M, in the smallest of the
 * versions 1 to 10 that holds it. The mask is the one with the lowest penalty, counted the way
 * libqrencode counts, so the result is the same square for square.
 */
object QrEncoder {
    /** The longest text, in bytes of UTF-8, that version 10 holds. */
    const val MAX_BYTES = 213

    private const val MAX_VERSION = 10
    private val TOTAL_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    private val CHECK_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private val ALIGNMENT = arrayOf(
        intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
        intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
    )
    private const val BYTE_MODE = 0b0100
    private const val LEVEL_M = 0b00
    private const val FORMAT_GENERATOR = 0x537
    private const val FORMAT_MASK = 0x5412
    private const val VERSION_GENERATOR = 0x1F25

    /** Throws IllegalArgumentException when [text] is longer than [MAX_BYTES] bytes. */
    fun encode(text: String): QrMatrix {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = (1..MAX_VERSION).firstOrNull { bytes.size <= capacity(it) }
            ?: throw IllegalArgumentException("${bytes.size} bytes do not fit into a QR code of version $MAX_VERSION")
        val codewords = interleave(version, dataCodewords(version, bytes))
        val size = 17 + 4 * version
        val frame = Frame(size)
        frame.drawFunctionPatterns(version)
        frame.place(codewords)

        var best: BooleanArray? = null
        var bestMask = 0
        var lowest = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val masked = frame.masked(mask)
            val penalty = Penalty.of(size, masked)
            if (penalty < lowest) {
                lowest = penalty
                best = masked
                bestMask = mask
            }
        }
        return QrMatrix(version, bestMask, size, best!!)
    }

    private fun dataCapacity(version: Int): Int = TOTAL_CODEWORDS[version] - CHECK_PER_BLOCK[version] * BLOCKS[version]

    private fun countBits(version: Int): Int = if (version < 10) 8 else 16

    private fun capacity(version: Int): Int = (dataCapacity(version) * 8 - 4 - countBits(version)) / 8

    private fun dataCodewords(version: Int, bytes: ByteArray): IntArray {
        val room = dataCapacity(version)
        val bits = BitWriter(room)
        bits.write(BYTE_MODE, 4)
        bits.write(bytes.size, countBits(version))
        for (b in bytes) bits.write(b.toInt() and 0xFF, 8)
        bits.write(0, minOf(4, room * 8 - bits.length))
        bits.write(0, (8 - bits.length % 8) % 8)
        var pad = 0xEC
        while (bits.length < room * 8) {
            bits.write(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        return bits.codewords
    }

    private fun interleave(version: Int, data: IntArray): IntArray {
        val blocks = BLOCKS[version]
        val checkLength = CHECK_PER_BLOCK[version]
        val shortLength = data.size / blocks
        val longBlocks = data.size % blocks
        val generator = ReedSolomon.generator(checkLength)
        val dataBlocks = ArrayList<IntArray>(blocks)
        val checkBlocks = ArrayList<IntArray>(blocks)
        var at = 0
        for (b in 0 until blocks) {
            val length = shortLength + if (b >= blocks - longBlocks) 1 else 0
            val block = data.copyOfRange(at, at + length)
            at += length
            dataBlocks += block
            checkBlocks += ReedSolomon.remainder(block, generator)
        }
        val out = IntArray(TOTAL_CODEWORDS[version])
        var n = 0
        for (i in 0..shortLength) for (block in dataBlocks) if (i < block.size) out[n++] = block[i]
        for (i in 0 until checkLength) for (block in checkBlocks) out[n++] = block[i]
        return out
    }

    private class BitWriter(codewordCount: Int) {
        val codewords = IntArray(codewordCount)
        var length = 0
            private set

        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                if ((value ushr i) and 1 == 1) codewords[length ushr 3] = codewords[length ushr 3] or (0x80 ushr (length and 7))
                length++
            }
        }
    }

    private class Frame(val size: Int) {
        private val dark = BooleanArray(size * size)
        private val fixed = BooleanArray(size * size)

        private fun set(x: Int, y: Int, value: Boolean) {
            dark[y * size + x] = value
            fixed[y * size + x] = true
        }

        fun drawFunctionPatterns(version: Int) {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val centres = ALIGNMENT[version]
            for (cy in centres) for (cx in centres) {
                val onFinder = (cx == 6 && cy == 6) || (cx == 6 && cy == size - 7) || (cx == size - 7 && cy == 6)
                if (!onFinder) alignment(cx, cy)
            }
            format(0)
            if (version >= 7) version(version)
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val ring = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(x, y, ring != 2 && ring != 4)
            }
        }

        private fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }

        fun format(mask: Int) {
            val data = (LEVEL_M shl 3) or mask
            var rest = data
            for (i in 0 until 10) rest = (rest shl 1) xor ((rest ushr 9) * FORMAT_GENERATOR)
            val bits = ((data shl 10) or rest) xor FORMAT_MASK
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun version(version: Int) {
            var rest = version
            for (i in 0 until 12) rest = (rest shl 1) xor ((rest ushr 11) * VERSION_GENERATOR)
            val bits = (version shl 12) or rest
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, bit)
                set(b, a, bit)
            }
        }

        /** In pairs of columns from the right, up and down in turn, leaving out the column of the timing pattern. */
        fun place(codewords: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                val upward = ((right + 1) and 2) == 0
                for (step in 0 until size) {
                    val y = if (upward) size - 1 - step else step
                    for (j in 0..1) {
                        val x = right - j
                        if (fixed[y * size + x]) continue
                        if (i < codewords.size * 8) {
                            dark[y * size + x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun masked(mask: Int): BooleanArray {
            format(mask)
            val out = dark.copyOf()
            for (y in 0 until size) for (x in 0 until size) {
                if (!fixed[y * size + x] && flips(mask, x, y)) out[y * size + x] = !out[y * size + x]
            }
            return out
        }

        private fun flips(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }
    }
}
