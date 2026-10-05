package io.github.munzzyy.tern.ui.detail

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.ui.add.isPackageName
import io.github.munzzyy.tern.ui.apps.cleanCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEditsTest {
    private val hex = "ab".repeat(32)

    @Test
    fun fingerprintsArePastedInAnyForm() {
        assertEquals(hex, normalizeFingerprint(hex.uppercase()))
        assertEquals(hex, normalizeFingerprint(hex.chunked(2).joinToString(":")))
        assertEquals(hex, normalizeFingerprint(" " + hex.chunked(2).joinToString(" ") + "\n"))
    }

    @Test
    fun partialOrForeignFingerprintsAreRefused() {
        assertNull(normalizeFingerprint(hex.dropLast(2)))
        assertNull(normalizeFingerprint(hex + "ab"))
        assertNull(normalizeFingerprint("zz".repeat(32)))
        assertNull(normalizeFingerprint(""))
    }

    @Test
    fun patternsAreCheckedBeforeSaving() {
        assertTrue(isValidPattern(""))
        assertTrue(isValidPattern("arm64|universal"))
        assertFalse(isValidPattern("(unclosed"))
        assertEquals(setOf("include", "version"), PatternDraft(include = "[", version = "(").invalid)
    }

    @Test
    fun aMatchGroupIsANumberOrATemplateOfGroups() {
        assertTrue(isValidMatchGroup(""))
        assertTrue(isValidMatchGroup("2"))
        assertTrue(isValidMatchGroup("$1.$2"))
        assertFalse(isValidMatchGroup("first"))
        assertEquals(setOf("matchGroup", "versionFilter"), PatternDraft(matchGroup = "x", versionFilter = "(").invalid)
    }

    @Test
    fun theNewFieldsGoIntoTheAppAndBlankOnesStayUnset() {
        val config = AppConfig("id", SourceSpec("github", "https://github.com/example/app"), "App")
        val saved = PatternDraft.of(config).copy(customName = "  Mine ", customAuthor = " ", matchGroup = "1", versionFilter = "^2", innerFilter = "arm").applyTo(config)
        assertEquals("Mine", saved.customName)
        assertNull(saved.customAuthor)
        assertEquals("Mine", saved.shownName)
        assertEquals("1", saved.releases.matchGroup)
        assertEquals("^2", saved.releases.versionFilter)
        assertEquals("arm", saved.assets.innerFilter)
        assertEquals(PatternDraft.of(saved), PatternDraft.of(saved.copy()))
    }

    @Test
    fun aPackageNameIsCheckedBeforeItIsSaved() {
        assertEquals("org.example.app", packageEntry(" org.example .app\n"))
        assertEquals(255, packageEntry("a".repeat(300)).length)
        assertTrue(isPackageName(packageEntry("org.example.app")))
        assertTrue(isPackageName(""))
        assertFalse(isPackageName("example"))
        assertFalse(isPackageName("org.1example"))
        assertFalse(isPackageName("org..example"))
        assertFalse(isPackageName("org.example-app"))
        assertEquals("org.example.app", packageNameOf("org.example.app"))
        // An empty field takes the name away, and Tern reads it from the app's file again.
        assertNull(packageNameOf(""))
    }

    @Test
    fun categoriesAreTrimmedAndCapped() {
        assertEquals("Maps", cleanCategory("  Maps "))
        assertEquals(40, cleanCategory("x".repeat(100))?.length)
        assertNull(cleanCategory("   "))
    }

    @Test
    fun draftRoundTripsAndBlankMeansUnset() {
        val config = AppConfig("id", SourceSpec("github", "https://github.com/example/app"), "App", categories = listOf("A"))
        val draft = PatternDraft.of(config).copy(include = "  arm64 ", exclude = "   ")
        val saved = draft.applyTo(config)
        assertEquals("arm64", saved.assets.include)
        assertNull(saved.assets.exclude)
        assertEquals(listOf("A"), saved.categories)
        assertEquals(PatternDraft.of(saved), PatternDraft.of(saved.copy()))
        assertEquals(config, PatternDraft.of(config).applyTo(config))
    }

    @Test
    fun eachCardSeesOnlyItsOwnChanges() {
        val config = AppConfig("id", SourceSpec("github", "https://github.com/example/app"), "App")
        val named = PatternDraft.of(config).copy(customName = "Mine")
        assertTrue(named.changedIn(DraftPart.NAME, config))
        assertFalse(named.changedIn(DraftPart.FILES, config))
        assertFalse(named.changedIn(DraftPart.ADVANCED, config))

        val filtered = PatternDraft.of(config).copy(include = "arm64")
        assertTrue(filtered.changedIn(DraftPart.FILES, config))
        assertFalse(filtered.changedIn(DraftPart.NAME, config))
        assertFalse(filtered.changedIn(DraftPart.ADVANCED, config))

        val versioned = PatternDraft.of(config).copy(matchGroup = "2")
        assertEquals(listOf(DraftPart.ADVANCED), DraftPart.entries.filter { versioned.changedIn(it, config) })
    }

    @Test
    fun discardingOneCardKeepsWhatIsTypedInTheOthers() {
        val config = AppConfig("id", SourceSpec("github", "https://github.com/example/app"), "App")
        val both = PatternDraft.of(config).copy(customName = "Mine", include = "arm64", tag = "^v")
        val kept = both.resetPart(DraftPart.FILES, config)
        assertEquals("Mine", kept.customName)
        assertEquals("", kept.include)
        assertEquals("^v", kept.tag)
        assertFalse(kept.changedIn(DraftPart.FILES, config))
        assertTrue(kept.changedIn(DraftPart.NAME, config))
    }

    @Test
    fun aCardSavesItsOwnFieldsAndABadPatternElsewhereDoesNotStopIt() {
        val config = AppConfig("id", SourceSpec("github", "https://github.com/example/app"), "App")
        val both = PatternDraft.of(config).copy(customName = " Mine ", include = "(unclosed", versionFilter = "^2")
        assertEquals(emptySet<String>(), both.invalidIn(DraftPart.NAME))
        assertEquals(setOf("include"), both.invalidIn(DraftPart.FILES))
        assertEquals(emptySet<String>(), both.invalidIn(DraftPart.ADVANCED))

        val saved = both.applyPart(DraftPart.NAME, config)
        assertEquals("Mine", saved.customName)
        assertNull(saved.assets.include)
        assertNull(saved.releases.versionFilter)
        assertEquals(config.copy(customName = "Mine"), saved)
    }
}
