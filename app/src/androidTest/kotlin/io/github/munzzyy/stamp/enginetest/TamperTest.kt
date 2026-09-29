package io.github.munzzyy.stamp.enginetest

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.core.apk.BytesSource
import io.github.munzzyy.stamp.core.apk.ZipIndex
import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.DeviceProfile
import io.github.munzzyy.stamp.core.verify.Fingerprints
import io.github.munzzyy.stamp.engine.ProblemKind
import io.github.munzzyy.stamp.engine.real.Texts
import io.github.munzzyy.stamp.install.GateRequest
import io.github.munzzyy.stamp.install.InstallGate
import io.github.munzzyy.stamp.install.PackageManagerArchiveReader
import io.github.munzzyy.stamp.install.StepFailure
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** What Android's archive parser does with a signed APK altered afterwards, and what the gate makes of it. */
@RunWith(AndroidJUnit4::class)
class TamperTest {
    private val pm = targetContext.packageManager
    private val dir = File(targetContext.cacheDir, "tamper").apply { deleteRecursively(); mkdirs() }
    private val reader = PackageManagerArchiveReader(pm)
    private val gate = InstallGate(reader, Texts(targetContext))
    private val pristine = asset("apk/app-v2.apk")

    @Test
    fun thePristineFileReadsAndPasses() {
        val file = write("pristine.apk", pristine)
        val reading = assertNotNullReading(file)
        record("pristine", file)
        assertEquals(PKG, reading.packageName)
        assertEquals(2L, reading.versionCode)
        assertEquals(1, reading.signers.size)
        val pass = gate.check(request(file))
        assertTrue(pass.facts.verified)
    }

    @Test
    fun recordWhatTheArchiveParserMakesOfBundleEntries() {
        val bundle = asset("apk/bundle.xapk")
        val index = ZipIndex.open(BytesSource(bundle))
        for (entry in index.entries.filter { it.name.endsWith(".apk") }) {
            val file = write("bundle-${entry.name.substringAfterLast('/')}", index.read(entry, 1 shl 20))
            record("bundle entry ${entry.name}", file)
        }
    }

    @Test
    fun aByteFlippedInsideAResourceIsRefused() {
        val index = ZipIndex.open(BytesSource(pristine))
        val entry = index.find("resources.arsc") ?: error("fixture has no resources.arsc")
        val at = index.dataOffset(entry) + entry.compressedSize / 2
        val file = write("resource-flip.apk", flipped(at))
        record("resource byte at $at (${entry.name}, method ${entry.method})", file)
        assertBlocked(file)
    }

    @Test
    fun aResourceStringChangedWithoutBreakingItsStructureIsRefused() {
        val index = ZipIndex.open(BytesSource(pristine))
        val entry = index.find("resources.arsc") ?: error("fixture has no resources.arsc")
        assertEquals(0, entry.method)
        val start = index.dataOffset(entry)
        val flags = start + STRING_POOL_FLAGS_BYTE
        assertEquals(0x01, pristine[flags.toInt()].toInt())
        val file = write("resource-flags.apk", flipped(flags))
        record("string pool UTF-8 flag in resources.arsc cleared at $flags", file)
        assertNotNull("the change should leave the file readable when nothing is verified", pm.getPackageArchiveInfo(file.path, 0))
        assertBlocked(file)
    }

    @Test
    fun aByteFlippedInsideTheSigningBlockIsRefused() {
        val cd = ZipIndex.open(BytesSource(pristine)).centralDirectoryOffset
        val magic = String(pristine, (cd - 16).toInt(), 16, Charsets.US_ASCII)
        assertEquals("APK Sig Block 42", magic)
        val size = ByteBuffer.wrap(pristine, (cd - 24).toInt(), 8).order(ByteOrder.LITTLE_ENDIAN).long
        val blockStart = cd - size - 8
        val at = blockStart + (size + 8) / 2
        val file = write("signing-block-flip.apk", flipped(at))
        record("signing block byte at $at (block $blockStart..$cd)", file)
        assertBlocked(file)
    }

    @Test
    fun aByteFlippedInsideTheManifestIsRefused() {
        val index = ZipIndex.open(BytesSource(pristine))
        val entry = index.find("AndroidManifest.xml") ?: error("no manifest")
        val at = index.dataOffset(entry) + entry.compressedSize - 3
        val file = write("manifest-flip.apk", flipped(at))
        record("manifest byte at $at (method ${entry.method})", file)
        assertBlocked(file)
    }

    private fun assertBlocked(file: File) {
        try {
            val pass = gate.check(request(file))
            fail("the gate passed a tampered file: ${pass.facts}")
        } catch (e: StepFailure) {
            Log.i(TAG, "gate on ${file.name}: ${e.kind} ${e.message}")
            assertTrue(e.kind in setOf(ProblemKind.PARSE, ProblemKind.PACKAGE_MISMATCH, ProblemKind.SIGNER_MISMATCH))
        }
    }

    private fun record(what: String, file: File) {
        val withCerts = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val plain = pm.getPackageArchiveInfo(file.path, 0)
        val certs = withCerts?.let { PackageManagerArchiveReader.reading(it).signers }
        Log.i(TAG, "$what: with GET_SIGNING_CERTIFICATES -> ${withCerts?.let { "${it.packageName} ${it.longVersionCode} signers=$certs" } ?: "null"}; with flags 0 -> ${plain?.let { "${it.packageName} ${it.longVersionCode}" } ?: "null"}")
    }

    private fun assertNotNullReading(file: File) = reader.read(file).also { assertNotNull("Android refused the pristine file", it) }!!

    private fun flipped(at: Long): ByteArray = pristine.copyOf().also { it[at.toInt()] = (it[at.toInt()].toInt() xor 0x01).toByte() }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun request(file: File) = GateRequest(
        file = file,
        asset = Asset("app.apk", "https://forge.test/app.apk"),
        fileSha256 = Fingerprints.sha256(file.readBytes()),
        expectedSha256 = null,
        expectedPackage = PKG,
        pinnedSigners = emptyList(),
        installedOf = { null },
        device = DeviceProfile.X86_64_EMULATOR,
        staging = File(dir, "staging-${file.name}"),
    )

    private companion object {
        const val TAG = "EngineTamper"

        /** Table header (12 bytes), then the string pool header, whose flags word starts 16 bytes in; 0x100 is UTF-8. */
        const val STRING_POOL_FLAGS_BYTE = 12 + 16 + 1
    }
}
