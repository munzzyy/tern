package io.github.munzzyy.tern.core.apk

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Bytes that can be read at any offset. [read] returns exactly [length] bytes or throws. */
interface RandomAccessSource : Closeable {
    val size: Long

    @Throws(IOException::class)
    fun read(offset: Long, length: Int): ByteArray
}

internal fun checkRead(offset: Long, length: Int, size: Long) {
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        throw ApkFormatException("Read of $length bytes at $offset is outside a file of $size bytes")
    }
}

class BytesSource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        checkRead(offset, length, size)
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun close() {}
}

class FileSource(file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")

    override val size: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        checkRead(offset, length, size)
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/** A byte range of [parent] seen as a file of its own, such as a stored zip entry. Closing it leaves [parent] open. */
class WindowSource(private val parent: RandomAccessSource, private val offset: Long, length: Long) : RandomAccessSource {
    override val size: Long = length

    init {
        if (offset < 0 || length < 0 || offset > parent.size || length > parent.size - offset) {
            throw ApkFormatException("Window $offset+$length is outside a file of ${parent.size} bytes")
        }
    }

    override fun read(offset: Long, length: Int): ByteArray {
        checkRead(offset, length, size)
        return parent.read(this.offset + offset, length)
    }

    override fun close() {}
}
