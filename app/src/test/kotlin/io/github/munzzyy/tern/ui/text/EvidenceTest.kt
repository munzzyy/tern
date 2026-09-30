package io.github.munzzyy.tern.ui.text

import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.ChecksumState
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SignerState
import io.github.munzzyy.tern.engine.Verification
import io.github.munzzyy.tern.engine.real.Evaluator
import io.github.munzzyy.tern.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun aStoresChecksumIsCalledTheStoresAndNotThePublishers() {
        val store = v(SignerState.FIRST_SEEN, true).copy(checksumFromStore = true)
        assertEquals(R.string.stores_checksum_matched, checksumLine(store).text)
        assertEquals(R.string.stores_explain_checksum_matched, checksumExplanation(store))
        assertEquals(R.string.checksum_matched, checksumLine(v(SignerState.FIRST_SEEN, true)).text)
        assertEquals(R.string.explain_checksum_matched, checksumExplanation(v(SignerState.FIRST_SEEN, true)))

        assertTrue(Evaluator.fromStore(SourceTypes.APKPURE, ChecksumState.MATCHED))
        assertTrue(Evaluator.fromStore(SourceTypes.HUAWEI, ChecksumState.PENDING))
        assertFalse(Evaluator.fromStore(SourceTypes.APKPURE, ChecksumState.NOT_PUBLISHED))
        assertFalse(Evaluator.fromStore(SourceTypes.GITHUB, ChecksumState.MATCHED))
        assertFalse(Evaluator.fromStore(SourceTypes.ITCHIO, ChecksumState.MATCHED))
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

    private fun checked(state: SignerState, verified: Boolean = true, checksum: ChecksumState = ChecksumState.MATCHED) =
        testRow().copy(verification = v(state, verified, checksum))

    @Test
    fun theSealIsForAFileThatAndroidReadAndThatMatchesWhatIsKnown() {
        assertTrue(passedEveryCheck(checked(SignerState.MATCHES_INSTALLED)))
        assertTrue(passedEveryCheck(checked(SignerState.MATCHES_PIN)))
        assertTrue(passedEveryCheck(checked(SignerState.MATCHES_PIN, checksum = ChecksumState.NOT_PUBLISHED)))
    }

    @Test
    fun noSealWhileAnythingIsOpenOrWrong() {
        assertFalse(passedEveryCheck(testRow()))
        assertFalse(passedEveryCheck(checked(SignerState.MATCHES_INSTALLED, verified = false)))
        assertFalse(passedEveryCheck(checked(SignerState.FIRST_SEEN)))
        assertFalse(passedEveryCheck(checked(SignerState.UNKNOWN)))
        assertFalse(passedEveryCheck(checked(SignerState.MISMATCH)))
        assertFalse(passedEveryCheck(checked(SignerState.MATCHES_PIN, checksum = ChecksumState.PENDING)))
        assertFalse(passedEveryCheck(checked(SignerState.MATCHES_PIN, checksum = ChecksumState.MISMATCH)))
        val good = checked(SignerState.MATCHES_INSTALLED)
        assertFalse(passedEveryCheck(good.copy(status = AppStatus.BLOCKED)))
        assertFalse(passedEveryCheck(good.copy(problem = Problem(ProblemKind.DOWNGRADE, "older"))))
        assertFalse(passedEveryCheck(good.copy(verification = good.verification?.copy(packageName = null))))
        assertFalse(passedEveryCheck(good.copy(verification = good.verification?.copy(signers = emptyList()))))
    }

    @Test
    fun theSignerIsExplainedByWhatThereIsToCompareWith() {
        assertEquals(R.string.explain_signer_first, signerExplanation(v(SignerState.FIRST_SEEN, false), installed = false))
        assertEquals(R.string.explain_signer_first, signerExplanation(v(SignerState.UNKNOWN, false), installed = false))
        assertEquals(R.string.explain_signer_installed, signerExplanation(v(SignerState.UNKNOWN, false), installed = true))
        for (state in listOf(SignerState.MATCHES_PIN, SignerState.MATCHES_INSTALLED, SignerState.MISMATCH)) {
            assertEquals(R.string.explain_signer_installed, signerExplanation(v(state, true), installed = false))
        }
    }

    @Test
    fun everyStateOfTheChecksumHasItsOwnExplanation() {
        val explained = ChecksumState.entries.map { checksumExplanation(v(SignerState.MATCHES_PIN, true, it)) }
        assertEquals(ChecksumState.entries.size, explained.toSet().size)
        assertEquals(R.string.explain_checksum_matched, checksumExplanation(v(SignerState.MATCHES_PIN, true, ChecksumState.MATCHED)))
        assertEquals(R.string.explain_checksum_none, checksumExplanation(v(SignerState.MATCHES_PIN, true, ChecksumState.NOT_PUBLISHED)))
    }
}
