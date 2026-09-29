package io.github.munzzyy.stamp.install

import io.github.munzzyy.stamp.core.apk.ApkInspector
import io.github.munzzyy.stamp.core.apk.FileSource
import io.github.munzzyy.stamp.core.apk.SignatureScheme
import io.github.munzzyy.stamp.core.apk.SignatureVerdict
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the gate makes of the two readings of a signature, on the parts of the bundle that the
 * device tests install. Android's reading is scripted here; Stamp's own is the real one.
 */
class SignerJudgeTest {
    private val fixtures = File("../core/src/test/resources/fixtures/apk")
    private val work: File = Files.createTempDirectory("signer-judge").toFile().apply { deleteOnExit() }
    private val parts = listOf("arm64_v8a", "x86_64", "armeabi_v7a", "xxhdpi", "mdpi", "en", "de").map { "split-config.$it.apk" }
    private val sdk = 36

    private fun bytes(name: String): ByteArray = File(fixtures, name).readBytes()

    private fun own(bytes: ByteArray): SignatureVerdict {
        val file = File.createTempFile("part", ".apk", work)
        try {
            file.writeBytes(bytes)
            return SignatureReader.OWN.read(file, sdk)
        } finally {
            file.delete()
        }
    }

    private fun claimed(bytes: ByteArray): List<String> {
        val file = File.createTempFile("claim", ".apk", work)
        try {
            file.writeBytes(bytes)
            return FileSource(file).use { ApkInspector.inspect(it) }.signersFor(sdk).map { it.sha256 }
        } finally {
            file.delete()
        }
    }

    /** The change of the device test: the time of the first entry, which no parser reads. */
    private fun changedAfterSigning(bytes: ByteArray): ByteArray = bytes.copyOf().also { it[LOCAL_HEADER_TIME] = (it[LOCAL_HEADER_TIME].toInt() xor 1).toByte() }

    private val base: List<String> = (own(bytes("split-base.apk")) as SignatureVerdict.Holds).certificates

    @Test
    fun theFixturesAreWhereTheTestLooksForThem() {
        assertTrue(fixtures.path, File(fixtures, "split-base.apk").isFile)
        assertEquals(1, base.size)
        assertEquals(base, claimed(bytes("split-base.apk")))
    }

    @Test
    fun aPartAndroidDidNotReadPassesWhenItsSignatureHoldsForTheSignerOfTheBase() {
        for (part in parts) {
            val verdict = own(bytes(part))
            assertTrue("$part: $verdict", verdict is SignatureVerdict.Holds)
            assertEquals(part, SignerFinding.ACCEPTED, SignerJudge.notReadByAndroid(base, verdict))
        }
    }

    @Test
    fun aPartChangedAfterSigningStillClaimsTheSignerOfTheBaseAndIsRefused() {
        for (part in parts) {
            val changed = changedAfterSigning(bytes(part))
            assertEquals("$part claims another signer, so the test proves nothing", base, claimed(changed))
            val verdict = own(changed)
            assertTrue("$part: $verdict", verdict is SignatureVerdict.DoesNotHold)
            assertEquals(part, SignerFinding.PART_UNPROVEN, SignerJudge.notReadByAndroid(base, verdict))
            assertEquals(part, SignerFinding.READ_DIFFERENTLY, SignerJudge.readByAndroid(base, verdict))
        }
    }

    @Test
    fun aPartSignedBySomeoneElseIsRefused() {
        val verdict = own(bytes("app-v2-otherkey.apk"))
        assertTrue("$verdict", verdict is SignatureVerdict.Holds)
        assertEquals(SignerFinding.PART_OTHER_SIGNER, SignerJudge.notReadByAndroid(base, verdict))
        assertEquals(SignerFinding.READ_DIFFERENTLY, SignerJudge.readByAndroid(base, verdict))
    }

    @Test
    fun aPartThatCannotBeVerifiedIsRefusedUnlessAndroidReadIt() {
        val verdict = SignatureVerdict.CannotVerify("a scheme that is not checked here")
        assertEquals(SignerFinding.PART_UNPROVEN, SignerJudge.notReadByAndroid(base, verdict))
        assertEquals(SignerFinding.ACCEPTED, SignerJudge.readByAndroid(base, verdict))
    }

    @Test
    fun whereAndroidReadTheFileTheOwnCheckCanOnlySpeakAgainstIt() {
        val same = SignatureVerdict.Holds(SignatureScheme.V3, base, emptyList())
        assertEquals(SignerFinding.ACCEPTED, SignerJudge.readByAndroid(base, same))
        assertEquals(SignerFinding.ACCEPTED, SignerJudge.readByAndroid(base + base, same))
        val more = SignatureVerdict.Holds(SignatureScheme.V2, base + "a".repeat(64), emptyList())
        assertEquals(SignerFinding.READ_DIFFERENTLY, SignerJudge.readByAndroid(base, more))
        assertEquals(SignerFinding.READ_DIFFERENTLY, SignerJudge.readByAndroid(base + "a".repeat(64), same))
        assertEquals(SignerFinding.READ_DIFFERENTLY, SignerJudge.readByAndroid(base, SignatureVerdict.DoesNotHold("content digest")))
    }

    @Test
    fun aVerdictWithoutCertificatesNeverPasses() {
        val empty = SignatureVerdict.Holds(SignatureScheme.V3, emptyList(), emptyList())
        assertEquals(SignerFinding.PART_OTHER_SIGNER, SignerJudge.notReadByAndroid(emptyList(), empty))
        assertEquals(SignerFinding.PART_OTHER_SIGNER, SignerJudge.notReadByAndroid(base, empty))
        assertEquals(SignerFinding.PART_OTHER_SIGNER, SignerJudge.notReadByAndroid(base, SignatureVerdict.Holds(SignatureScheme.V2, base + "a".repeat(64), emptyList())))
    }

    private companion object {
        const val LOCAL_HEADER_TIME = 10
    }
}
