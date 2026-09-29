package io.github.munzzyy.stamp.core.apk

import java.security.NoSuchAlgorithmException
import java.security.Signature
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Files signed by the test's own signer, which gets one thing wrong at a time and signs the result
 * properly. A change made after signing breaks the signature before any other check is reached;
 * these files are the only way to see that the other checks are made.
 */
class ApkVerifierSignerTest {
    private val zip = TestSigner.unsignedZip()
    private val first = TestKeys.first
    private val second = TestKeys.second
    private val stranger = TestKeys.stranger

    private fun v2(vararg signers: TestSignerSpec) = SigningFixtures.verify(TestSigner.sign(zip, v2 = signers.toList()), 29)

    private fun v3(vararg signers: TestSignerSpec) = SigningFixtures.verify(TestSigner.sign(zip, v3 = signers.toList()), 29)

    private fun hasPss(): Boolean = try {
        Signature.getInstance("RSASSA-PSS")
        true
    } catch (_: NoSuchAlgorithmException) {
        false
    }

    @Test
    fun whatTheSignerOfTheTestsWritesHolds() {
        assertHolds("RSA, v2", v2(TestSignerSpec(first)), SignatureScheme.V2, setOf(first.sha256))
        assertHolds("RSA, v3", v3(TestSignerSpec(first)), SignatureScheme.V3, setOf(first.sha256))
        assertHolds("RSA with SHA-512", v3(TestSignerSpec(first, listOf(TestSigner.RSA_PKCS1_SHA512))), SignatureScheme.V3, setOf(first.sha256))
        assertHolds("ECDSA", v2(TestSignerSpec(second)), SignatureScheme.V2, setOf(second.sha256))
        assertHolds("ECDSA with SHA-512", v3(TestSignerSpec(TestKeys.curve384, listOf(TestSigner.ECDSA_SHA512))), SignatureScheme.V3, setOf(TestKeys.curve384.sha256))
        assertHolds("DSA", v2(TestSignerSpec(TestKeys.dsa)), SignatureScheme.V2, setOf(TestKeys.dsa.sha256))
        assertHolds("two algorithms", v3(TestSignerSpec(first, listOf(TestSigner.RSA_PKCS1_SHA256, TestSigner.RSA_PKCS1_SHA512))), SignatureScheme.V3, setOf(first.sha256))
    }

    @Test
    fun aFileOfSeveralChunksSignedByTheTestsHolds() {
        val large = TestSigner.unsignedZip(extra = 2 * 1024 * 1024 + 17)
        val signed = TestSigner.sign(large, v2 = listOf(TestSignerSpec(first, listOf(TestSigner.RSA_PKCS1_SHA256, TestSigner.RSA_PKCS1_SHA512))))
        assertHolds("three chunks of entries", SigningFixtures.verify(signed, 29), SignatureScheme.V2, setOf(first.sha256))
        assertRefused("a bit in the third chunk", SigningFixtures.verify(ApkSurgery(signed).flip(2 * 1024 * 1024 + 300), 29), "content digest")
    }

    @Test
    fun rsaPssHolds() {
        assumeTrue("this Java has no RSASSA-PSS", hasPss())
        assertHolds("SHA-256", v2(TestSignerSpec(first, listOf(TestSigner.RSA_PSS_SHA256))), SignatureScheme.V2, setOf(first.sha256))
        assertHolds("SHA-512", v3(TestSignerSpec(first, listOf(TestSigner.RSA_PSS_SHA512))), SignatureScheme.V3, setOf(first.sha256))
        assertRefused("one bit wrong", v3(TestSignerSpec(first, listOf(TestSigner.RSA_PSS_SHA256), brokenSignatures = setOf(TestSigner.RSA_PSS_SHA256))), "does not verify")
        val pkcs1 = TestSignerSpec(first, listOf(TestSigner.RSA_PKCS1_SHA256))
        val signed = ApkSurgery(TestSigner.sign(zip, v2 = listOf(pkcs1)))
        val signer = signed.signers(TestSigner.V2).single()
        val renamed = signed.apk.copyOf().also {
            TestSigner.u32(TestSigner.RSA_PSS_SHA256).copyInto(it, signer.firstSignature.first - 8)
        }
        assertTrue(SigningFixtures.verify(renamed, 29) is SignatureVerdict.DoesNotHold)
    }

    @Test
    fun aSignerWhoShowsTheCertificateOfSomeoneElseIsRefused() {
        val forged = TestSignerSpec(stranger, certificates = listOf(first.certificate))
        assertRefused("v2", v2(forged), "key of the certificate")
        assertRefused("v3", v3(forged), "key of the certificate")
        assertRefused("own certificate second", v3(TestSignerSpec(stranger, certificates = listOf(first.certificate, stranger.certificate))), "key of the certificate")
    }

