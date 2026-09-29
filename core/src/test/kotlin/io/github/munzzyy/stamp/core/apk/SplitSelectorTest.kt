package io.github.munzzyy.stamp.core.apk

import io.github.munzzyy.stamp.core.model.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitSelectorTest {
    private val bundle = BundleIndex.read(BytesSource(ApkFixtures.bytes("bundle.xapk")))

    private fun names(choice: SplitChoice) = choice.chosen.map { it.manifest!!.split ?: "base" }

    private fun skippedNames(choice: SplitChoice) = choice.skipped.map { it.first.manifest!!.split }.toSet()

    private fun apk(split: String?, feature: Boolean = false, configFor: String? = null, pkg: String = "com.example.app", code: Long = 3) =
        BundleApk(
            "${split ?: "base"}.apk", 100, true,
            ManifestInfo(pkg, code, "3.0", 24, 35, split, feature, configFor, emptyList(), emptyList(), false, false, emptyList()),
        )

    @Test
    fun arm64PhoneGetsItsAbiDensityAndLanguage() {
        val choice = SplitSelector.select(bundle.apks, DeviceProfile.ARM64_PHONE)
        assertEquals(listOf("base", "config.arm64_v8a", "config.xxhdpi", "config.en"), names(choice))
        assertEquals(setOf("config.x86_64", "config.armeabi_v7a", "config.mdpi", "config.de"), skippedNames(choice))
        assertTrue(choice.skipped.all { it.second.isNotBlank() })
    }

    @Test
    fun abiOrderFollowsTheDevicePreference() {
        assertTrue("config.x86_64" in names(SplitSelector.select(bundle.apks, DeviceProfile.X86_64_EMULATOR)))
        val old = DeviceProfile(listOf("armeabi-v7a", "armeabi"), sdk = 28, densityDpi = 240)
        assertTrue("config.armeabi_v7a" in names(SplitSelector.select(bundle.apks, old)))
    }

    @Test
    fun noMatchingAbiIsIncompatible() {
        val riscv = DeviceProfile(listOf("riscv64"), sdk = 36, densityDpi = 420)
        assertThrows(IncompatibleDeviceException::class.java) { SplitSelector.select(bundle.apks, riscv) }
    }

    @Test
    fun densityTakesTheSmallestBucketAtOrAboveElseTheLargestBelow() {
        fun density(dpi: Int) = names(SplitSelector.select(bundle.apks, DeviceProfile(listOf("arm64-v8a"), 36, dpi))).single { it.endsWith("dpi") }
        assertEquals("config.mdpi", density(100))
        assertEquals("config.mdpi", density(160))
        assertEquals("config.xxhdpi", density(161))
        assertEquals("config.xxhdpi", density(480))
        assertEquals("config.xxhdpi", density(640))
    }

    @Test
    fun everyDeviceLanguageWithASplitIsTaken() {
        val device = DeviceProfile(listOf("arm64-v8a"), 36, 420, languages = listOf("de-DE", "en", "fr"))
        val chosen = names(SplitSelector.select(bundle.apks, device))
        assertTrue("config.de" in chosen && "config.en" in chosen)
        val none = DeviceProfile(listOf("arm64-v8a"), 36, 420, languages = listOf("ja"))
        assertTrue(names(SplitSelector.select(bundle.apks, none)).none { it == "config.de" || it == "config.en" })
    }

    @Test
    fun featureSplitsBringTheirOwnConfigSplits() {
        val apks = listOf(
            apk(null),
            apk("config.arm64_v8a"),
            apk("dyn", feature = true),
            apk("dyn.config.arm64_v8a", configFor = "dyn"),
            apk("dyn.config.x86_64", configFor = "dyn"),
            apk("dyn.config.xxxhdpi", configFor = "dyn"),
            apk("gone.config.en", configFor = "gone"),
            apk("config.etc2"),
        )
        val choice = SplitSelector.select(apks, DeviceProfile.ARM64_PHONE)
        assertEquals(listOf("base", "dyn", "config.arm64_v8a", "dyn.config.arm64_v8a", "dyn.config.xxxhdpi"), names(choice))
        assertEquals(setOf("dyn.config.x86_64", "gone.config.en", "config.etc2"), skippedNames(choice))
    }

    @Test
    fun featureWithoutAFittingAbiIsIncompatible() {
        val apks = listOf(apk(null), apk("dyn", feature = true), apk("dyn.config.x86_64", configFor = "dyn"))
        assertThrows(IncompatibleDeviceException::class.java) { SplitSelector.select(apks, DeviceProfile.ARM64_PHONE) }
    }

    @Test
    fun baseCountAndIdentityAreEnforced() {
        val phone = DeviceProfile.ARM64_PHONE
        assertThrows(ApkFormatException::class.java) { SplitSelector.select(listOf(apk("config.en")), phone) }
        assertThrows(ApkFormatException::class.java) { SplitSelector.select(listOf(apk(null), apk(null)), phone) }
        assertThrows(ApkFormatException::class.java) { SplitSelector.select(listOf(apk(null), apk("config.en", code = 4)), phone) }
        assertThrows(ApkFormatException::class.java) { SplitSelector.select(listOf(apk(null), apk("config.en", pkg = "com.example.other")), phone) }
        assertThrows(ApkFormatException::class.java) { SplitSelector.select(listOf(apk(null), apk("config.en"), apk("config.en")), phone) }
    }
}
