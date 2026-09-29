package io.github.munzzyy.stamp.core.verify

import java.security.MessageDigest

object Fingerprints {
    fun normalize(text: String): String? {
        val hex = text.trim().replace(":", "").lowercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return hex
    }

    fun format(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(digest)
    }

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
