package io.github.munzzyy.stamp.ui.detail

import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.SourceSpec
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
    fun categoriesAreTrimmedAndCapped() {
        assertEquals(listOf("Maps", "Tools"), parseCategories(" Maps, ,Tools,Maps "))
        assertEquals(20, parseCategories((1..50).joinToString(",") { "c$it" }).size)
        assertEquals(40, parseCategories("x".repeat(100)).single().length)
    }

    @Test
    fun draftRoundTripsAndBlankMeansUnset() {
        val config = AppConfig("id", SourceSpec("github", "https://github.com/example/app"), "App", categories = listOf("A"))
        val draft = PatternDraft.of(config).copy(include = "  arm64 ", exclude = "   ", categories = "A, B")
        val saved = draft.applyTo(config)
        assertEquals("arm64", saved.assets.include)
        assertNull(saved.assets.exclude)
        assertEquals(listOf("A", "B"), saved.categories)
        assertEquals(PatternDraft.of(saved), PatternDraft.of(saved.copy()))
        assertEquals(config, PatternDraft.of(config).applyTo(config))
    }
}
