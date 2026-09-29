package io.github.munzzyy.jackdaw.core.apk

import io.github.munzzyy.jackdaw.core.apk.ApkFixtures.deflated
import io.github.munzzyy.jackdaw.core.apk.ApkFixtures.stored
import io.github.munzzyy.jackdaw.core.apk.ApkFixtures.zip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleIndexTest {
    private val configs = listOf("arm64_v8a", "x86_64", "armeabi_v7a", "xxhdpi", "mdpi", "en", "de")

    private fun read(bytes: ByteArray) = BundleIndex.read(BytesSource(bytes))

    private fun xapkManifest(pkg: String = "com.example.app", code: String = "3", files: List<String> = listOf("base.apk")) =
        """{"package_name":"$pkg","version_code":"$code","split_apks":[${files.joinToString { "{\"file\":\"$it\"}" }}]}""".toByteArray()

    @Test
    fun xapkWithStoredApksIsInspectedInPlace() {
        val bundle = read(ApkFixtures.bytes("bundle.xapk"))
        assertEquals(BundleKind.XAPK, bundle.kind)
        assertEquals("com.example.app", bundle.declaredPackage)
        assertEquals(3L, bundle.declaredVersionCode)
        assertEquals(listOf("Android/obb/com.example.app/main.3.com.example.app.obb"), bundle.expansionFiles)
        assertEquals(8, bundle.apks.size)
        assertTrue(bundle.apks.all { it.stored && it.manifest != null })
        val splits = bundle.apks.associate { it.entryName to it.manifest!!.split }
        assertNull(splits["com.example.app.apk"])
        for (c in configs) assertEquals("config.$c", splits["config.$c.apk"])
        assertEquals(listOf("x86_64"), bundle.apks.single { it.entryName == "config.x86_64.apk" }.manifest!!.nativeLibraryAbis)
        assertEquals(ApkFixtures.bytes("split-base.apk").size.toLong(), bundle.apks.single { it.entryName == "com.example.app.apk" }.size)
    }

    @Test
    fun apksWithDeflatedApksNeedExtraction() {
        val bundle = read(ApkFixtures.bytes("bundle.apks"))
        assertEquals(BundleKind.APKS, bundle.kind)
        assertEquals(8, bundle.apks.size)
        assertTrue(bundle.apks.all { !it.stored && it.manifest == null })
        assertNull(bundle.declaredPackage)
        assertThrows(ApkFormatException::class.java) { SplitSelector.select(bundle.apks, io.github.munzzyy.jackdaw.core.model.DeviceProfile.ARM64_PHONE) }
    }

    @Test
    fun plainZipOfApks() {
        val bundle = read(zip(stored("base.apk", ApkFixtures.bytes("split-base.apk")), stored("x/config.en.apk", ApkFixtures.bytes("split-config.en.apk"))))
        assertEquals(BundleKind.ZIP, bundle.kind)
        assertEquals(listOf("base.apk", "x/config.en.apk"), bundle.apks.map { it.entryName })
    }

    @Test
    fun metadataDisagreeingWithTheApksIsAnError() {
        val base = ApkFixtures.bytes("split-base.apk")
        assertThrows(ApkFormatException::class.java) { read(zip(deflated("manifest.json", xapkManifest(code = "99")), stored("base.apk", base))) }
        assertThrows(ApkFormatException::class.java) { read(zip(deflated("manifest.json", xapkManifest(pkg = "com.example.other")), stored("base.apk", base))) }
        assertThrows(ApkFormatException::class.java) {
            read(zip(deflated("manifest.json", xapkManifest(files = listOf("base.apk", "gone.apk"))), stored("base.apk", base)))
        }
        assertThrows(ApkFormatException::class.java) { read(zip(deflated("manifest.json", "{not json".toByteArray()), stored("base.apk", base))) }
        read(zip(deflated("manifest.json", xapkManifest()), stored("base.apk", base)))
    }

    @Test
    fun innerApksMustAgreeWithEachOther() {
        val mixed = zip(stored("base.apk", ApkFixtures.bytes("split-base.apk")), stored("config.en.apk", ApkFixtures.bytes("app-v2.apk")))
        assertThrows(ApkFormatException::class.java) { read(mixed) }
        val twoBases = zip(stored("a.apk", ApkFixtures.bytes("split-base.apk")), stored("b.apk", ApkFixtures.bytes("split-base.apk")))
        assertThrows(ApkFormatException::class.java) { read(twoBases) }
    }

    @Test
    fun bundleWithoutApksIsAnError() {
        assertThrows(ApkFormatException::class.java) { read(zip(stored("readme.txt", "hi".toByteArray()))) }
    }
}