    @Test
    fun aSignatureByAnotherKeyThanTheOneShownIsRefused() {
        assertRefused("v2", v2(TestSignerSpec(first, signedBy = stranger)), "does not verify")
        assertRefused("v3", v3(TestSignerSpec(first, signedBy = stranger)), "does not verify")
    }

    @Test
    fun aKeyOfAnotherKindThanTheAlgorithmNamesIsRefused() {
        val verdict = v2(TestSignerSpec(second, listOf(TestSigner.RSA_PKCS1_SHA256)))
        assertTrue("$verdict", verdict is SignatureVerdict.DoesNotHold)
    }

    @Test
    fun digestsAndSignaturesMustNameTheSameAlgorithmsInTheSameOrder() {
        val both = listOf(TestSigner.RSA_PKCS1_SHA256, TestSigner.RSA_PKCS1_SHA512)
        assertRefused("a digest more", v3(TestSignerSpec(first, listOf(TestSigner.RSA_PKCS1_SHA256), digestAlgorithms = both)), "different algorithms")
        assertRefused("a signature more", v3(TestSignerSpec(first, both, digestAlgorithms = listOf(TestSigner.RSA_PKCS1_SHA512))), "different algorithms")
        assertRefused("another order", v2(TestSignerSpec(first, both, digestAlgorithms = both.reversed())), "different algorithms")
        assertRefused("no digest", v2(TestSignerSpec(first, both, digestAlgorithms = emptyList())), "different algorithms")
    }

    @Test
    fun aDigestOfOtherContentUnderAValidSignatureIsRefused() {
        val both = listOf(TestSigner.RSA_PKCS1_SHA256, TestSigner.RSA_PKCS1_SHA512)
        assertRefused("v2", v2(TestSignerSpec(first, brokenDigests = setOf(TestSigner.RSA_PKCS1_SHA256))), "content digest")
        assertRefused("v3", v3(TestSignerSpec(second, brokenDigests = setOf(TestSigner.ECDSA_SHA256))), "content digest")
        assertRefused("the stronger of two", v3(TestSignerSpec(first, both, brokenDigests = setOf(TestSigner.RSA_PKCS1_SHA512))), "content digest")
        assertRefused("the stronger of two, listed first", v2(TestSignerSpec(first, both.reversed(), brokenDigests = setOf(TestSigner.RSA_PKCS1_SHA512))), "content digest")
    }

    /** The specification has the strongest signature decide, and apksigner accepts a file whose weaker one is wrong. */
    @Test
    fun ofTwoSignaturesTheStrongerOneDecides() {
        val both = listOf(TestSigner.RSA_PKCS1_SHA256, TestSigner.RSA_PKCS1_SHA512)
        assertRefused("the stronger one wrong", v3(TestSignerSpec(first, both, brokenSignatures = setOf(TestSigner.RSA_PKCS1_SHA512))), "does not verify")
        assertRefused("the stronger one wrong, listed first", v2(TestSignerSpec(first, both.reversed(), brokenSignatures = setOf(TestSigner.RSA_PKCS1_SHA512))), "does not verify")
        assertHolds("the weaker one wrong", v3(TestSignerSpec(first, both, brokenSignatures = setOf(TestSigner.RSA_PKCS1_SHA256))), SignatureScheme.V3, setOf(first.sha256))
        assertHolds("the digest of the weaker one wrong", v3(TestSignerSpec(first, both, brokenDigests = setOf(TestSigner.RSA_PKCS1_SHA256))), SignatureScheme.V3, setOf(first.sha256))
    }

    @Test
    fun ofTwoSignaturesOfTheSameStrengthTheFirstOneDecides() {
        assumeTrue("this Java has no RSASSA-PSS", hasPss())
        val pssFirst = listOf(TestSigner.RSA_PSS_SHA256, TestSigner.RSA_PKCS1_SHA256)
        assertRefused("the first one wrong", v2(TestSignerSpec(first, pssFirst, brokenSignatures = setOf(TestSigner.RSA_PSS_SHA256))), "does not verify")
        assertHolds("the second one wrong", v2(TestSignerSpec(first, pssFirst, brokenSignatures = setOf(TestSigner.RSA_PKCS1_SHA256))), SignatureScheme.V2, setOf(first.sha256))
    }

