package io.github.munzzyy.tern.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which block decides on which version of Android, and what a block that was taken out leaves behind. */
class ApkVerifierSchemeTest {
    private val zip = TestSigner.unsignedZip()
    private val first = TestKeys.first
    private val second = TestKeys.second

    private fun rotated(minSdk: Int, vararg more: Pair<Int, ByteArray>) = TestSignerSpec(
        second,
        minSdk = minSdk,
        attributes = listOf(TestSigner.ATTR_LINEAGE to TestSigner.lineage(listOf(first, second))) + more,
    )

    @Test
    fun aV3BlockDecidesEvenWhereItsSignerIsForOtherVersions() {
        val signed = TestSigner.sign(zip, v2 = listOf(TestSignerSpec(first)), v3 = listOf(TestSignerSpec(first, minSdk = 24, maxSdk = 29)))
        assertHolds("inside the range", SigningFixtures.verify(signed, 29), SignatureScheme.V3, setOf(first.sha256))
        assertRefused("past the range", SigningFixtures.verify(signed, 30), "no signer")
        assertHolds("before Android knew v3", SigningFixtures.verify(signed, 27), SignatureScheme.V2, setOf(first.sha256))
    }

    @Test
    fun ofTwoSignersInAV3BlockTheOneForTheDeviceCounts() {
        val signed = TestSigner.sign(
            zip,
            v3 = listOf(TestSignerSpec(first, minSdk = 24, maxSdk = 30), rotated(31)),
        )
        assertHolds("30", SigningFixtures.verify(signed, 30), SignatureScheme.V3, setOf(first.sha256))
        assertHolds("31", SigningFixtures.verify(signed, 31), SignatureScheme.V3, setOf(second.sha256), listOf(first.sha256, second.sha256))
    }

    @Test
    fun twoSignersForTheSameDeviceAreRefused() {
        val signed = TestSigner.sign(zip, v3 = listOf(TestSignerSpec(first, maxSdk = 30), TestSignerSpec(second, minSdk = 30)))
        assertHolds("29", SigningFixtures.verify(signed, 29), SignatureScheme.V3, setOf(first.sha256))
        assertRefused("30", SigningFixtures.verify(signed, 30), "more than one signer")
    }

    @Test
    fun aSignerForOtherVersionsIsNotLookedAt() {
        val signed = TestSigner.sign(
            zip,
            v3 = listOf(TestSignerSpec(first, maxSdk = 30), TestSignerSpec(second, minSdk = 31, brokenSignatures = setOf(second.algorithm))),
        )
        assertHolds("30", SigningFixtures.verify(signed, 30), SignatureScheme.V3, setOf(first.sha256))
        assertRefused("31", SigningFixtures.verify(signed, 31), "does not verify")
    }

    @Test
    fun aV31BlockForLaterVersionsLeavesTheV3BlockToDecide() {
        val signed = TestSigner.sign(
            zip,
            v3 = listOf(TestSignerSpec(first, maxSdk = 34, attributes = listOf(TestSigner.attribute(TestSigner.ATTR_ROTATION_MIN_SDK, 35)))),
            v31 = listOf(rotated(35)),
        )
        assertHolds("34", SigningFixtures.verify(signed, 34), SignatureScheme.V3, setOf(first.sha256))
        assertHolds("35", SigningFixtures.verify(signed, 35), SignatureScheme.V31, setOf(second.sha256), listOf(first.sha256, second.sha256))
        assertHolds("32 does not know v3.1", SigningFixtures.verify(signed, 32), SignatureScheme.V3, setOf(first.sha256))
    }

    @Test
    fun aV31BlockIsNotReadBeforeAndroid13() {
        val signed = TestSigner.sign(zip, v3 = listOf(TestSignerSpec(first)), v31 = listOf(rotated(24)))
        assertHolds("32", SigningFixtures.verify(signed, 32), SignatureScheme.V3, setOf(first.sha256))
        assertHolds("33", SigningFixtures.verify(signed, 33), SignatureScheme.V31, setOf(second.sha256), listOf(first.sha256, second.sha256))
    }

