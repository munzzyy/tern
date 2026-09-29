package io.github.munzzyy.stamp.engine.real

import io.github.munzzyy.stamp.core.engine.InstalledApp
import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.DeviceProfile
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.select.Pick
import io.github.munzzyy.stamp.data.FileFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluatorTest {
    private fun asset(name: String) = Asset(name = name, url = "https://example.org/$name")
    private fun pick(name: String, score: Int = 0) = Pick(asset(name), score, emptyList())
    private val release = Release(id = "r1", version = "1.0", assets = emptyList())
    private val arm64Phone = DeviceProfile(abis = listOf("arm64-v8a", "armeabi-v7a"), sdk = 34, densityDpi = 420)
    private val armTv = DeviceProfile(abis = listOf("armeabi-v7a"), sdk = 30, densityDpi = 320, television = true)

    private fun facts(packageName: String, nativeAbis: List<String> = emptyList()) = FileFacts(
        packageName = packageName, versionCode = 1, versionName = "1.0", signers = emptyList(), lineage = emptyList(),
        permissions = emptyList(), minSdk = null, targetSdk = null, testOnly = false, verified = false,
        nativeAbis = nativeAbis,
    )

    private fun appConfig(packageName: String?) = io.github.munzzyy.stamp.core.model.AppConfig(
        id = "a", source = io.github.munzzyy.stamp.core.model.SourceSpec("github", "https://example.org/a"), name = "App",
        packageName = packageName,
    )

    @Test
    fun freshInstallWithNoPackageKnownTakesTheBestPickWithoutInspectingOthers() {
        val ranked = listOf(pick("bitwarden.apk"), pick("authenticator.apk"))
        var inspected = 0
        val (chosen, _) = Evaluator.chooseFile(appConfig(null), null, release, ranked, arm64Phone) { _, _ -> inspected++; facts("com.x.other") }
        assertEquals("bitwarden.apk", chosen.asset.name)
        assertEquals(1, inspected)
    }

    @Test
    fun freshInstallPrefersTheFileThatReadsAsTheConfiguredPackage() {
        val ranked = listOf(pick("authenticator.apk"), pick("bitwarden.apk"))
        val (chosen, chosenFacts) = Evaluator.chooseFile(appConfig("com.x.password"), null, release, ranked, arm64Phone) { asset, _ ->
            if (asset.name == "bitwarden.apk") facts("com.x.password") else facts("com.x.authenticator")
        }
        assertEquals("bitwarden.apk", chosen.asset.name)
        assertEquals("com.x.password", chosenFacts?.packageName)
    }

    @Test
    fun whenNoRankedFileMatchesThePackageTheBestOneIsStillOffered() {
        val ranked = listOf(pick("authenticator.apk"))
        val (chosen, chosenFacts) = Evaluator.chooseFile(appConfig("com.x.password"), null, release, ranked, arm64Phone) { _, _ -> facts("com.x.authenticator") }
        assertEquals("authenticator.apk", chosen.asset.name)
        assertEquals("com.x.authenticator", chosenFacts?.packageName)
    }

    @Test
    fun installedAppStillPrefersTheSignerMatchOverPackageAlone() {
        val installedApp = InstalledApp("com.x.password", "0.9", 9, listOf("aa"))
        val device = DeviceApp(installedApp, emptyList(), 34, null, null, emptySet())
        val ranked = listOf(pick("wrongsigner.apk"), pick("rightsigner.apk"))
        val (chosen, _) = Evaluator.chooseFile(appConfig("com.x.password"), device, release, ranked, arm64Phone) { asset, _ ->
            if (asset.name == "rightsigner.apk") {
                FileFacts("com.x.password", 10, "1.0", listOf("aa"), emptyList(), emptyList(), null, null, false, false)
            } else {
                FileFacts("com.x.password", 10, "1.0", listOf("bb"), emptyList(), emptyList(), null, null, false, false)
            }
        }
        assertEquals("rightsigner.apk", chosen.asset.name)
    }

    @Test
    fun noPackageConfiguredMatchesAnything() {
        assertTrue(Evaluator.matchesConfiguredPackage(null, sequenceOf(facts("anything"))))
    }

    @Test
    fun matchingFactFoundAmongTheFirstFewIsAMatch() {
        assertTrue(Evaluator.matchesConfiguredPackage("com.x.password", sequenceOf(facts("com.x.authenticator"), facts("com.x.password"))))
    }

    @Test
    fun noMatchingFactAtAllIsNotAMatch() {
        assertFalse(Evaluator.matchesConfiguredPackage("com.x.password", sequenceOf(facts("com.x.authenticator"), facts("com.x.other"))))
    }

    @Test
    fun anUnreadableFactBehavesAsBeforeThisCheckExisted() {
        assertTrue(Evaluator.matchesConfiguredPackage("com.x.password", sequenceOf(null)))
    }

    @Test
    fun negativeControlDetectsAFreshInstallSkippingThePackageCheck() {
        val ranked = listOf(pick("authenticator.apk"), pick("bitwarden.apk"))
        val brokenBest = ranked.first()
        assertEquals("authenticator.apk", brokenBest.asset.name)
        val (chosen, _) = Evaluator.chooseFile(appConfig("com.x.password"), null, release, ranked, arm64Phone) { asset, _ ->
            if (asset.name == "bitwarden.apk") facts("com.x.password") else facts("com.x.authenticator")
        }
        assertTrue(chosen.asset.name != brokenBest.asset.name)
    }

    @Test
    fun aFileWithNativeLibsThatFitNoProcessorIsPassedOverForTheNextOne() {
        val ranked = listOf(pick("nextcloud-generic.apk"), pick("nextcloud-armeabi.apk"))
        val (chosen, chosenFacts) = Evaluator.chooseFile(appConfig(null), null, release, ranked, armTv) { asset, _ ->
            if (asset.name == "nextcloud-generic.apk") facts("com.x.nextcloud", listOf("arm64-v8a", "x86_64")) else facts("com.x.nextcloud", listOf("armeabi-v7a"))
        }
        assertEquals("nextcloud-armeabi.apk", chosen.asset.name)
        assertEquals(listOf("armeabi-v7a"), chosenFacts?.nativeAbis)
    }

    @Test
    fun aFileWithNoNativeLibsFitsEveryDevice() {
        val ranked = listOf(pick("pure-kotlin.apk"))
        val (chosen, _) = Evaluator.chooseFile(appConfig(null), null, release, ranked, armTv) { _, _ -> facts("com.x.app", emptyList()) }
        assertEquals("pure-kotlin.apk", chosen.asset.name)
    }

    @Test
    fun whenNoCandidateFitsTheProcessorTheBestOneIsStillReturnedForTheCallerToReport() {
        val ranked = listOf(pick("nextcloud-generic.apk"))
        val (chosen, chosenFacts) = Evaluator.chooseFile(appConfig(null), null, release, ranked, armTv) { _, _ -> facts("com.x.nextcloud", listOf("arm64-v8a", "x86_64")) }
        assertEquals("nextcloud-generic.apk", chosen.asset.name)
        assertEquals(listOf("arm64-v8a", "x86_64"), chosenFacts?.nativeAbis)
    }

    @Test
    fun negativeControlDetectsAMissingNativeAbiCheck() {
        val ranked = listOf(pick("nextcloud-generic.apk"), pick("nextcloud-armeabi.apk"))
        val bestByNameAlone = ranked.first()
        assertEquals("nextcloud-generic.apk", bestByNameAlone.asset.name)
        val (chosen, _) = Evaluator.chooseFile(appConfig(null), null, release, ranked, armTv) { asset, _ ->
            if (asset.name == "nextcloud-generic.apk") facts("com.x.nextcloud", listOf("arm64-v8a", "x86_64")) else facts("com.x.nextcloud", listOf("armeabi-v7a"))
        }
        assertTrue(chosen.asset.name != bestByNameAlone.asset.name)
    }
}