    @Test
    fun anAlgorithmNobodyKnowsIsPassedOverNextToOneThatIsKnown() {
        val mixed = listOf(TestSigner.UNKNOWN, TestSigner.RSA_PKCS1_SHA256)
        assertHolds("unknown first", v2(TestSignerSpec(first, mixed)), SignatureScheme.V2, setOf(first.sha256))
        assertHolds("unknown and broken", v3(TestSignerSpec(first, mixed, brokenSignatures = setOf(TestSigner.UNKNOWN))), SignatureScheme.V3, setOf(first.sha256))
        assertRefused("its digest still has to be listed", v3(TestSignerSpec(first, mixed, digestAlgorithms = listOf(TestSigner.RSA_PKCS1_SHA256))), "different algorithms")
    }

    @Test
    fun aSignerWithoutAnAlgorithmThatIsCheckedHereGetsNoAnswer() {
        assertNotChecked("unknown only", v2(TestSignerSpec(first, listOf(TestSigner.UNKNOWN))), "algorithm")
        assertNotChecked("ML-DSA only", v3(TestSignerSpec(first, listOf(TestSigner.ML_DSA))), "algorithm")
        assertNotChecked("verity only", v3(TestSignerSpec(first, listOf(TestSigner.VERITY_RSA))), "verity")
    }

    @Test
    fun whereAndroidGoesByAVerityDigestThereIsNoAnswer() {
        assertNotChecked("verity is stronger than SHA-256", v3(TestSignerSpec(first, listOf(TestSigner.RSA_PKCS1_SHA256, TestSigner.VERITY_RSA))), "verity")
        assertNotChecked("in either order", v2(TestSignerSpec(second, listOf(TestSigner.VERITY_ECDSA, TestSigner.ECDSA_SHA256))), "verity")
        assertHolds(
            "SHA-512 is stronger than verity",
            v3(TestSignerSpec(first, listOf(TestSigner.VERITY_RSA, TestSigner.RSA_PKCS1_SHA512))),
            SignatureScheme.V3,
            setOf(first.sha256),
        )
    }

    @Test
    fun aSignerWithoutSignaturesOrCertificatesIsRefused() {
        assertRefused("no signature", v2(TestSignerSpec(first, emptyList())), "no signature")
        assertRefused("no certificate", v3(TestSignerSpec(first, certificates = emptyList())), "no certificate")
        val verdict = v3(TestSignerSpec(first, certificates = listOf(first.certificate, ByteArray(40) { 3 })))
        assertRefused("a second certificate that is none", verdict, "certificate 2")
    }

    @Test
    fun everySignerOfAV2BlockMustHold() {
        assertHolds("two signers", v2(TestSignerSpec(first), TestSignerSpec(second)), SignatureScheme.V2, setOf(first.sha256, second.sha256))
        assertRefused("the second one broken", v2(TestSignerSpec(first), TestSignerSpec(second, brokenSignatures = setOf(second.algorithm))), "does not verify")
        assertRefused("the first one broken", v2(TestSignerSpec(first, brokenDigests = setOf(first.algorithm)), TestSignerSpec(second)), "content digest")
        assertRefused("the second one forged", v2(TestSignerSpec(first), TestSignerSpec(stranger, certificates = listOf(second.certificate))), "key of the certificate")
    }

    @Test
    fun aBlockWithoutSignersOrWithTooManyIsRefused() {
        val none = TestSigner.sign(zip, others = listOf(TestSigner.V2 to TestSigner.prefixed(ByteArray(0))))
        assertTrue(SigningFixtures.verify(none, 29) is SignatureVerdict.DoesNotHold)
        val eleven = v2(*Array(11) { TestSignerSpec(second) })
        assertRefused("eleven signers", eleven, "signers")
        val noneForV3 = TestSigner.sign(zip, others = listOf(TestSigner.V3 to TestSigner.prefixed(ByteArray(0))))
        assertTrue(SigningFixtures.verify(noneForV3, 29) is SignatureVerdict.DoesNotHold)
    }

    @Test
    fun theSameSignerTwiceInAV2BlockIsOneCertificate() {
        assertHolds("twice", v2(TestSignerSpec(first), TestSignerSpec(first)), SignatureScheme.V2, setOf(first.sha256))
    }

    @Test
    fun aLengthThatRunsPastItsFieldIsRefused() {
        val signed = ApkSurgery(TestSigner.sign(zip, v2 = listOf(TestSignerSpec(first))))
        val signer = signed.signers(TestSigner.V2).single()
        for (at in listOf(signer.signedData.first - 4, signer.firstSignature.first - 4, signer.publicKey.first - 4, signer.firstCertificate.first - 4)) {
            val grown = signed.apk.copyOf().also { it[at + 2] = 0x7f }
            val verdict = SigningFixtures.verify(grown, 29)
            assertTrue("length at $at: $verdict", verdict is SignatureVerdict.DoesNotHold)
        }
    }
}
