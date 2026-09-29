package io.github.munzzyy.stamp.core.handoff

import java.security.SecureRandom

internal object Secrets {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val SECRET_BYTES = 16
    private const val PIN_DIGITS = 6

    /** 128 random bits, which are 26 characters. */
    fun secret(random: SecureRandom): String = base32(ByteArray(SECRET_BYTES).also(random::nextBytes))

    fun pin(random: SecureRandom): String = random.nextInt(1_000_000).toString().padStart(PIN_DIGITS, '0')

    fun base32(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var held = 0
        var bits = 0
        for (b in bytes) {
            held = ((held shl 8) or (b.toInt() and 0xFF)) and 0xFFFF
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(held ushr bits) and 31])
            }
        }
        if (bits > 0) out.append(ALPHABET[(held shl (5 - bits)) and 31])
        return out.toString()
    }

    /** Takes as long for a wrong guess as for the right one: every byte of [expected] is looked at whatever [given] holds. */
    fun same(expected: String, given: String): Boolean {
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = given.toByteArray(Charsets.UTF_8)
        var difference = a.size xor b.size
        for (i in a.indices) difference = difference or (a[i].toInt() xor if (b.isEmpty()) 0 else b[i % b.size].toInt())
        return difference == 0
    }
}
