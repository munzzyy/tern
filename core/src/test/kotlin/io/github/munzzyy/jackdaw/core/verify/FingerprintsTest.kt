package io.github.munzzyy.jackdaw.core.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintsTest {
    @Test
    fun normalizesColonSeparatedHex() {
        val colons = (1..32).joinToString(":") { "AB" }
        assertEquals("ab".repeat(32), Fingerprints.normalize(colons))
    }

    @Test
    fun normalizesPlainHexWithWhitespace() {
        val hex = "ab".repeat(32)
        assertEquals(hex, Fingerprints.normalize("  $hex  \n"))
    }

    @Test
    fun rejectsWrongLength() {
        assertNull(Fingerprints.normalize("ab".repeat(31)))
        assertNull(Fingerprints.normalize("ab".repeat(33)))
    }

    @Test
    fun rejectsNonHexCharacters() {
        assertNull(Fingerprints.normalize("gg".repeat(32)))
    }

    @Test
    fun formatUppercasesAndColonSeparates() {
        assertEquals("AB:CD", Fingerprints.format("abcd"))
    }

    @Test
    fun sha256MatchesKnownVector() {
        val digest = Fingerprints.sha256(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest)
    }
}