    @Test
    fun aV31BlockThatDoesNotHoldIsNotPassedOver() {
        val signed = TestSigner.sign(
            zip,
            v3 = listOf(TestSignerSpec(first)),
            v31 = listOf(rotated(33).copy(brokenSignatures = setOf(second.algorithm))),
        )
        assertRefused("33", SigningFixtures.verify(signed, 33), "does not verify")
        assertHolds("32", SigningFixtures.verify(signed, 32), SignatureScheme.V3, setOf(first.sha256))
    }

    @Test
    fun theV31BlockTakenOutOfARotatedFile() {
        val surgery = ApkSurgery(SigningFixtures.bytes("v31-rotated-twice.apk"))
        val stripped = surgery.withPairs { it != TestSigner.V31 }
        assertEquals(surgery.pairs.size - 1, ApkSurgery(stripped).pairs.size)
        assertRefused("36", SigningFixtures.verify(stripped, 36), "no signer")
        assertTrue(SigningFixtures.verify(stripped, 32) is SignatureVerdict.Holds)
    }

    @Test
    fun aV3SignerThatNamesAV31BlockIsRefusedWithoutOne() {
        val names34 = TestSignerSpec(first, attributes = listOf(TestSigner.attribute(TestSigner.ATTR_ROTATION_MIN_SDK, 34)))
        val alone = TestSigner.sign(zip, v3 = listOf(names34))
        assertRefused("no v3.1 block", SigningFixtures.verify(alone, 33), "v3.1")
        assertHolds("Android 12 does not know the attribute", SigningFixtures.verify(alone, 32), SignatureScheme.V3, setOf(first.sha256))

        val other = TestSigner.sign(zip, v3 = listOf(names34), v31 = listOf(rotated(35)))
        assertRefused("a v3.1 block for another version", SigningFixtures.verify(other, 33), "v3.1")

        val right = TestSigner.sign(zip, v3 = listOf(names34), v31 = listOf(rotated(34)))
        assertHolds("the block it names", SigningFixtures.verify(right, 33), SignatureScheme.V3, setOf(first.sha256))
    }

    @Test
    fun theV3BlockTakenOutOfAFileSignedWithBoth() {
        for (name in listOf("v2v3-ec-sha256.apk", "v2v3-rsa-sha512.apk", "v1v2v3-rsa-sha256.apk")) {
            val surgery = ApkSurgery(SigningFixtures.bytes(name))
            val stripped = surgery.withPairs { it != TestSigner.V3 }
            assertEquals(name, surgery.pairs.size - 1, ApkSurgery(stripped).pairs.size)
            assertRefused("$name on 29", SigningFixtures.verify(stripped, 29), "v3 signature")
            assertHolds("$name on 27", SigningFixtures.verify(stripped, 27), SignatureScheme.V2, SigningFixtures.certificatesAt(name, 27)!!)
        }
    }

    @Test
    fun aV2SignerThatNamesNoNewerSchemeHoldsAlone() {
        val signed = TestSigner.sign(zip, v2 = listOf(TestSignerSpec(first, attributes = listOf(TestSigner.attribute(TestSigner.ATTR_STRIPPING, 2)))))
        assertHolds("29", SigningFixtures.verify(signed, 29), SignatureScheme.V2, setOf(first.sha256))
    }

    @Test
    fun thePlatformRangeOutsideTheSignedDataMustBeTheOneInside() {
        val surgery = ApkSurgery(SigningFixtures.bytes("v3-rsa-sha256.apk"))
        val signer = surgery.signers(TestSigner.V3).single()
        val from = surgery.apk.u32(signer.minSdk!!).toInt()
        assertTrue(from in 24..28)
        assertEquals(from.toLong(), surgery.apk.u32(signer.signedMinSdk!!))
        assertEquals(0x7fffffffL, surgery.apk.u32(signer.minSdk + 4))
        assertEquals(0x7fffffffL, surgery.apk.u32(signer.signedMinSdk + 4))

        val lower = surgery.apk.copyOf().also { TestSigner.u32(from - 1).copyInto(it, signer.minSdk) }
        assertRefused("first version outside lowered", SigningFixtures.verify(lower, 29), "platform range")
        val shorter = surgery.apk.copyOf().also { TestSigner.u32(40).copyInto(it, signer.minSdk + 4) }
        assertRefused("last version outside lowered", SigningFixtures.verify(shorter, 29), "platform range")

        val signed = TestSigner.sign(zip, v3 = listOf(TestSignerSpec(first, minSdk = 24, signedMinSdk = 31)))
        assertRefused("signed for later versions only", SigningFixtures.verify(signed, 29), "platform range")
    }

