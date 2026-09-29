package io.github.munzzyy.stamp.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files signed by apksigner and changed afterwards, one place at a time. Each one has to be refused. */
class ApkVerifierTamperTest {
    private class Case(val name: String, val sdk: Int, val pair: Int) {
        val bytes = SigningFixtures.bytes(name)
        val surgery = ApkSurgery(bytes)
        val signer = surgery.signers(pair).last()
        val index = ZipIndex.open(BytesSource(bytes))

        fun verdictAfter(at: Int, mask: Int = 1) = SigningFixtures.verify(surgery.flip(at, mask), sdk)

        override fun toString() = "$name on $sdk"
    }

    private val cases = listOf(
        Case("v2-rsa-sha256.apk", 29, TestSigner.V2),
        Case("v2-dsa-sha256.apk", 29, TestSigner.V2),
        Case("v3-rsa-sha256.apk", 29, TestSigner.V3),
        Case("v2v3-ec-sha256.apk", 27, TestSigner.V2),
        Case("v2v3-ec-sha512.apk", 36, TestSigner.V3),
        Case("v2v3-rsa-sha512.apk", 33, TestSigner.V3),
        Case("v31-rotated-twice.apk", 36, TestSigner.V31),
        Case("v31-rotated-twice.apk", 32, TestSigner.V3),
    )

    @Test
    fun everyFileHoldsBeforeItIsChanged() {
        for (case in cases) assertTrue("$case", SigningFixtures.verify(case.bytes, case.sdk) is SignatureVerdict.Holds)
    }

    @Test
    fun oneBitInTheDataOfAnEntry() {
        for (case in cases) {
            val entry = case.index.entries.first { it.compressedSize > 8 }
            val at = case.index.dataOffset(entry) + entry.compressedSize / 2
            assertRefused("$case, ${entry.name}", case.verdictAfter(at.toInt()), "content digest")
        }
    }

    @Test
    fun oneBitInALocalHeader() {
        for (case in cases) {
            val entry = case.index.entries.last()
            assertRefused("$case, time of ${entry.name}", case.verdictAfter(entry.localHeaderOffset.toInt() + LOCAL_TIME), "content digest")
        }
    }

    @Test
    fun oneBitInTheCentralDirectory() {
        for (case in cases) {
            assertRefused("$case, time of the first entry", case.verdictAfter(case.surgery.directoryAt + CENTRAL_TIME), "content digest")
            assertRefused("$case, last byte", case.verdictAfter(case.surgery.eocdAt - 1, 0x20), "content digest")
        }
    }

    @Test
    fun oneBitInTheEndOfTheCentralDirectory() {
        for (case in cases) {
            val changed = case.surgery.flip(case.surgery.eocdAt + EOCD_ENTRIES_ON_THIS_DISK)
            assertEquals("the change has to leave the file readable", case.index.entries.size, ZipIndex.open(BytesSource(changed)).entries.size)
            assertRefused("$case", SigningFixtures.verify(changed, case.sdk), "content digest")
        }
    }

    @Test
    fun oneBitInTheSignedData() {
        for (case in cases) {
            assertRefused("$case, digest", case.verdictAfter(case.surgery.middle(case.signer.firstDigest)), "does not verify")
            assertRefused("$case, first byte", case.verdictAfter(case.signer.signedData.first), "does not verify")
            assertRefused("$case, last byte", case.verdictAfter(case.signer.signedData.last, 0x80), "does not verify")
        }
    }

    @Test
    fun oneBitInTheSignature() {
        for (case in cases) {
            for (at in listOf(case.signer.firstSignature.first, case.surgery.middle(case.signer.firstSignature), case.signer.firstSignature.last)) {
                assertRefused("$case, signature byte $at", case.verdictAfter(at), "does not verify")
            }
        }
    }

    @Test
    fun oneBitInTheCertificate() {
        for (case in cases) {
            assertRefused("$case", case.verdictAfter(case.surgery.middle(case.signer.firstCertificate)), "does not verify")
            assertRefused("$case, last byte", case.verdictAfter(case.signer.firstCertificate.last), "does not verify")
        }
    }

    @Test
    fun oneBitInThePublicKey() {
        for (case in cases) {
            for (at in listOf(case.surgery.middle(case.signer.publicKey), case.signer.publicKey.last)) {
                val verdict = case.verdictAfter(at)
                assertTrue("$case, key byte $at: $verdict", verdict is SignatureVerdict.DoesNotHold)
            }
        }
    }

    @Test
    fun theSigningBlockOfAnotherFileMovedIn() {
        val v2 = ApkSurgery(SigningFixtures.bytes("v2-rsa-sha256.apk"))
        val larger = ApkSurgery(SigningFixtures.bytes("big-two-chunks.apk"))
        assertEquals("both are signed with the same key", SigningFixtures.certificatesAt("v2-rsa-sha256.apk", 29), SigningFixtures.certificatesAt("big-two-chunks.apk", 29))
        assertRefused("v2 block of a smaller file", SigningFixtures.verify(larger.withBlock(v2.block()), 29), "content digest")

        val v3 = ApkSurgery(SigningFixtures.bytes("v3-rsa-sha256.apk"))
        val other = ApkSurgery(SigningFixtures.bytes("v2v3-ec-sha256.apk"))
        val moved = other.withBlock(v3.block())
        assertEquals(1, ApkSurgery(moved).signers(TestSigner.V3).size)
        assertRefused("v3 block of a file with another manifest", SigningFixtures.verify(moved, 29), "content digest")
    }

    @Test
    fun theSameBlockPutBackIsTheSameFile() {
        for (case in cases) {
            assertTrue("$case: moving a block changes more than the block", case.surgery.withBlock(case.surgery.block()).contentEquals(case.bytes))
            assertTrue("$case: writing the pairs again changes them", case.surgery.withPairs { true }.contentEquals(case.bytes))
        }
    }

    @Test
    fun theFileCutShortOrGrown() {
        for (case in cases) {
            val shorter = SigningFixtures.verify(case.bytes.copyOf(case.bytes.size - 1), case.sdk)
            assertTrue("$case, cut: $shorter", shorter is SignatureVerdict.DoesNotHold)
            val longer = SigningFixtures.verify(case.bytes + byteArrayOf(0), case.sdk)
            assertTrue("$case, grown: $longer", longer is SignatureVerdict.DoesNotHold)
            val comment = (case.bytes + "note".toByteArray()).also { ApkFixtures.le(4, 2).copyInto(it, case.surgery.eocdAt + 20) }
            assertRefused("$case, a zip comment added", SigningFixtures.verify(comment, case.sdk), "content digest")
        }
    }

    private companion object {
        const val LOCAL_TIME = 10
        const val CENTRAL_TIME = 12
        const val EOCD_ENTRIES_ON_THIS_DISK = 8
    }
}
