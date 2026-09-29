package io.github.munzzyy.stamp.core.apk

import io.github.munzzyy.stamp.core.json.JsonNumber
import io.github.munzzyy.stamp.core.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkInspectorTest {
    private fun inspect(name: String) = ApkInspector.inspect(BytesSource(ApkFixtures.bytes(name)))

    @Test
    fun everyFixtureMatchesAapt2AndApksigner() {
        assertTrue(ApkFixtures.apkNames.size >= 15)
        for (name in ApkFixtures.apkNames) {
            val expected = ApkFixtures.expected.obj(name)!!
            val info = inspect(name)
            val m = info.manifest
            assertEquals(name, expected.string("package"), m.packageName)
            assertEquals(name, expected.long("versionCode"), m.versionCode)
            assertEquals(name, expected.string("versionName"), m.versionName)
            assertEquals(name, expected.string("split"), m.split)
            assertEquals(name, expected.long("minSdk")?.toInt(), m.minSdk)
            assertEquals(name, expected.long("targetSdk")?.toInt(), m.targetSdk)
            assertEquals(name, expected.array("permissions")!!.strings(), m.permissions)
            assertEquals(name, expected.array("certificates")!!.strings().toSet(), info.signers.map { it.sha256 }.toSet())
            assertEquals(name, schemes(expected), info.schemes)
            assertEquals(name, ApkFixtures.bytes(name).size.toLong(), info.fileSize)
        }
    }

    private fun schemes(expected: JsonObject): Set<Int> =
        expected.array("schemes")!!.items.map { (it as JsonNumber).toLongOrNull()!!.toInt() }.toSet()

    @Test
    fun rotatedKeyReportsBothSignersAndTheLineage() {
        val info = inspect("app-rotated.apk")
        val lineage = ApkFixtures.expected.obj("app-rotated.apk")!!.array("lineage")!!.strings()
        assertEquals(2, lineage.size)
        val v31 = info.signers.single { it.scheme == SignatureScheme.V31 }
        val v3 = info.signers.single { it.scheme == SignatureScheme.V3 }
        assertEquals(lineage.last(), v31.sha256)
        assertEquals(lineage.first(), v3.sha256)
        assertEquals(lineage, v31.lineage)
        assertEquals("CN=Example Fixture gamma,O=Example", v31.subject)
    }

    @Test
    fun jarOnlySignatureComesFromThePkcs7Block() {
        val info = inspect("app-jar-only.apk")
        assertEquals(setOf(SignatureScheme.V1), info.schemes)
        val signer = info.signers.single()
        assertEquals(SignatureScheme.V1, signer.scheme)
        assertEquals("CN=Example Fixture alpha,O=Example", signer.subject)
        assertEquals(inspect("app-v2-only.apk").signers.single().sha256, signer.sha256)
    }

    @Test
    fun sameKeyAndOtherKeyAreTellable() {
        val v1 = inspect("app-v1.apk").signers.map { it.sha256 }.toSet()
        val v2 = inspect("app-v2.apk").signers.map { it.sha256 }.toSet()
        val other = inspect("app-v2-otherkey.apk").signers.map { it.sha256 }.toSet()
        assertEquals(v1, v2)
        assertTrue(v2.intersect(other).isEmpty())
    }

    @Test
    fun manifestDetailsAndNativeLibraries() {
        val m = inspect("app-v1.apk").manifest
        assertEquals(listOf("android.hardware.camera"), m.features)
        assertFalse(m.debuggable)
        assertFalse(m.testOnly)
        assertFalse(m.isFeatureSplit)
        assertNull(m.configForSplit)
        assertEquals(emptyList<String>(), m.nativeLibraryAbis)
        assertEquals(listOf("arm64-v8a"), inspect("split-config.arm64_v8a.apk").manifest.nativeLibraryAbis)
        assertEquals(listOf("armeabi-v7a"), inspect("split-config.armeabi_v7a.apk").manifest.nativeLibraryAbis)
    }

    @Test
    fun zip64FixtureIsReadThroughItsZip64Records() {
        val info = inspect("app-zip64.apk")
        assertEquals("com.example.app", info.manifest.packageName)
        assertTrue(info.signers.isEmpty())
        assertTrue(info.schemes.isEmpty())
    }

    @Test
    fun fileSourceGivesTheSameAnswer() {
        val file = File.createTempFile("stamp", ".apk")
        try {
            file.writeBytes(ApkFixtures.bytes("app-rotated.apk"))
            val fromFile = FileSource(file).use { ApkInspector.inspect(it) }
            assertEquals(inspect("app-rotated.apk"), fromFile)
        } finally {
            file.delete()
        }
    }
}
