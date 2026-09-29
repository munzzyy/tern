package io.github.munzzyy.stamp.enginetest

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.core.apk.ApkInspector
import io.github.munzzyy.stamp.core.apk.FileSource
import io.github.munzzyy.stamp.core.apk.SignatureScheme
import io.github.munzzyy.stamp.core.apk.SignatureVerdict
import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.DeviceProfile
import io.github.munzzyy.stamp.core.verify.Fingerprints
import io.github.munzzyy.stamp.engine.ProblemKind
import io.github.munzzyy.stamp.engine.real.Texts
import io.github.munzzyy.stamp.install.AndroidReading
import io.github.munzzyy.stamp.install.ArchiveReader
import io.github.munzzyy.stamp.install.GateRequest
import io.github.munzzyy.stamp.install.InstallGate
import io.github.munzzyy.stamp.install.SignatureReader
import io.github.munzzyy.stamp.install.StepFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android reads a split by itself on some versions and not on others. The gate has to hold every
 * part of a bundle to the base's signer either way, so the reader is scripted here. Where Android
 * gives no reading of a part, the gate goes by its own check of the signature, never by what the
 * part claims.
 */
@RunWith(AndroidJUnit4::class)
class BundleSignerTest {
    private val work = File(targetContext.cacheDir, "bundle-signer-test")
    private val bundle = File(work, "app.xapk")
    private val device = DeviceProfile(listOf("x86_64", "arm64-v8a"), sdk = 36, densityDpi = 420, languages = listOf("en"))

    @Before
    fun prepare() {
        work.deleteRecursively()
        work.mkdirs()
        bundle.writeBytes(asset("apk/bundle.xapk"))
    }

    @After
    fun clean() {
        work.deleteRecursively()
    }

    /** Answers like Android would, with the signer this test chooses for the base and for the splits. */
    private class Scripted(val baseSigner: String, val splitSigner: String?) : ArchiveReader {
        val asked = ArrayList<String>()

        override fun read(file: File): AndroidReading? {
            val ours = FileSource(file).use { ApkInspector.inspect(it) }
            val split = ours.manifest.split
            asked.add(split ?: "base")
            val signer = if (split == null) baseSigner else splitSigner ?: return null
            return AndroidReading(
                packageName = ours.manifest.packageName,
                versionCode = ours.manifest.versionCode,
                versionName = ours.manifest.versionName,
                signers = listOf(signer),
                lineage = emptyList(),
                minSdk = ours.manifest.minSdk,
                targetSdk = ours.manifest.targetSdk,
                testOnly = false,
            )
        }
    }

    private fun realSigner(): String {
        val base = File(work, "base-for-signer.apk").apply { writeBytes(asset("apk/app-v1.apk")) }
        return FileSource(base).use { ApkInspector.inspect(it) }.signersFor(device.sdk).single().sha256
    }

