package io.github.munzzyy.jackdaw.ui.text

import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.ChecksumState
import io.github.munzzyy.jackdaw.engine.SignerState
import io.github.munzzyy.jackdaw.engine.Verification
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
}