    @Test
    fun aV32BlockIsLeftToAndroid17() {
        val hybrid = TestSigner.V32 to TestSigner.prefixed(ByteArray(0))
        val signed = TestSigner.sign(zip, v2 = listOf(TestSignerSpec(first)), v3 = listOf(TestSignerSpec(first)), others = listOf(hybrid))
        assertNotChecked("37", SigningFixtures.verify(signed, 37), "v3.2")
        assertHolds("36", SigningFixtures.verify(signed, 36), SignatureScheme.V3, setOf(first.sha256))
    }

    @Test
    fun aSignerThatNamesAV32BlockIsRefusedWithoutOneOnAndroid17() {
        for (attribute in listOf(TestSigner.ATTR_HYBRID_MIN_SDK, TestSigner.ATTR_HYBRID_MAX_SDK)) {
            val signed = TestSigner.sign(zip, v3 = listOf(TestSignerSpec(first, attributes = listOf(TestSigner.attribute(attribute, 37)))))
            assertRefused("37", SigningFixtures.verify(signed, 37), "v3.2")
            assertHolds("36", SigningFixtures.verify(signed, 36), SignatureScheme.V3, setOf(first.sha256))
        }
    }

    @Test
    fun aRotationMeantForAPreviewOfAndroidCannotBeJudgedOnThatVersion() {
        val signed = TestSigner.sign(
            zip,
            v3 = listOf(TestSignerSpec(first, maxSdk = 33, attributes = listOf(TestSigner.attribute(TestSigner.ATTR_ROTATION_MIN_SDK, 34)))),
            v31 = listOf(rotated(34, TestSigner.ATTR_DEV_RELEASE to ByteArray(0))),
        )
        assertNotChecked("34", SigningFixtures.verify(signed, 34), "preview")
        assertHolds("35", SigningFixtures.verify(signed, 35), SignatureScheme.V31, setOf(second.sha256), listOf(first.sha256, second.sha256))
        assertHolds("33", SigningFixtures.verify(signed, 33), SignatureScheme.V3, setOf(first.sha256))
    }

    @Test
    fun aSchemeThatAppearsTwiceIsRefused() {
        val surgery = ApkSurgery(SigningFixtures.bytes("v2-rsa-sha256.apk"))
        val twice = surgery.withBlock(TestSigner.block(listOf(TestSigner.V2 to surgery.value(TestSigner.V2), TestSigner.V2 to surgery.value(TestSigner.V2))))
        assertRefused("v2 twice", SigningFixtures.verify(twice, 29), "twice")
    }

    @Test
    fun aBlockWhoseTwoSizesDifferIsRefused() {
        val surgery = ApkSurgery(SigningFixtures.bytes("v2-rsa-sha256.apk"))
        val verdict = SigningFixtures.verify(surgery.flip(surgery.blockAt), 29)
        assertTrue("$verdict", verdict is SignatureVerdict.DoesNotHold)
    }

    @Test
    fun aDirectoryThatDoesNotEndWhereTheEndRecordBeginsIsRefused() {
        val surgery = ApkSurgery(SigningFixtures.bytes("v2-rsa-sha256.apk"))
        val gap = surgery.apk.copyOfRange(0, surgery.eocdAt) + ByteArray(8) + surgery.apk.copyOfRange(surgery.eocdAt, surgery.apk.size)
        assertEquals(ZipIndex.open(BytesSource(surgery.apk)).entries, ZipIndex.open(BytesSource(gap)).entries)
        assertRefused("eight bytes between them", SigningFixtures.verify(gap, 29), "end record")
    }

    @Test
    fun aZip64FileIsLeftAlone() {
        assertNotChecked("zip64", SigningFixtures.verify(ApkFixtures.bytes("app-zip64.apk"), 29), "ZIP64")
    }

    @Test
    fun whatIsNotAZipFileIsRefused() {
        val verdict = SigningFixtures.verify(ByteArray(4096) { 7 }, 29)
        assertTrue("$verdict", verdict is SignatureVerdict.DoesNotHold)
        val empty = SigningFixtures.verify(ByteArray(0), 29)
        assertTrue("$empty", empty is SignatureVerdict.DoesNotHold)
    }
}
