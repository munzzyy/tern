package io.github.munzzyy.tern.core.handoff

import java.security.SecureRandom

internal object Secrets {
    /** The alphabet of base32 in RFC 4648, in lower case. It has no 0, 1, 8 or 9. */
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    const val CODE_LENGTH = 20
    private const val GROUP = 4

    /** 100 random bits in 20 characters. Each character is the low five bits of a random byte of its own. */
    fun code(random: SecureRandom): String {
        val bytes = ByteArray(CODE_LENGTH).also(random::nextBytes)
        return String(CharArray(CODE_LENGTH) { ALPHABET[bytes[it].toInt() and 31] })
    }

    /** The code the way it is shown, in groups of four with a space between. */
    fun grouped(code: String): String = code.chunked(GROUP).joinToString(" ")

    /** Takes as long for a wrong tag as for the right one. */
    fun same(expected: ByteArray, given: ByteArray): Boolean {
        if (expected.size != given.size) return false
        var difference = 0
        for (i in expected.indices) difference = difference or (expected[i].toInt() xor given[i].toInt())
        return difference == 0
    }
}
