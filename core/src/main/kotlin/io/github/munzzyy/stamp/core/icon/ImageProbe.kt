package io.github.munzzyy.stamp.core.icon

import java.util.zip.CRC32

enum class ImageKind { PNG, JPEG, WEBP }

data class ImageFacts(val kind: ImageKind, val width: Int, val height: Int)

/**
 * Says what a file is from its own bytes, never from its name or from what a server called it.
 * Only a whole PNG, JPEG or WebP image is recognised; a file that stops early is not.
 */
object ImageProbe {
    fun read(bytes: ByteArray): ImageFacts? = try {
        when {
            startsWith(bytes, PNG_SIGNATURE) -> png(bytes)
            startsWith(bytes, JPEG_START) -> jpeg(bytes)
            startsWith(bytes, RIFF) && startsWith(bytes, WEBP, 8) -> webp(bytes)
            else -> null
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun png(b: ByteArray): ImageFacts? {
        var at = PNG_SIGNATURE.size
        var facts: ImageFacts? = null
        var data = false
        val crc = CRC32()
        while (at + 12 <= b.size) {
            val length = u32(b, at)
            if (length > b.size - at - 12) return null
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            val body = at + 8
            val end = body + length.toInt()
            if (type[0].isUpperCase()) {
                crc.reset()
                crc.update(b, at + 4, 4 + length.toInt())
                if (crc.value != u32(b, end)) return null
            }
            when {
                facts == null -> {
                    if (type != "IHDR" || length != 13L) return null
                    val width = u32(b, body)
                    val height = u32(b, body + 4)
                    if (width !in 1..Int.MAX_VALUE || height !in 1..Int.MAX_VALUE) return null
                    facts = ImageFacts(ImageKind.PNG, width.toInt(), height.toInt())
                }
                type == "IDAT" -> data = true
                type == "IEND" -> return facts.takeIf { data }
            }
            at = end + 4
        }
        return null
    }

    private fun jpeg(b: ByteArray): ImageFacts? {
        var at = 2
        var facts: ImageFacts? = null
        var scanned = false
        while (at + 1 < b.size) {
            if (u8(b, at) != 0xFF) return null
            when (val marker = u8(b, at + 1)) {
                0xFF -> at += 1
                0xD9 -> return facts.takeIf { scanned }
                0x00 -> return null
                0x01, in 0xD0..0xD8 -> at += 2
                else -> {
                    val length = u16(b, at + 2)
                    val next = at + 2 + length
                    if (length < 2 || next > b.size) return null
                    if (marker in SIZE_MARKERS && facts == null) {
                        if (length < 7) return null
                        val height = u16(b, at + 5)
                        val width = u16(b, at + 7)
                        if (width == 0 || height == 0) return null
                        facts = ImageFacts(ImageKind.JPEG, width, height)
                    }
                    if (marker == SCAN) {
                        if (facts == null) return null
                        scanned = true
                    }
                    at = if (marker == SCAN) afterScan(b, next) else next
                }
            }
        }
        return null
    }

    /** Where the picture data that follows a scan header ends: at the first marker that is not part of it. */
    private fun afterScan(b: ByteArray, from: Int): Int {
        var at = from
        while (at + 1 < b.size) {
            if (u8(b, at) == 0xFF) {
                val next = u8(b, at + 1)
                if (next != 0x00 && next != 0xFF && next !in 0xD0..0xD7) return at
            }
            at++
        }
        return b.size
    }

    private fun webp(b: ByteArray): ImageFacts? {
        val declared = u32le(b, 4)
        if (declared < 4 || declared > b.size - 8) return null
        val end = 8 + declared.toInt()
        var at = 12
        var facts: ImageFacts? = null
        var picture = false
        while (at + 8 <= end) {
            val type = String(b, at, 4, Charsets.ISO_8859_1)
            val length = u32le(b, at + 4)
            if (length > end - at - 8) return null
            val body = at + 8
            when (type) {
                "VP8X" -> {
                    if (facts != null || length < 10) return null
                    facts = ImageFacts(ImageKind.WEBP, u24le(b, body + 4) + 1, u24le(b, body + 7) + 1)
                }
                "VP8 " -> {
                    if (length < 10 || (u8(b, body) and 1) != 0) return null
                    if (u8(b, body + 3) != 0x9D || u8(b, body + 4) != 0x01 || u8(b, body + 5) != 0x2A) return null
                    val width = u16le(b, body + 6) and 0x3FFF
                    val height = u16le(b, body + 8) and 0x3FFF
                    if (width == 0 || height == 0) return null
                    if (facts == null) facts = ImageFacts(ImageKind.WEBP, width, height)
                    picture = true
                }
                "VP8L" -> {
                    if (length < 5 || u8(b, body) != 0x2F) return null
                    val bits = u32le(b, body + 1)
                    if (facts == null) facts = ImageFacts(ImageKind.WEBP, (bits and 0x3FFFL).toInt() + 1, ((bits shr 14) and 0x3FFFL).toInt() + 1)
                    picture = true
                }
                "ANMF" -> picture = true
                else -> if (facts == null) return null
            }
            at = body + length.toInt() + (length.toInt() and 1)
        }
        return facts.takeIf { picture }
    }

    private fun startsWith(b: ByteArray, prefix: ByteArray, at: Int = 0): Boolean {
        if (b.size < at + prefix.size) return false
        for (i in prefix.indices) if (b[at + i] != prefix[i]) return false
        return true
    }

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    private fun u16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)

    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    private fun u16le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)

    private fun u24le(b: ByteArray, at: Int): Int = u16le(b, at) or (u8(b, at + 2) shl 16)

    private fun u32le(b: ByteArray, at: Int): Long = u16le(b, at).toLong() or (u16le(b, at + 2).toLong() shl 16)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val RIFF = "RIFF".toByteArray(Charsets.ISO_8859_1)
    private val WEBP = "WEBP".toByteArray(Charsets.ISO_8859_1)
    private val SIZE_MARKERS = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)
    private const val SCAN = 0xDA
}
