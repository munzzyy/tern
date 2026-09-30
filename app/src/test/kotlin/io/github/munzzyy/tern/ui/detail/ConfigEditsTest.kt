package io.github.munzzyy.tern.ui.detail

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
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
}
