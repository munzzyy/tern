package io.github.munzzyy.stamp.core.apk

internal class LeReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    var position: Int = start
        private set

    init {
        if (start < 0 || end > data.size || start > end) throw ApkFormatException("Bad slice $start..$end of ${data.size}")
    }

    val remaining: Int get() = end - position

    fun hasRemaining(): Boolean = position < end

    private fun need(n: Int) {
        if (n < 0 || n > remaining) throw ApkFormatException("Need $n bytes at $position, only $remaining left")
    }

    fun u8(): Int {
        need(1)
        return data[position++].toInt() and 0xff
    }

    fun u16(): Int {
        need(2)
        return data.u16(position).also { position += 2 }
    }

    fun u32(): Long {
        need(4)
        return data.u32(position).also { position += 4 }
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        need(8)
        val lo = data.u32(position)
        val hi = data.u32(position + 4)
        position += 8
        if (hi and 0x80000000L != 0L) throw ApkFormatException("64-bit value too large")
        return (hi shl 32) or lo
    }

    /** A u32 that must also fit a non-negative Int, as every length in these formats must. */
    fun length(): Int {
        val value = u32()
        if (value > Int.MAX_VALUE) throw ApkFormatException("Length $value too large")
        return value.toInt()
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        return data.copyOfRange(position, position + n).also { position += n }
    }

    fun skip(n: Int) {
        need(n)
        position += n
    }

    fun slice(n: Int): LeReader {
        need(n)
        return LeReader(data, position, position + n).also { position += n }
    }

    fun lengthPrefixed(): LeReader = slice(length())

    fun lengthPrefixedBytes(): ByteArray = bytes(length())
}

internal fun ByteArray.u16(at: Int): Int {
    if (at < 0 || at > size - 2) throw ApkFormatException("Offset $at out of bounds")
    return (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)
}

internal fun ByteArray.u32(at: Int): Long {
    if (at < 0 || at > size - 4) throw ApkFormatException("Offset $at out of bounds")
    return (this[at].toLong() and 0xff) or
        ((this[at + 1].toLong() and 0xff) shl 8) or
        ((this[at + 2].toLong() and 0xff) shl 16) or
        ((this[at + 3].toLong() and 0xff) shl 24)
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[2 * i] = digits[b ushr 4]
        out[2 * i + 1] = digits[b and 0x0f]
    }
    return String(out)
}
