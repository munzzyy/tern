package io.github.munzzyy.tern.ui.text

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theStatusDecidesWhetherAChangeIsShown() {
        val update = testRow(status = AppStatus.UPDATE_AVAILABLE, installed = "0.4.0", offered = "v0.4.4")
        assertEquals(VersionChange.Change("0.4.0", "0.4.4"), versionChange(update))
        assertEquals(VersionChange.Same("0.4.4"), versionChange(testRow(status = AppStatus.UP_TO_DATE, installed = "0.4.4", offered = "v0.4.4")))
        assertEquals(VersionChange.Change("4.0", "4.1"), versionChange(testRow(status = AppStatus.BLOCKED, installed = "4.0", offered = "4.1")))
        assertEquals(VersionChange.Same("1.4.2"), versionChange(testRow(status = AppStatus.UP_TO_DATE, installed = "1.4.2", offered = "1.5.0")))
        assertEquals(VersionChange.Same("2.0"), versionChange(testRow(installed = "2.0", offered = "2.0")))
        assertEquals(VersionChange.Same("3.0"), versionChange(testRow(installed = null, offered = "3.0")))
        assertEquals(VersionChange.Same("1.0"), versionChange(testRow(installed = "1.0", offered = null)))
        assertNull(versionChange(testRow(installed = null, offered = null)))
    }

    @Test
    fun theInstalledReleaseIsFoundByCodeOrByVersionWithoutItsV() {
        val installed = io.github.munzzyy.tern.core.engine.InstalledApp("org.example.app", "0.4.4", 44, emptyList())
        fun rel(version: String, code: Long? = null) = io.github.munzzyy.tern.core.model.Release("v", version, versionCode = code)
        assertTrue(isInstalledRelease(rel("v0.4.4"), installed))
        assertTrue(isInstalledRelease(rel("0.4.4"), installed))
        assertTrue(isInstalledRelease(rel("renamed", 44), installed))
        assertFalse(isInstalledRelease(rel("0.4.4", 45), installed))
        assertFalse(isInstalledRelease(rel("v0.4.5"), installed))
        assertFalse(isInstalledRelease(rel(""), installed.copy(versionName = "")))
        assertFalse(isInstalledRelease(rel("0.4.4"), null))
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
        assertEquals(null to "apps.example.net/download", sourceParts(SourceSpec("html", "https://apps.example.net/download")))
        assertEquals("GitHub" to "github.com/a/b", sourceParts(SourceSpec("github", "https://github.com/a/b")))
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
    fun intervalStopsKeepTheCurrentValue() {
        assertEquals(INTERVAL_STOPS, intervalStops(360))
        assertEquals((INTERVAL_STOPS + 2000).sorted(), intervalStops(2000))
        assertEquals(INTERVAL_STOPS, intervalStops(-4))
    }

    @Test
    fun theSliderStopsWhereObtainiumsDoes() {
        // Obtainium's slider runs through these, off first; the stops between them are Tern's own.
        val obtainium = listOf(0, 15, 30, 60, 120, 180, 360, 720, 1440, 4320, 10080, 20160, 43200)
        assertTrue(INTERVAL_STOPS.containsAll(obtainium))
        assertEquals(INTERVAL_STOPS.sorted(), INTERVAL_STOPS)
        assertEquals(43200, INTERVAL_STOPS.last())
        assertTrue(INTERVAL_STOPS.none { it in 1 until 15 })
    }

    @Test
    fun anIntervalIsNamedInTheLargestUnitThatDividesIt() {
        assertEquals(IntervalUnit.OFF to 0, intervalUnit(0))
        assertEquals(IntervalUnit.MINUTES to 15, intervalUnit(15))
        assertEquals(IntervalUnit.MINUTES to 90, intervalUnit(90))
        assertEquals(IntervalUnit.HOURS to 6, intervalUnit(360))
        assertEquals(IntervalUnit.DAYS to 1, intervalUnit(1440))
        assertEquals(IntervalUnit.DAYS to 30, intervalUnit(43200))
    }

    @Test
    fun datesFollowTheZone() {
        val ms = 1_790_000_000_000L
        assertEquals(formatDate(ms, ZoneOffset.UTC, Locale.US), formatDate(ms, ZoneOffset.UTC, Locale.US))
        assertTrue(formatDate(ms, ZoneOffset.UTC, Locale.US).contains("2026"))
    }

    @Test
    fun aVersionAndATagAreShownSpelledAlike() {
        assertEquals("0.4.0" to "0.4.4", spelledAlike("0.4.0", "v0.4.4"))
        assertEquals("1.0" to "2.0", spelledAlike("v1.0", "2.0"))
        assertEquals("1.0" to "2.0", spelledAlike("V1.0", "2.0"))
    }

    @Test
    fun versionsThatAlreadyAgreeAreLeftAsTheyAre() {
        assertEquals("v1.0" to "v2.0", spelledAlike("v1.0", "v2.0"))
        assertEquals("1.0" to "2.0", spelledAlike("1.0", "2.0"))
        assertEquals("vanilla" to "2.0", spelledAlike("vanilla", "2.0"))
        assertEquals("v" to "2.0", spelledAlike("v", "2.0"))
        assertEquals("nightly" to "release-7", spelledAlike("nightly", "release-7"))
    }
}
