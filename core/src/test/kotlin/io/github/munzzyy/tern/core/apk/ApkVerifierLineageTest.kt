package io.github.munzzyy.tern.core.apk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The proof of rotation sits inside the signed data, so the newest key vouches for all of it. The
 * links still have to be checked one by one: the newest key is the one that gains from a false one.
 */
class ApkVerifierLineageTest {
    private val zip = TestSigner.unsignedZip()
    private val first = TestKeys.first
    private val second = TestKeys.second
    private val third = TestKeys.third
    private val stranger = TestKeys.stranger
    private val three = listOf(first, second, third)

    private fun verify(signer: TestKey, vararg lineages: ByteArray, sdk: Int = 29): SignatureVerdict {
        val spec = TestSignerSpec(signer, attributes = lineages.map { TestSigner.ATTR_LINEAGE to it })
        return SigningFixtures.verify(TestSigner.sign(zip, v3 = listOf(spec)), sdk)
    }

    @Test
    fun aLineageOfThreeKeysHoldsLinkByLink() {
        assertHolds("three", verify(third, TestSigner.lineage(three)), SignatureScheme.V3, setOf(third.sha256), three.map { it.sha256 })
        assertHolds("two", verify(second, TestSigner.lineage(listOf(first, second))), SignatureScheme.V3, setOf(second.sha256), listOf(first.sha256, second.sha256))
        assertHolds("one", verify(first, TestSigner.lineage(listOf(first))), SignatureScheme.V3, setOf(first.sha256), listOf(first.sha256))
    }

    @Test
    fun aLineageWhoseMiddleSignatureIsWrongIsRefused() {
        assertRefused("the link to the second key", verify(third, TestSigner.lineage(three, wrongLink = 1)), "lineage")
        assertRefused("the link to the third key", verify(third, TestSigner.lineage(three, wrongLink = 2)), "lineage")
    }

    @Test
    fun aLinkSignedBySomeoneWhoIsNotTheKeyBeforeIsRefused() {
        assertRefused("a stranger vouches for the second key", verify(third, TestSigner.lineage(three, signedBy = mapOf(1 to stranger))), "lineage")
        assertRefused("the third key vouches for itself", verify(third, TestSigner.lineage(three, signedBy = mapOf(2 to third))), "lineage")
        assertRefused("the first key vouches for the third", verify(third, TestSigner.lineage(three, signedBy = mapOf(2 to first))), "lineage")
    }

    @Test
    fun aLineageThatClaimsAnAncestorItHasNoSignatureOfIsRefused() {
        val claimed = listOf(first, stranger)
        val forged = TestSigner.lineage(claimed, signedBy = mapOf(1 to stranger))
        assertRefused("signed by itself in place of the ancestor", verify(stranger, forged), "lineage")
    }

    @Test
    fun aLineageMustEndWithTheSigner() {
        assertRefused("ends one key early", verify(third, TestSigner.lineage(listOf(first, second))), "lineage")
        assertRefused("ends with the key before", verify(second, TestSigner.lineage(three)), "lineage")
        assertRefused("of another app", verify(stranger, TestSigner.lineage(three)), "lineage")
    }

    @Test
    fun aLineageThatNamesAKeyTwiceIsRefused() {
        assertRefused("first, second, first", verify(first, TestSigner.lineage(listOf(first, second, first))), "lineage")
    }

    @Test
    fun anEmptyLineageAndTwoLineagesAreRefused() {
        assertRefused("no key at all", verify(first, TestSigner.u32(1)), "lineage")
        assertRefused("not even a version", verify(first, ByteArray(0)), "")
        assertRefused("two of them", verify(third, TestSigner.lineage(three), TestSigner.lineage(three)), "lineage")
    }

    @Test
    fun theAlgorithmALinkWasSignedWithMustBeTheOneTheKeyBeforeAnnounced() {
        assertRefused("named otherwise", verify(third, TestSigner.lineage(three, namedAlgorithm = mapOf(1 to TestSigner.RSA_PKCS1_SHA512))), "lineage")
    }

    @Test
    fun aLineageCutOffInTheMiddleIsRefused() {
        val whole = TestSigner.lineage(three)
        val verdict = verify(third, whole.copyOf(whole.size - 9))
        assertRefused("cut", verdict, "")
    }

    @Test
    fun aV2SignerHasNoLineage() {
        val spec = TestSignerSpec(third, attributes = listOf(TestSigner.ATTR_LINEAGE to TestSigner.lineage(three, wrongLink = 1)))
        val verdict = SigningFixtures.verify(TestSigner.sign(zip, v2 = listOf(spec)), 29)
        assertHolds("the attribute means nothing in a v2 block", verdict, SignatureScheme.V2, setOf(third.sha256))
    }

    @Test
    fun theLineageOfAFileFromApksignerWithOneLinkChanged() {
        val surgery = ApkSurgery(SigningFixtures.bytes("v3-rotated.apk"))
        val signer = surgery.signers(TestSigner.V3).single()
        assertEquals(TestSigner.ATTR_LINEAGE.toLong(), surgery.apk.u32(signer.attributes.first + 4))
        val verdict = SigningFixtures.verify(surgery.flip(signer.attributes.last - 2), 29)
        assertRefused("changed after signing", verdict, "does not verify")
    }
}
