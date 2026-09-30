package io.github.munzzyy.tern.core.compress

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The plain data the compressed fixtures were made from, made again the same way. The fixtures
 * were packed by the real bzip2 and xz encoders; only what they made is kept.
 */
object Samples {
    private const val A = 6364136223846793005L
    private const val C = 1442695040888963407L
    private val WORDS = listOf(
        "tern", "release", "apk", "signer", "the", "and", "of", "update", "file", "archive",
        "version", "check", "android", "install", "gate", "a", "zero", "one",
    )

    /** Words and spaces, now and then a line break: data that packs well. */
    fun text(size: Int, seed: Long): ByteArray {
        var x = seed
        val out = ByteArrayOutputStream()
        while (out.size() < size) {
            x = x * A + C
            out.write(WORDS[((x ushr 33) % WORDS.size).toInt()].toByteArray())
            out.write(if ((x ushr 20) % 11 == 0L) '\n'.code else ' '.code)
        }
        return out.toByteArray().copyOf(size)
    }

    /** Bytes that do not pack at all. */
    fun noise(size: Int, seed: Long): ByteArray {
        var x = seed
        return ByteArray(size) {
            x = x * A + C
            (x ushr 56).toByte()
        }
    }

    /** Runs of every length around the ones bzip2 treats apart: 1 to 4, and past 255. */
    fun runs(): ByteArray {
        val out = ByteArrayOutputStream()
        listOf(1, 2, 3, 4, 5, 6, 250, 255, 256, 259, 260, 1000, 70000, 3, 4).forEachIndexed { i, length ->
            repeat(length) { out.write(0x41 + i % 26) }
        }
        return out.toByteArray()
    }

    /** Everything [input] gives, read in pieces of [chunk] bytes, as a caller with a small buffer reads it. */
    fun readAll(input: InputStream, chunk: Int = 8192): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
