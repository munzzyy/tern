package io.github.munzzyy.jackdaw.ui.text

import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class FormatTest {
    @Test
    fun bytesUseDecimalUnits() {
        assertEquals("999 B", formatBytes(999, Locale.US))
        assertEquals("1.0 KB", formatBytes(1000, Locale.US))
        assertEquals("24 MB", formatBytes(24_000_000, Locale.US))
        assertEquals("1.5 GB", formatBytes(1_500_000_000, Locale.US))
        assertEquals("0 B", formatBytes(-5, Locale.US))
        assertTrue(formatBytes(Long.MAX_VALUE, Locale.US).endsWith("TB"))
    }

    @Test
    fun isolatedValuesAreWrappedInFirstStrongIsolates() {
        assertEquals("⁨1.4.2⁩", isolate("1.4.2"))
    }

    @Test
    fun percentIsClamped() {
        assertEquals(0, percentOf(-1f))
        assertEquals(50, percentOf(0.5f))
        assertEquals(100, percentOf(3f))
    }

    @Test
    fun versionChangeShowsBothSidesOnlyWhenTheyDiffer() {
        assertEquals(VersionChange.Change("1.4.2", "1.5.0"), versionChange(testRow(installed = "1.4.2", offered = "1.5.0")))
        assertEquals(VersionChange.Same("2.0"), versionChange(testRow(installed = "2.0", offered = "2.0")))
        assertEquals(VersionChange.Same("3.0"), versionChange(testRow(installed = null, offered = "3.0")))
        assertEquals(VersionChange.Same("1.0"), versionChange(testRow(installed = "1.0", offered = null)))
        assertNull(versionChange(testRow(installed = null, offered = null)))
    }

    @Test
    fun releaseWithoutVersionNeverPrintsAnEmptyVersion() {
        val update = testRow(status = AppStatus.UPDATE_AVAILABLE, installed = "1.4.2", offered = "")
        assertEquals(VersionChange.NewFile("1.4.2"), versionChange(update))
        assertEquals(VersionChange.Unknown, versionChange(testRow(status = AppStatus.NOT_INSTALLED, installed = null, offered = "  ")))
        assertEquals(VersionChange.Same("1.4.2"), versionChange(testRow(status = AppStatus.UP_TO_DATE, installed = "1.4.2", offered = "")))
        assertEquals(VersionChange.Unknown, versionChange(testRow(status = AppStatus.NOT_INSTALLED, installed = "", offered = "")))
        assertNull(knownVersion(" "))
        assertEquals("2.0", knownVersion(" 2.0 "))
    }

    @Test
    fun hostAndShortUrl() {
        assertEquals("codeberg.org", hostOf("https://user@Codeberg.org/a/b?x#y"))
        assertEquals("github.com/example/app", shortUrl("https://github.com/example/app/"))
        assertEquals("GitHub", sourceName(SourceSpec("github", "https://github.com/a/b")))
        assertEquals("apps.example.net", sourceName(SourceSpec("html", "https://apps.example.net/download")))
    }

    @Test
    fun avatarLetterSkipsPunctuationAndKeepsSurrogates() {
        assertEquals("P", avatarLetter("  pocket"))
        assertEquals("A", avatarLetter("(alpha)"))
        assertEquals("م", avatarLetter("مفكرة"))
        assertEquals("𐐀", avatarLetter("𐐨abc"))
        assertEquals("?", avatarLetter("!!!"))
        assertEquals("?", avatarLetter(""))
    }

    @Test
    fun avatarColourIsStableAndInRange() {
        val ids = (0 until 500).map { "app$it" }
        val indices = ids.map { avatarColorIndex(it, 10) }
        assertTrue(indices.all { it in 0 until 10 })
        assertEquals(indices, ids.map { avatarColorIndex(it, 10) })
        assertTrue(indices.toSet().size >= 8)
        assertEquals(avatarColorIndex("trailmap", 10), avatarColorIndex("trailmap", 10))
    }

    @Test
    fun minutesRoundUp() {
        assertEquals(0, minutesUntil(1000, 2000))
        assertEquals(1, minutesUntil(60_001, 60_000))
        assertEquals(37, minutesUntil(37 * 60_000L, 0))
    }

    @Test
    fun intervalChoicesKeepTheCurrentValue() {
        assertEquals(listOf(0, 1, 3, 6, 12, 24), intervalChoices(6))
        assertEquals(listOf(0, 1, 3, 6, 12, 24, 48), intervalChoices(48))
        assertEquals(listOf(0, 1, 3, 6, 12, 24), intervalChoices(-4))
    }

    @Test
    fun datesFollowTheZone() {
        val ms = 1_790_000_000_000L
        assertEquals(formatDate(ms, ZoneOffset.UTC, Locale.US), formatDate(ms, ZoneOffset.UTC, Locale.US))
        assertTrue(formatDate(ms, ZoneOffset.UTC, Locale.US).contains("2026"))
    }
}
