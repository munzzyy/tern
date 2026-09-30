package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.real.Arrivals
import io.github.munzzyy.tern.engine.real.BuiltInPins
import io.github.munzzyy.tern.engine.real.Detector
import io.github.munzzyy.tern.engine.real.RealEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a file or a link may change of an app that is already in the list, once the person asks. */
class ReplacedSettingsTest {
    private val builtIn = BuiltInPins(emptyList())
    private val pin = "a".repeat(64)
    private val otherPin = "b".repeat(64)
    private val existing = AppConfig(
        id = "kept",
        source = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"),
        name = "App",
        packageName = "org.example.app",
        pinnedSigners = listOf(pin),
        updates = UpdateMode.MANUAL,
        releases = ReleasePolicy(skippedReleaseId = "v2"),
    )
    private val file = existing.copy(
        id = "other",
        name = "Something else",
        packageName = "org.example.other",
        pinnedSigners = listOf(otherPin),
        updates = UpdateMode.AUTO,
        releases = ReleasePolicy(includePrereleases = true, titleFilter = "stable"),
        assets = AssetPolicy(include = "arm64"),
        customName = "Mine",
        categories = listOf("Tools"),
    )

    @Test
    fun theFilesSettingsAreTakenAndWhoMaySignTheAppIsNot() {
        val replaced = Arrivals.replaced(existing, file, builtIn)
        assertEquals("kept", replaced.id)
        assertEquals("App", replaced.name)
        assertEquals("org.example.app", replaced.packageName)
        assertEquals(listOf(pin), replaced.pinnedSigners)
        assertEquals("stable", replaced.releases.titleFilter)
        assertTrue(replaced.releases.includePrereleases)
        assertEquals("v2", replaced.releases.skippedReleaseId)
        assertEquals("arm64", replaced.assets.include)
        assertEquals("Mine", replaced.customName)
        assertEquals(listOf("Tools"), replaced.categories)
    }

    @Test
    fun anAppThatDidNotInstallByItselfDoesNotStartTo() {
        assertEquals(UpdateMode.MANUAL, Arrivals.replaced(existing, file, builtIn).updates)
        assertEquals(UpdateMode.AUTO, Arrivals.replaced(existing.copy(updates = UpdateMode.AUTO), file, builtIn).updates)
        assertEquals(UpdateMode.NOTIFY, Arrivals.replaced(existing.copy(updates = UpdateMode.AUTO), file.copy(updates = UpdateMode.NOTIFY), builtIn).updates)
    }

    @Test
    fun anAppWithoutPinsOrPackageTakesTheFilesAndARepositoryKeepsItsKey() {
        val bare = existing.copy(pinnedSigners = emptyList(), packageName = null)
        val replaced = Arrivals.replaced(bare, file, builtIn)
        assertEquals(listOf(otherPin), replaced.pinnedSigners)
        assertEquals("org.example.other", replaced.packageName)

        val repo = SourceSpec(SourceTypes.FDROID_REPO, "https://example.org/fdroid/repo", mapOf(SourceOptions.PACKAGE to "org.example.app", SourceOptions.FINGERPRINT to "c".repeat(64)))
        val fromFile = repo.copy(options = mapOf(SourceOptions.PACKAGE to "org.example.app", SourceOptions.FINGERPRINT to "d".repeat(64)))
        val kept = Arrivals.replaced(existing.copy(source = repo), file.copy(source = fromFile), builtIn)
        assertEquals("c".repeat(64), kept.source.option(SourceOptions.FINGERPRINT))
    }

    @Test
    fun theAppsOfOneRepositoryAreToldApartByTheirPackage() {
        val a = SourceSpec(SourceTypes.FDROID_REPO, "https://example.org/fdroid/repo", mapOf(SourceOptions.PACKAGE to "org.example.a"))
        val b = a.copy(options = mapOf(SourceOptions.PACKAGE to "org.example.b"))
        assertFalse(RealEngine.sameSource(a, b))
        assertTrue(RealEngine.sameSource(a, a.copy(url = "https://EXAMPLE.org/fdroid/repo", options = a.options + (SourceOptions.FINGERPRINT to pin))))
        val github = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app")
        assertTrue(RealEngine.sameSource(github, github.copy(options = mapOf(SourceOptions.VERIFY_LATEST to "true"))))
        assertFalse(RealEngine.sameSource(github, github.copy(type = SourceTypes.GITHUB_ACTIONS)))
    }

    @Test
    fun aLinkOfTernOrObtainiumIsNeverSearchedFor() {
        assertTrue(Detector.isAppLink("obtainium://settings/x"))
        assertTrue(Detector.isAppLink("TERN://remove"))
        assertFalse(Detector.isAppLink("tern reader"))
        assertFalse(Detector.isAppLink("https://example.org"))
    }
}
