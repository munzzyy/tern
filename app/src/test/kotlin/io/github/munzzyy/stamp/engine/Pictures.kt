package io.github.munzzyy.stamp.engine

import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Pictures drawn in the test itself, so that no file has to be shared between the modules. */
object Pictures {
    /** A whole black and white PNG. [padding] bytes of noise in a text chunk make it as large as a test needs, [seed] makes it another file. */
    fun png(width: Int, height: Int, padding: Int = 0, seed: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", int(width) + int(height) + byteArrayOf(1, 0, 0, 0, 0))
        chunk(out, "tEXt", ByteArray(padding + 4).also { Random(seed.toLong()).nextBytes(it) })
        chunk(out, "IDAT", deflated(ByteArray(height * (1 + (width + 7) / 8))))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(name)
            update(data)
        }
        out.write(int(data.size))
        out.write(name)
        out.write(data)
        out.write(int(crc.value.toInt()))
    }

    private fun int(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun deflated(raw: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    const val SVG = "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"96\" height=\"96\"><rect width=\"96\" height=\"96\" fill=\"#2e7d6b\"/></svg>\n"
}
