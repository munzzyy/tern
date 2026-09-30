package io.github.munzzyy.tern.core.select

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun arm64PhonePicksArm64v8OverArm32v7WhenNamedThatWay() {
        val assets = listOf(
            asset("LocalSend-1.17.0-android-arm32v7.apk"),
            asset("LocalSend-1.17.0-android-arm64v8.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("LocalSend-1.17.0-android-arm64v8.apk", picks.first().asset.name)
        assertTrue(picks.first().score > picks.last().score)
    }

    @Test
    fun bareArmTokenReadAsThirtyTwoBitArm() {
        val assets = listOf(
            asset("koreader-android-arm-v2025.04.apk"),
            asset("koreader-android-arm64-v2025.04.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("koreader-android-arm64-v2025.04.apk", picks.first().asset.name)
        assertEquals(2, picks.size)
    }

    @Test
    fun otherAbiSpellingsAreRecognised() {
        val assets = listOf(
            asset("app-arm64v8a.apk"),
            asset("app-armeabiv7a.apk"),
            asset("app-armhf.apk"),
            asset("app-x8664.apk"),
        )
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(matchDevice = false))
        val byName = picks.associateBy { it.asset.name }
        assertTrue(byName.getValue("app-arm64v8a.apk").reasons.contains(PickReason(PickReason.Kind.ABI_MATCH, "arm64-v8a")))
        assertTrue(byName.getValue("app-armeabiv7a.apk").reasons.any { it.kind == PickReason.Kind.ABI_MATCH && it.detail == "armeabi-v7a" })
        assertTrue(byName.getValue("app-armhf.apk").reasons.any { it.kind == PickReason.Kind.ABI_MATCH && it.detail == "armeabi-v7a" })
        assertTrue(byName.getValue("app-x8664.apk").reasons.any { it.kind == PickReason.Kind.ABI_MISMATCH && it.detail == "x86_64" })
    }

    @Test
    fun archivesAreLeftOutUnlessAskedFor() {
        val assets = listOf(asset("app-arm64-v8a.zip"), asset("app-arm64-v8a.tar.xz"), asset("notes.txt"))
        assertTrue(AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy()).isEmpty())
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(archives = true))
        assertEquals(listOf("app-arm64-v8a.tar.xz", "app-arm64-v8a.zip"), picks.map { it.asset.name })
    }

    @Test
    fun anArchiveRanksBelowAnApkOrBundleWhoseNameScoresTheSame() {
        val assets = listOf(asset("app-arm64-v8a.zip"), asset("app-arm64-v8a.apk"), asset("app-arm64-v8a.apks"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(archives = true))
        assertEquals(listOf("app-arm64-v8a.apk", "app-arm64-v8a.apks", "app-arm64-v8a.zip"), picks.map { it.asset.name })
        assertTrue(picks[1].score > picks[2].score)
    }

    @Test
    fun anArchiveTheSourceKnowsToHoldTheAppIsRankedUnasked() {
        val artifact = Asset(name = "app-release.zip", url = "https://example.com/artifacts/1/zip", holdsApps = true)
        val picks = AssetPicker.rank(listOf(artifact, asset("sources.zip")), DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals(listOf("app-release.zip"), picks.map { it.asset.name })
        assertEquals(listOf(artifact), Release(id = "1", version = "1", assets = listOf(artifact, asset("sources.zip"))).installable)
    }

    @Test
    fun theFilterForFilesInsideAnArchiveIsCheckedWithTheOthersAndApplied() {
        val assets = listOf(asset("app.apk"))
        assertThrows(AssetPolicyException::class.java) { AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(innerFilter = "(unclosed")) }
        assertTrue(AssetPicker.installsInside(AssetPolicy(), "anything.apk"))
        assertTrue(AssetPicker.installsInside(AssetPolicy(innerFilter = "arm64"), "app/app-arm64-v8a.apk"))
        assertFalse(AssetPicker.installsInside(AssetPolicy(innerFilter = "arm64"), "app/app-x86.apk"))
        assertThrows(AssetPolicyException::class.java) { AssetPicker.installsInside(AssetPolicy(innerFilter = "(unclosed"), "a.apk") }
    }

    @Test
    fun tarballsOfEveryCommonKindAreArchives() {
        for (name in listOf("a.zip", "a.tar", "a.tar.gz", "a.tgz", "a.tar.bz2", "a.tbz2", "a.tar.xz", "a.txz", "A.TAR.XZ?x=1")) {
            assertEquals(name, AssetKind.ARCHIVE, Asset.kindOf(name))
        }
        assertEquals(AssetKind.OTHER, Asset.kindOf("a.tar.zst"))
        assertEquals(AssetKind.APK, Asset.kindOf("a.apk"))
    }

    @Test
    fun namedProcessorsAreReadInTheSpellingAndroidUses() {
        assertEquals(listOf("arm64-v8a"), AssetPicker.abisIn("app-aarch64-release"))
        assertEquals(listOf("armeabi-v7a", "x86_64"), AssetPicker.abisIn("app-armv7-x64.apk"))
        assertTrue(AssetPicker.abisIn("app-universal.apk").isEmpty())
        assertEquals("arm64-v8a", AssetPicker.canonical("ARM64"))
    }

    @Test
    fun negativeControlDetectsBrokenAbiPreference() {
        val assets = listOf(asset("coffeecup-armeabi-v7a-release.apk"), asset("coffeecup-arm64-v8a-release.apk"))
        val picks = AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertTrue(picks.first().asset.name.contains("arm64-v8a"))
        assertTrue(picks.first().score > picks.last().score)
    }
}
