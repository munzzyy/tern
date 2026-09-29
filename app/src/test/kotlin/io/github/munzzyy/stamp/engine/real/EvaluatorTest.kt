package io.github.munzzyy.stamp.engine.real

import io.github.munzzyy.stamp.core.engine.InstalledApp
import io.github.munzzyy.stamp.core.model.Asset
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

    private fun facts(packageName: String) = FileFacts(
        packageName = packageName, versionCode = 1, versionName = "1.0", signers = emptyList(), lineage = emptyList(),
        permissions = emptyList(), minSdk = null, targetSdk = null, testOnly = false, verified = false,
    )

    private fun appConfig(packageName: String?) = io.github.munzzyy.stamp.core.model.AppConfig(
        id = "a", source = io.github.munzzyy.stamp.core.model.SourceSpec("github", "https://example.org/a"), name = "App",
        packageName = packageName,
    )

    @Test
    fun freshInstallWithNoPackageKnownTakesTheBestPickWithoutInspectingOthers() {
        val ranked = listOf(pick("bitwarden.apk"), pick("authenticator.apk"))
        var inspected = 0
        val (chosen, _) = Evaluator.chooseFile(appConfig(null), null, release, ranked) { _, _ -> inspected++; facts("com.x.other") }
        assertEquals("bitwarden.apk", chosen.asset.name)
        assertEquals(1, inspected)
    }

    @Test
    fun freshInstallPrefersTheFileThatReadsAsTheConfiguredPackage() {
        val ranked = listOf(pick("authenticator.apk"), pick("bitwarden.apk"))
        val (chosen, chosenFacts) = Evaluator.chooseFile(appConfig("com.x.password"), null, release, ranked) { asset, _ ->
            if (asset.name == "bitwarden.apk") facts("com.x.password") else facts("com.x.authenticator")
        }
        assertEquals("bitwarden.apk", chosen.asset.name)
        assertEquals("com.x.password", chosenFacts?.packageName)
    }

    @Test
    fun whenNoRankedFileMatchesThePackageTheBestOneIsStillOffered() {
        val ranked = listOf(pick("authenticator.apk"))
        val (chosen, chosenFacts) = Evaluator.chooseFile(appConfig("com.x.password"), null, release, ranked) { _, _ -> facts("com.x.authenticator") }
        assertEquals("authenticator.apk", chosen.asset.name)
        assertEquals("com.x.authenticator", chosenFacts?.packageName)
    }

    @Test
    fun installedAppStillPrefersTheSignerMatchOverPackageAlone() {
        val installedApp = InstalledApp("com.x.password", "0.9", 9, listOf("aa"))
        val device = DeviceApp(installedApp, emptyList(), 34, null, null, emptySet())
        val ranked = listOf(pick("wrongsigner.apk"), pick("rightsigner.apk"))
        val (chosen, _) = Evaluator.chooseFile(appConfig("com.x.password"), device, release, ranked) { asset, _ ->
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
        val (chosen, _) = Evaluator.chooseFile(appConfig("com.x.password"), null, release, ranked) { asset, _ ->
            if (asset.name == "bitwarden.apk") facts("com.x.password") else facts("com.x.authenticator")
        }
        assertTrue(chosen.asset.name != brokenBest.asset.name)
    }
}
