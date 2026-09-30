package io.github.munzzyy.tern.core.apk

import java.io.FilterInputStream
import java.io.InputStream

/** One file inside a tar archive: its name as the archive gives it, and its length. */
data class TarEntry(val name: String, val size: Long)

/**
 * Reads the regular files of a tar archive, in the ustar and GNU forms, one after the other from
 * a stream. Names are data, never paths: nothing here touches the file system. Anything that is
 * not a regular file (a directory, a link, a device, a header of extended attributes) is passed
 * over, and a GNU long name is taken for the file that follows it.
 */
object TarReader {
    private const val BLOCK = 512
    private const val MAX_NAME = 4096

    /**
     * Calls [onFile] for every regular file, with a stream that ends where the file does. What
     * [onFile] leaves unread is skipped. Stops after [maxEntries] headers, and throws
     * [ApkFormatException] for an archive it cannot read.
     */
    fun read(input: InputStream, maxEntries: Int = 10_000, onFile: (TarEntry, InputStream) -> Unit) {
        var longName: String? = null
        var seen = 0
        val header = ByteArray(BLOCK)
        while (true) {
            if (!fill(input, header)) return
            if (header.all { it.toInt() == 0 }) return
            if (++seen > maxEntries) throw ApkFormatException("The archive holds more than $maxEntries entries")
            if (!checksumMatches(header)) throw ApkFormatException("A header of the archive is damaged")
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val name = longName ?: nameOf(header)
            longName = null
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK
            when (type) {
                '0', '\u0000', '7' -> {
                    val body = Bounded(input, size)
                    onFile(TarEntry(name, size), body)
                    body.drain()
                    skip(input, padded - size)
                }
                'L' -> {
                    if (size > MAX_NAME) throw ApkFormatException("A name in the archive is too long")
                    val bytes = ByteArray(size.toInt())
                    if (!fill(input, bytes)) throw ApkFormatException("The archive ends inside a name")
                    longName = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
                    skip(input, padded - size)
                }
                else -> skip(input, padded)
            }
        }
    }

    private fun nameOf(header: ByteArray): String {
        val name = text(header, 0, 100)
        val ustar = text(header, 257, 5) == "ustar"
        val prefix = if (ustar) text(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun text(header: ByteArray, from: Int, length: Int): String {
        var end = from
        while (end < from + length && header[end].toInt() != 0) end++
        return String(header, from, end - from, Charsets.UTF_8)
    }

    private fun octal(header: ByteArray, from: Int, length: Int): Long {
        if (header[from].toInt() and 0x80 != 0) throw ApkFormatException("A file in the archive is too large")
        val digits = text(header, from, length).trim()
        if (digits.isEmpty()) return 0
        return digits.toLongOrNull(8)?.takeIf { it >= 0 } ?: throw ApkFormatException("A size in the archive cannot be read")
    }

    /** The header sum, counted with the field of the sum itself as spaces, the way both signed and unsigned writers count. */
    private fun checksumMatches(header: ByteArray): Boolean {
        val stored = runCatching { octal(header, 148, 8) }.getOrNull() ?: return false
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until BLOCK) {
            val b = if (i in 148 until 156) ' '.code.toByte() else header[i]
            unsigned += b.toInt() and 0xff
            signed += b.toInt()
        }
        return stored == unsigned || stored == signed
    }

    private fun fill(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw ApkFormatException("The archive ends inside a header")
            }
            read += n
        }
        return true
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw ApkFormatException("The archive ends early")
            left -= n
        }
    }

    /** The body of one file: it ends where the file does, and [drain] reads what is left of it. */
    private class Bounded(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = super.read()
            if (b < 0) throw ApkFormatException("The archive ends inside a file")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw ApkFormatException("The archive ends inside a file")
            left -= n
            return n
        }

        override fun close() = Unit

        fun drain() {
            val scratch = ByteArray(8192)
            @Suppress("ControlFlowWithEmptyBody")
            while (read(scratch) >= 0) {
            }
        }
    }
}
