package io.github.munzzyy.stamp.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files signed by apksigner: the verifier has to say what apksigner said when they were made. */
class ApkVerifierFixturesTest {
    private val mib = 1024 * 1024

    @Test
    fun everyFixtureHoldsForTheCertificateApksignerNames() {
        assertTrue(SigningFixtures.names.size >= 17)
        var held = 0
        for (name in SigningFixtures.names) {
            val bytes = SigningFixtures.bytes(name)
            for (sdk in SigningFixtures.levels) {
                val expected = SigningFixtures.certificatesAt(name, sdk)
                val verdict = SigningFixtures.verify(bytes, sdk)
                if (expected == null) {
                    assertTrue("$name on $sdk: apksigner refuses it, got $verdict", verdict is SignatureVerdict.DoesNotHold)
                } else {
                    assertTrue("$name on $sdk: $verdict", verdict is SignatureVerdict.Holds)
                    assertEquals("$name on $sdk", expected, (verdict as SignatureVerdict.Holds).certificates.toSet())
                    held++
                }
            }
        }
        assertTrue(held > 90)
    }

    @Test
    fun theSchemeIsTheNewestOneTheDeviceKnowsAndTheFileCarries() {
        val cases = listOf(
            Triple("v2-rsa-sha256.apk", 36, SignatureScheme.V2),
            Triple("v2-dsa-sha256.apk", 29, SignatureScheme.V2),
            Triple("v3-rsa-sha256.apk", 29, SignatureScheme.V3),
            Triple("v2v3-ec-sha256.apk", 27, SignatureScheme.V2),
            Triple("v2v3-ec-sha256.apk", 28, SignatureScheme.V3),
            Triple("v2v3-rsa-sha512.apk", 36, SignatureScheme.V3),
            Triple("v2v3-ec-sha512.apk", 29, SignatureScheme.V3),
            Triple("v1v2v3-rsa-sha256.apk", 29, SignatureScheme.V3),
            Triple("v1-rsa-target29.apk", 36, SignatureScheme.V1),
            Triple("v31-rotated-twice.apk", 32, SignatureScheme.V3),
            Triple("v31-rotated-twice.apk", 33, SignatureScheme.V31),
            Triple("v2v3-rotated.apk", 27, SignatureScheme.V2),
            Triple("v2v3-rotated.apk", 28, SignatureScheme.V3),
            Triple("v1-two-signers.apk", 29, SignatureScheme.V1),
            Triple("v2-two-signers.apk", 36, SignatureScheme.V2),
        )
        for ((name, sdk, scheme) in cases) {
            val verdict = SigningFixtures.verify(SigningFixtures.bytes(name), sdk)
            assertTrue("$name on $sdk: $verdict", verdict is SignatureVerdict.Holds)
            assertEquals("$name on $sdk", scheme, (verdict as SignatureVerdict.Holds).scheme)
        }
    }

    @Test
    fun twoSignersAreTwoCertificates() {
        for (name in listOf("v1-two-signers.apk", "v2-two-signers.apk")) {
            val expected = SigningFixtures.certificatesAt(name, 29)!!
            assertEquals(name, 2, expected.size)
            val verdict = SigningFixtures.verify(SigningFixtures.bytes(name), 29)
            assertTrue("$name: $verdict", verdict is SignatureVerdict.Holds)
            assertEquals(name, expected, (verdict as SignatureVerdict.Holds).certificates.toSet())
            assertEquals(name, 2, verdict.certificates.size)
        }
    }

    @Test
    fun aRotatedKeyComesWithTheLineageApksignerPrints() {
        val two = SigningFixtures.lineage("v3-rotated.apk")
        assertEquals(2, two.size)
        assertHolds("v3-rotated", SigningFixtures.verify(SigningFixtures.bytes("v3-rotated.apk"), 29), SignatureScheme.V3, setOf(two.last()), two)
        assertHolds("v2v3-rotated", SigningFixtures.verify(SigningFixtures.bytes("v2v3-rotated.apk"), 36), SignatureScheme.V3, setOf(two.last()), two)
        assertHolds("v2v3-rotated on 27", SigningFixtures.verify(SigningFixtures.bytes("v2v3-rotated.apk"), 27), SignatureScheme.V2, setOf(two.first()))

        val three = SigningFixtures.lineage("v31-rotated-twice.apk")
        assertEquals(3, three.size)
        assertEquals(3, three.toSet().size)
        val twice = SigningFixtures.bytes("v31-rotated-twice.apk")
        assertHolds("rotated twice on 36", SigningFixtures.verify(twice, 36), SignatureScheme.V31, setOf(three.last()), three)
        assertHolds("rotated twice on 32", SigningFixtures.verify(twice, 32), SignatureScheme.V3, setOf(three.first()))
    }

    @Test
    fun theFixturesOfTheInspectorHoldForTheCertificateItReads() {
        for (name in listOf("app-v1.apk", "app-v2.apk", "app-v2-otherkey.apk", "app-v2-only.apk", "split-base.apk", "split-config.x86_64.apk", "split-config.de.apk")) {
            val expected = ApkFixtures.expected.obj(name)!!.array("certificates")!!.strings().toSet()
            val verdict = SigningFixtures.verify(ApkFixtures.bytes(name), 36)
            assertTrue("$name: $verdict", verdict is SignatureVerdict.Holds)
            assertEquals(name, expected, (verdict as SignatureVerdict.Holds).certificates.toSet())
        }
        val lineage = ApkFixtures.expected.obj("app-rotated.apk")!!.array("lineage")!!.strings()
        assertHolds("app-rotated on 36", SigningFixtures.verify(ApkFixtures.bytes("app-rotated.apk"), 36), SignatureScheme.V31, setOf(lineage.last()), lineage)
        assertHolds("app-rotated on 30", SigningFixtures.verify(ApkFixtures.bytes("app-rotated.apk"), 30), SignatureScheme.V3, setOf(lineage.first()))
    }

    @Test
    fun aFileOfTwoChunksHoldsAndEveryChunkCounts() {
        val bytes = SigningFixtures.bytes("big-two-chunks.apk")
        val surgery = ApkSurgery(bytes)
        assertTrue(surgery.blockAt > mib && surgery.blockAt < 2 * mib)
        assertTrue(SigningFixtures.verify(bytes, 29) is SignatureVerdict.Holds)
        for (at in listOf(mib / 2, mib - 1, mib, mib + 1, surgery.blockAt - 1)) {
            assertRefused("bit at $at", SigningFixtures.verify(surgery.flip(at), 29), "content digest")
        }
    }

    @Test
    fun aFileWhoseEntriesEndOnAChunkBoundaryHolds() {
        val bytes = SigningFixtures.bytes("big-one-chunk-exactly.apk")
        val surgery = ApkSurgery(bytes)
        assertEquals(mib, surgery.blockAt)
        assertTrue(SigningFixtures.verify(bytes, 29) is SignatureVerdict.Holds)
        for (at in listOf(0, mib - 1)) {
            assertRefused("bit at $at", SigningFixtures.verify(surgery.flip(at, 0x80), 29), "content digest")
        }
    }

    @Test
    fun aFileOverTheLimitIsNotLookedAt() {
        val file = SigningFixtures.file(SigningFixtures.bytes("v2-rsa-sha256.apk"))
        assertTrue(ApkVerifier.verify(file, 29, file.length()) is SignatureVerdict.Holds)
        assertNotChecked("one byte over", ApkVerifier.verify(file, 29, file.length() - 1), "limit")
    }
}