    /** The bundle with one bit changed in every part that carries native code, in a place no parser reads. */
    private fun tamperParts() {
        val out = ByteArrayOutputStream()
        var changed = 0
        ZipOutputStream(out).use { zip ->
            ZipInputStream(ByteArrayInputStream(asset("apk/bundle.xapk"))).use { pristine ->
                while (true) {
                    val entry = pristine.nextEntry ?: break
                    val bytes = pristine.readBytes()
                    if (entry.name.startsWith("config.") && ABI_PARTS.any { it in entry.name }) {
                        bytes[LOCAL_HEADER_TIME] = (bytes[LOCAL_HEADER_TIME].toInt() xor 1).toByte()
                        changed++
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        assertEquals(3, changed)
        bundle.writeBytes(out.toByteArray())
    }

    private fun check(reader: ArchiveReader, signatures: SignatureReader = SignatureReader.OWN) = InstallGate(reader, Texts(targetContext), signatures).check(
        GateRequest(
            file = bundle,
            asset = Asset("app.xapk", "https://forge.test/app.xapk"),
            fileSha256 = Fingerprints.sha256(bundle.readBytes()),
            expectedSha256 = null,
            expectedPackage = PKG,
            pinnedSigners = emptyList(),
            installedOf = { null },
            device = device,
            staging = File(work, "staging"),
        ),
    )

    @Test
    fun aSplitAndroidReadsAsSignedBySomeoneElseStopsTheBundle() {
        val reader = Scripted(baseSigner = realSigner(), splitSigner = "f".repeat(64))
        val refused = assertThrows(StepFailure::class.java) { check(reader) }
        assertEquals(ProblemKind.SIGNER_MISMATCH, refused.problem.kind)
        assertTrue("the splits were read: ${reader.asked}", reader.asked.any { it != "base" })
    }

    @Test
    fun aBundleWhoseEveryPartHasTheBaseSignerPasses() {
        val signer = realSigner()
        val pass = check(Scripted(baseSigner = signer, splitSigner = signer))
        assertTrue(pass.split)
        assertEquals(listOf(signer), pass.facts.signers)
    }

    @Test
    fun whereAndroidCannotReadASplitItsSignatureIsVerifiedAndHeldToTheBase() {
        val pass = check(Scripted(baseSigner = realSigner(), splitSigner = null))
        assertTrue(pass.split)
        assertTrue(pass.facts.verified)
        val refused = assertThrows(StepFailure::class.java) { check(Scripted(baseSigner = "e".repeat(64), splitSigner = null)) }
        assertEquals(ProblemKind.SIGNER_MISMATCH, refused.problem.kind)
    }

    @Test
    fun aSplitChangedAfterSigningThatAndroidDidNotReadStopsTheBundle() {
        tamperParts()
        val claimed = Scripted(baseSigner = realSigner(), splitSigner = null)
        val refused = assertThrows(StepFailure::class.java) { check(claimed) }
        assertEquals(ProblemKind.SIGNER_MISMATCH, refused.problem.kind)
        assertEquals(targetContext.getString(R.string.engine4_part_not_signed), refused.problem.message)
        assertTrue("the splits were looked at: ${claimed.asked}", claimed.asked.any { it != "base" })
        assertTrue("the staging folder was left behind", File(work, "staging").listFiles().isNullOrEmpty())
    }

    @Test
    fun aSplitChangedAfterSigningThatAndroidReadWithoutVerifyingStopsTheBundle() {
        tamperParts()
        val signer = realSigner()
        val refused = assertThrows(StepFailure::class.java) { check(Scripted(baseSigner = signer, splitSigner = signer)) }
        assertEquals(ProblemKind.SIGNER_MISMATCH, refused.problem.kind)
        assertEquals(targetContext.getString(R.string.engine_reads_differently), refused.problem.message)
    }

    @Test
    fun aSplitThatCannotBeVerifiedHereStopsTheBundleUnlessAndroidReadIt() {
        val signer = realSigner()
        val noAnswer = SignatureReader { _, _ -> SignatureVerdict.CannotVerify("scripted") }
        val refused = assertThrows(StepFailure::class.java) { check(Scripted(baseSigner = signer, splitSigner = null), noAnswer) }
        assertEquals(ProblemKind.SIGNER_MISMATCH, refused.problem.kind)
        assertEquals(targetContext.getString(R.string.engine4_part_not_signed), refused.problem.message)
        assertTrue(check(Scripted(baseSigner = signer, splitSigner = signer), noAnswer).split)
    }

    @Test
    fun aBaseThatTheTwoReadersSignDifferentlyStopsTheBundle() {
        val signer = realSigner()
        val other = SignatureReader { _, _ -> SignatureVerdict.Holds(SignatureScheme.V3, listOf("d".repeat(64)), emptyList()) }
        val refused = assertThrows(StepFailure::class.java) { check(Scripted(baseSigner = signer, splitSigner = signer), other) }
        assertEquals(ProblemKind.SIGNER_MISMATCH, refused.problem.kind)
        assertEquals(targetContext.getString(R.string.engine_reads_differently), refused.problem.message)
    }

    @Test
    fun theOwnCheckHoldsForEveryPartOfTheBundleOnThisDevice() {
        val signer = realSigner()
        val seen = ArrayList<SignatureVerdict>()
        val recording = SignatureReader { file, sdk -> SignatureReader.OWN.read(file, sdk).also { synchronized(seen) { seen.add(it) } } }
        assertTrue(check(Scripted(baseSigner = signer, splitSigner = null), recording).split)
        Log.i("EngineTest", "own check on Android ${Build.VERSION.SDK_INT}: $seen")
        assertTrue("$seen", seen.size > 1 && seen.all { it is SignatureVerdict.Holds && it.certificates == listOf(signer) })
    }

    private companion object {
        val ABI_PARTS = listOf("arm64_v8a", "x86_64", "armeabi_v7a")

        /** The time of the first entry in its local header: no parser reads it, and every signature scheme from v2 on covers it. */
        const val LOCAL_HEADER_TIME = 10
    }
}
