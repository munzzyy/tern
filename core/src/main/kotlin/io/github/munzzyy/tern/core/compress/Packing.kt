package io.github.munzzyy.tern.core.compress

import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** How the bytes of a file are compressed, as its first bytes say whatever its name. */
enum class Packing {
    NONE,
    GZIP,
    BZIP2,
    XZ,

    /** Not read: Obtainium does not read it either. */
    ZSTD,
    ;

    companion object {
        /** How many first bytes [of] looks at. */
        const val HEAD = 6

        fun of(head: ByteArray): Packing {
            fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xff else -1
            return when {
                at(0) == 0x1f && at(1) == 0x8b -> GZIP
                at(0) == 'B'.code && at(1) == 'Z'.code && at(2) == 'h'.code && at(3) in '1'.code..'9'.code -> BZIP2
                (0 until 6).all { at(it) == XZ_MAGIC[it] } -> XZ
                (0 until 4).all { at(it) == ZSTD_MAGIC[it] } -> ZSTD
                else -> NONE
            }
        }

        /**
         * [input] as it reads unpacked, never more than [maxOutput] bytes of it. A packing that is
         * not read here is a [CompressedDataException].
         */
        fun open(input: InputStream, packing: Packing, maxOutput: Long): InputStream = when (packing) {
            NONE -> input
            GZIP -> Bounded(GZIPInputStream(input), maxOutput)
            BZIP2 -> Bzip2InputStream(input, maxOutput)
            XZ -> XzInputStream(input, maxOutput)
            ZSTD -> throw CompressedDataException("zstd data is not read")
        }

        private val XZ_MAGIC = intArrayOf(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00)
        private val ZSTD_MAGIC = intArrayOf(0x28, 0xb5, 0x2f, 0xfd)
    }
}

/** Gives the bytes of [input] and refuses to give more than [max] of them, for a decompressor that has no limit of its own. */
class Bounded(input: InputStream, private val max: Long) : FilterInputStream(input) {
    private var given = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n)
        return n
    }

    private fun count(n: Int) {
        given += n
        if (given > max) throw CompressedDataException("The data unpacks to more than $max bytes")
    }
}
