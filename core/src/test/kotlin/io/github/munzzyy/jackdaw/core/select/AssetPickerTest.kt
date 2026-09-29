package io.github.munzzyy.jackdaw.core.select

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.AssetPolicy
import io.github.munzzyy.jackdaw.core.model.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetPickerTest {
    private fun asset(name: String) = Asset(name = name, url = "https://example.com/$name")

    private val tvProfile = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34, densityDpi = 320, television = true)
    private val wearProfile = DeviceProfile(abis = listOf("armeabi-v7a"), sdk = 30, densityDpi = 240, watch = true)

    @Test
    fun arm64PhonePrefersExactAbiOverUniversalAndOtherAbis() {
        val assets = listOf(
            asset("coffeecup-armeabi-v7a-release.apk"),
            asset("coffeecup-universal-release.apk"),
            asset("coffeecup-arm64-v8a-release.apk"),
            asset("coffeecup-x86_64-release.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("coffeecup-arm64-v8a-release.apk", picks.first().asset.name)
        assertTrue(picks.first().reasons.contains(PickReason(PickReason.Kind.ABI_MATCH, "arm64-v8a")))
    }

    @Test
    fun x86_64EmulatorPrefersOwnAbiThenArm64ThenUniversal() {
        val assets = listOf(
            asset("teasteep-arm64-v8a.apk"),
            asset("teasteep-universal.apk"),
            asset("teasteep-x86_64.apk"),
            asset("teasteep-armeabi-v7a.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.X86_64_EMULATOR, AssetPolicy())
        assertEquals("teasteep-x86_64.apk", picks[0].asset.name)
        assertEquals("teasteep-arm64-v8a.apk", picks[1].asset.name)
    }

    @Test
    fun wrongAbiDroppedByDefaultOnPhone() {
        val assets = listOf(asset("lampglow-x86.apk"), asset("lampglow-armeabi-v7a.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals(1, picks.size)
        assertEquals("lampglow-armeabi-v7a.apk", picks.single().asset.name)
    }

    @Test
    fun wrongAbiKeptAndPenalisedWhenMatchDeviceOff() {
        val assets = listOf(asset("lampglow-x86.apk"), asset("lampglow-armeabi-v7a.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(matchDevice = false))
        assertEquals(2, picks.size)
        assertEquals("lampglow-armeabi-v7a.apk", picks[0].asset.name)
        assertEquals("lampglow-x86.apk", picks[1].asset.name)
    }

    @Test
    fun nothingFitsWhenOnlyWrongAbisOffered() {
        val assets = listOf(asset("driftmark-x86.apk"), asset("driftmark-x86_64.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertTrue(picks.isEmpty())
    }

    @Test
    fun tvDeviceProfilePrefersTvVariant() {
        val assets = listOf(asset("mossreel-arm64-v8a.apk"), asset("mossreel-tv-arm64-v8a.apk"))
        val picks = AssetPicker.rank(assets, tvProfile, AssetPolicy())
        assertEquals("mossreel-tv-arm64-v8a.apk", picks.first().asset.name)
    }

    @Test
    fun phonePenalisesTvWearAutomotiveVariants() {
        val assets = listOf(
            asset("mossreel-arm64-v8a.apk"),
            asset("mossreel-tv-arm64-v8a.apk"),
            asset("mossreel-wear-arm64-v8a.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("mossreel-arm64-v8a.apk", picks.first().asset.name)
    }

    @Test
    fun wearDeviceKeepsWearVariantOverPlainBuild() {
        val assets = listOf(asset("pinequill-armeabi-v7a.apk"), asset("pinequill-wear-armeabi-v7a.apk"))
        val picks = AssetPicker.rank(assets, wearProfile, AssetPolicy())
        assertEquals("pinequill-wear-armeabi-v7a.apk", picks.first().asset.name)
    }

    @Test
    fun debugTestAndUnsignedArePenalised() {
        val assets = listOf(
            asset("saltvine-arm64-v8a-release.apk"),
            asset("saltvine-arm64-v8a-debug.apk"),
            asset("saltvine-arm64-v8a-test.apk"),
            asset("saltvine-arm64-v8a-unsigned.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("saltvine-arm64-v8a-release.apk", picks.first().asset.name)
        assertEquals(4, picks.size)
    }

    @Test
    fun apkPreferredOverBundleWhenBothPresent() {
        val assets = listOf(asset("hollowkite-arm64-v8a.apkm"), asset("hollowkite-arm64-v8a.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("hollowkite-arm64-v8a.apk", picks.first().asset.name)
    }

    @Test
    fun includeAndExcludeFilterByFileName() {
        val assets = listOf(asset("brightfox-fdroid-arm64-v8a.apk"), asset("brightfox-play-arm64-v8a.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(include = "fdroid"))
        assertEquals(1, picks.size)
        assertEquals("brightfox-fdroid-arm64-v8a.apk", picks.single().asset.name)

        val excluded = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(exclude = "play"))
        assertEquals(1, excluded.size)
        assertEquals("brightfox-fdroid-arm64-v8a.apk", excluded.single().asset.name)
    }

    @Test
    fun invalidPatternThrowsAssetPolicyException() {
        val assets = listOf(asset("brightfox-arm64-v8a.apk"))
        try {
            AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(include = "("))
            org.junit.Assert.fail("expected AssetPolicyException")
        } catch (_: AssetPolicyException) {
        }
    }

    @Test
    fun tiesBreakOnName() {
        val assets = listOf(asset("z-plain.apk"), asset("a-plain.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("a-plain.apk", picks[0].asset.name)
        assertEquals("z-plain.apk", picks[1].asset.name)
    }

    @Test
    fun nonInstallableKindsAreExcluded() {
        val assets = listOf(
            Asset(name = "notes.txt", url = "https://example.com/notes.txt"),
            asset("driftmark-arm64-v8a.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals(1, picks.size)
    }

    @Test
    fun negativeControlDetectsBrokenAbiPreference() {
        val assets = listOf(asset("coffeecup-armeabi-v7a-release.apk"), asset("coffeecup-arm64-v8a-release.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertTrue(picks.first().asset.name.contains("arm64-v8a"))
        assertTrue(picks.first().score > picks.last().score)
    }
}
