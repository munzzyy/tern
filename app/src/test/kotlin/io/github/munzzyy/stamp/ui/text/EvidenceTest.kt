package io.github.munzzyy.stamp.ui.text

import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.ChecksumState
import io.github.munzzyy.stamp.engine.SignerState
import io.github.munzzyy.stamp.engine.Verification
import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceTest {
    private fun v(state: SignerState, verified: Boolean, checksum: ChecksumState = ChecksumState.MATCHED) =
        Verification("org.example.app", listOf("ab".repeat(32)), verified, state, checksum, null, emptyList())

    @Test
    fun headerOnlyCertificatesAreClaimsNotFacts() {
        assertEquals(R.string.signer_matches_installed_claimed, signerLine(v(SignerState.MATCHES_INSTALLED, verified = false)).text)
        assertEquals(R.string.signer_matches_pin_claimed, signerLine(v(SignerState.MATCHES_PIN, verified = false)).text)
        assertEquals(R.string.signer_first_seen_claimed, signerLine(v(SignerState.FIRST_SEEN, verified = false)).text)
        assertEquals(R.string.signer_mismatch_claimed, signerLine(v(SignerState.MISMATCH, verified = false)).text)
    }

    @Test
    fun verifiedCertificatesAreStatedPlainly() {
        assertEquals(R.string.signer_matches_installed, signerLine(v(SignerState.MATCHES_INSTALLED, verified = true)).text)
        assertEquals(R.string.signer_first_seen, signerLine(v(SignerState.FIRST_SEEN, verified = true)).text)
    }

    @Test
    fun mismatchesAreBad() {
        assertEquals(Trust.BAD, signerLine(v(SignerState.MISMATCH, true)).trust)
        assertEquals(Trust.BAD, checksumLine(v(SignerState.MATCHES_PIN, true, ChecksumState.MISMATCH)).trust)
        assertEquals(Trust.NOTE, checksumLine(v(SignerState.MATCHES_PIN, true, ChecksumState.NOT_PUBLISHED)).trust)
        assertEquals(R.string.checksum_not_published, checksumLine(v(SignerState.MATCHES_PIN, true, ChecksumState.NOT_PUBLISHED)).text)
    }

    @Test
    fun fingerprintIsColonPairsUppercase() {
        assertEquals("AB:CD:01", formatFingerprint("abcd01"))
        assertEquals(95, formatFingerprint("0f".repeat(32)).length)
    }

    @Test
    fun wrappedFingerprintBreaksOnlyBetweenPairsAndStaysLeftToRight() {
        val shown = breakableFingerprint(formatFingerprint("abcd01"))
        assertEquals("\u2066AB:\u200BCD:\u200B01\u2069", shown)
        assertEquals("AB:CD:01", shown.filter { it.code < 0x2000 })
    }

    @Test
    fun aValueThatStartsWithDigitsIsHeldLeftToRight() {
        assertEquals("\u206635:D2\u2069", ltr("35:D2"))
    }

    @Test
    fun oddOrNonHexFingerprintIsLeftAlone() {
        assertEquals("abc", formatFingerprint("abc"))
        assertEquals("zz", formatFingerprint("zz"))
        assertEquals("", formatFingerprint(""))
    }

    @Test
    fun permissionPrefixIsDroppedOnlyForAndroidOnes() {
        assertEquals("CAMERA", shortPermission("android.permission.CAMERA"))
        assertEquals("com.vendor.permission.X", shortPermission("com.vendor.permission.X"))
    }

    @Test
    fun virusTotalLinksOnlyAWellFormedFingerprint() {
        val hash = "0123456789abcdef".repeat(4)
        assertEquals("https://www.virustotal.com/gui/file/$hash", virusTotalUrl(hash))
        for (bad in listOf(null, "", hash.uppercase(), hash.dropLast(1), hash + "0", "$hash/../../x", hash.replaceRange(0, 1, "g"), " $hash")) {
            assertEquals(bad, null, virusTotalUrl(bad))
        }
    }
}
