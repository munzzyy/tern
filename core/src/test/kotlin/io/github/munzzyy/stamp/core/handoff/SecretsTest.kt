package io.github.munzzyy.stamp.core.handoff

import java.net.InetAddress
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsTest {
    /** Hands out the bytes and the number it was made with, so that a test knows what has to come out. */
    private class Fixed(private val fill: Byte, private val number: Int) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) = bytes.fill(fill)

        override fun nextInt(bound: Int): Int = number
    }

    @Test
    fun base32IsTheOneOfTheStandardInLowerCaseAndWithoutPadding() {
        val vectors = mapOf("" to "", "f" to "my", "fo" to "mzxq", "foo" to "mzxw6", "foob" to "mzxw6yq", "fooba" to "mzxw6ytb", "foobar" to "mzxw6ytboi")
        for ((plain, encoded) in vectors) assertEquals(encoded, Secrets.base32(plain.toByteArray()))
    }

    @Test
    fun aSecretHolds128BitsIn26Characters() {
        assertEquals("a".repeat(26), Secrets.secret(Fixed(0, 0)))
        assertEquals("7".repeat(25) + "4", Secrets.secret(Fixed(-1, 0)))
        val secret = Secrets.secret(SecureRandom())
        assertEquals(26, secret.length)
        assertTrue(secret, secret.all { it in 'a'..'z' || it in '2'..'7' })
        assertNotEquals(secret, Secrets.secret(SecureRandom()))
    }

    @Test
    fun aPinHasSixDigitsAlsoWhenTheNumberIsSmall() {
        assertEquals("000042", Secrets.pin(Fixed(0, 42)))
        assertEquals("000000", Secrets.pin(Fixed(0, 0)))
        assertEquals("999999", Secrets.pin(Fixed(0, 999_999)))
    }

    @Test
    fun onlyTheSameTextIsTheSame() {
        assertTrue(Secrets.same("402917", "402917"))
        for (other in listOf("", "4", "40291", "4029170", "402918", "502917", "402917402917", "\u0000", "\uFF14\uFF10\uFF12\uFF19\uFF11\uFF17")) {
            assertFalse(other, Secrets.same("402917", other))
        }
        assertTrue(Secrets.same("", ""))
        assertFalse(Secrets.same("", "a"))
    }

    @Test
    fun theAddressToListenOnIsThePrivateOneAndNeverAnotherKind() {
        fun address(text: String) = InetAddress.getByName(text)
        assertEquals(address("192.168.1.23"), LocalAddress.pick(listOf(address("fe80::1"), address("192.168.1.23"), address("10.0.0.2"))))
        assertEquals(address("10.0.0.2"), LocalAddress.pick(listOf(address("100.64.0.7"), address("10.0.0.2"))))
        assertEquals(address("172.31.255.254"), LocalAddress.pick(listOf(address("172.32.0.1"), address("172.31.255.254"))))
        for (none in listOf("127.0.0.1", "169.254.10.10", "100.64.0.7", "8.8.8.8", "0.0.0.0", "172.15.0.1", "192.169.0.1", "fd00::1", "::1")) {
            assertNull(none, LocalAddress.pick(listOf(address(none))))
        }
        assertNull(LocalAddress.pick(emptyList()))
    }
}
