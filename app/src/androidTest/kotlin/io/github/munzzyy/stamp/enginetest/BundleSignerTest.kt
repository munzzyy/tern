package io.github.munzzyy.stamp.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.core.apk.ApkInspector
import io.github.munzzyy.stamp.core.apk.FileSource
import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.DeviceProfile
import io.github.munzzyy.stamp.core.verify.Fingerprints
import io.github.munzzyy.stamp.engine.ProblemKind
import io.github.munzzyy.stamp.engine.real.Texts
import io.github.munzzyy.stamp.install.AndroidReading
import io.github.munzzyy.stamp.install.ArchiveReader
import io.github.munzzyy.stamp.install.GateRequest
import io.github.munzzyy.stamp.install.InstallGate
import io.github.munzzyy.stamp.install.StepFailure
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android reads a split by itself on some versions and not on others. The gate has to hold every
 * part of a bundle to the base's signer either way, so the reader is scripted here.
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

    private fun check(reader: ArchiveReader) = InstallGate(reader, Texts(targetContext)).check(
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
    fun whereAndroidCannotReadASplitWhatTheSplitClaimsIsHeldToTheBase() {
        assertTrue(check(Scripted(baseSigner = realSigner(), splitSigner = null)).split)
        val refused = assertThrows(StepFailure::class.java) { check(Scripted(baseSigner = "e".repeat(64), splitSigner = null)) }
        assertEquals(ProblemKind.SIGNER_MISMATCH, refused.problem.kind)
    }
}
