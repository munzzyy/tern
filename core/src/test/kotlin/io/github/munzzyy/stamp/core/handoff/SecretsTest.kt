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
    /** Hands out the bytes it was made with, over and over, so that a test knows what has to come out. */
    private class Fixed(private vararg val bytes: Int) : SecureRandom() {
        override fun nextBytes(into: ByteArray) {
            for (i in into.indices) into[i] = bytes[i % bytes.size].toByte()
        }
    }

    @Test
    fun aCodeIsTwentyCharactersOfTheAlphabetOfBase32() {
        assertEquals("abcdefghijklmnopqrstuvwxyz234567", Secrets.ALPHABET)
        assertEquals("a".repeat(20), Secrets.code(Fixed(0)))
        assertEquals("7".repeat(20), Secrets.code(Fixed(31)))
        assertEquals("7".repeat(20), Secrets.code(Fixed(255)))
        assertEquals("abcdefghijklmnopqrst", Secrets.code(Fixed(*IntArray(20) { it })))
        assertEquals("uvwxyz234567abcdefgh", Secrets.code(Fixed(*IntArray(20) { 0xE0 + 20 + it })))
        val code = Secrets.code(SecureRandom())
        assertEquals(20, code.length)
        assertTrue(code, code.all { it in 'a'..'z' || it in '2'..'7' })
        assertNotEquals(code, Secrets.code(SecureRandom()))
    }

    @Test
    fun everyCharacterOfACodeComesAsOftenAsAnyOther() {
        val random = SecureRandom()
        val counts = IntArray(32)
        repeat(8_000) { for (c in Secrets.code(random)) counts[Secrets.ALPHABET.indexOf(c)]++ }
        // 160000 draws, 5000 for each character with a deviation of 70. Nine deviations is what chance does not do.
        for (i in counts.indices) assertTrue("${Secrets.ALPHABET[i]} came ${counts[i]} times", counts[i] in 4_370..5_630)
    }

    @Test
    fun aCodeIsShownInFiveGroupsOfFour() {
        assertEquals("k4mz q7wd x2np h5tc r3vb", Secrets.grouped("k4mzq7wdx2nph5tcr3vb"))
    }

    @Test
    fun onlyTheSameBytesAreTheSame() {
        val tag = ByteArray(32) { (it * 7).toByte() }
        assertTrue(Secrets.same(tag, tag.copyOf()))
        for (i in tag.indices) {
            for (bit in 0 until 8) {
                val other = tag.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
                assertFalse("byte $i bit $bit", Secrets.same(tag, other))
            }
        }
        assertFalse(Secrets.same(tag, tag.copyOf(31)))
        assertFalse(Secrets.same(tag, tag.copyOf(33)))
        assertFalse(Secrets.same(tag, ByteArray(0)))
        assertTrue(Secrets.same(ByteArray(0), ByteArray(0)))
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
