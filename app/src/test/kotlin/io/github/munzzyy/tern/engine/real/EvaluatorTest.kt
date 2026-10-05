package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.select.Pick
import io.github.munzzyy.tern.data.FileFacts
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

    private fun appConfig(packageName: String?) = io.github.munzzyy.tern.core.model.AppConfig(
        id = "a", source = io.github.munzzyy.tern.core.model.SourceSpec("github", "https://example.org/a"), name = "App",
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

    @Test
    fun aReleaseFitsTheDeviceWhenOneOfItsFilesRunsThere() {
        assertFalse(Evaluator.fitsDevice(sequenceOf(facts("org.videolan.vlc", listOf("arm64-v8a"))), armTv))
        assertFalse(Evaluator.fitsDevice(sequenceOf(facts("a", listOf("arm64-v8a")), facts("a", listOf("x86_64"))), armTv))
        assertTrue(Evaluator.fitsDevice(sequenceOf(facts("a", listOf("arm64-v8a")), facts("a", listOf("armeabi-v7a"))), armTv))
        assertTrue(Evaluator.fitsDevice(sequenceOf(facts("org.videolan.vlc", listOf("arm64-v8a"))), arm64Phone))
        assertTrue(Evaluator.fitsDevice(sequenceOf(facts("a", emptyList())), armTv))
    }

    @Test
    fun aFileThatCannotBeReadNowSettlesNothingAboutTheFit() {
        assertTrue(Evaluator.fitsDevice(sequenceOf(null), armTv))
        assertTrue(Evaluator.fitsDevice(sequenceOf(facts("a", listOf("arm64-v8a")), null), armTv))
        assertTrue(Evaluator.fitsDevice(emptySequence(), armTv))
    }

    @Test
    fun theBuildOfAVersionForThisDevicesProcessorIsTheOneOffered() {
        fun build(code: Long) = Release(id = "$code", version = "3.6.1", versionCode = code, assets = listOf(asset("org.videolan.vlc_$code.apk")))
        val abis = mapOf("13060104" to "arm64-v8a", "13060103" to "x86_64", "13060101" to "armeabi-v7a")
        val builds = listOf(build(13060104), build(13060103), build(13060101))
        val fitsTv = { release: Release -> Evaluator.fitsDevice(sequenceOf(facts("org.videolan.vlc", listOf(abis.getValue(release.id)))), armTv) }
        val picked = ReleaseSelector.select(builds, ReleasePolicy(fallbackToOlder = false), 0, fitsDevice = fitsTv) { it.installable.isNotEmpty() }
        assertEquals(13060101L, picked.candidate!!.versionCode)
        val phone = { release: Release -> Evaluator.fitsDevice(sequenceOf(facts("org.videolan.vlc", listOf(abis.getValue(release.id)))), arm64Phone) }
        assertEquals(13060104L, ReleaseSelector.select(builds, ReleasePolicy(), 0, fitsDevice = phone) { it.installable.isNotEmpty() }.candidate!!.versionCode)
    }

    @Test
    fun aDigestThatCameThroughAHubproxyIsCreditedToIt() {
        val github = io.github.munzzyy.tern.core.model.SourceSpec("github", "https://github.com/example/app")
        assertEquals("gh-proxy.example", Evaluator.digestProxy(github, "gh-proxy.example"))
        assertEquals("gh-proxy.example", Evaluator.digestProxy(github.copy(type = "github-actions"), "gh-proxy.example"))
        assertNull(Evaluator.digestProxy(github, null))
        // A GitHub of its own host and other sources never go through it.
        assertNull(Evaluator.digestProxy(github.copy(url = "https://git.example.org/example/app"), "gh-proxy.example"))
        assertNull(Evaluator.digestProxy(io.github.munzzyy.tern.core.model.SourceSpec("fdroid", "https://f-droid.org/packages/org.example"), "gh-proxy.example"))
    }

    @Test
    fun theHubproxySettingSaysItCanChangeWhatGitHubSeemsToPublish() {
        val text = java.io.File("src/main/res/values/strings_safety.xml").readText()
        val effect = Regex("""name="github_proxy_effect_trust">([^<]*)<""").find(text)!!.groupValues[1]
        assertTrue(effect, "can change what comes back" in effect)
        assertTrue(effect, "first install through it trusts the hubproxy" in effect)
        assertTrue(effect, "pinning does not reach it" in effect)
    }
}
