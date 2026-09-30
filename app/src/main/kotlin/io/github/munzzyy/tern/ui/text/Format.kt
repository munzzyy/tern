package io.github.munzzyy.tern.ui.text

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Wraps a value in first-strong isolates so a version or size keeps its own order inside a right-to-left sentence. */
fun isolate(value: String): String = "\u2068$value\u2069"

/**
 * A fingerprint, a file name or an address reads left to right in every language. Left to its
 * first strong character, one that starts with digits is rearranged inside a right-to-left
 * paragraph: "35:D2:6C" was drawn with the 35 at the far end.
 */
fun ltr(value: String): String = "\u2066$value\u2069"

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1000) return "${bytes.coerceAtLeast(0)} B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val pattern = if (value < 10) "%.1f %s" else "%.0f %s"
    return String.format(locale, pattern, value, UNITS[unit])
}

fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()

/** What a row says about versions. A release read from a bare file link can have no version yet. */
sealed interface VersionChange {
    data class Same(val version: String) : VersionChange

    data class Change(val from: String, val to: String) : VersionChange

    /** Installed [installed], and a newer file whose version is only known once it is read. */
    data class NewFile(val installed: String) : VersionChange

    data object Unknown : VersionChange
}

/** Null for a missing or blank version, so no label ever prints an empty gap. */
fun knownVersion(version: String?): String? = version?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The status decides whether a change is shown, never a comparison of the two strings: an installed
 * "0.4.4" and a tag "v0.4.4" are the same release.
 */
fun versionChange(row: AppRow): VersionChange? {
    val offered = knownVersion(row.latest?.version)
    val installed = knownVersion(row.installed?.versionName)
    val hasRelease = row.latest != null
    val changing = isUpdate(row) || row.status == AppStatus.BLOCKED || row.progress != null
    return when {
        changing && installed != null && offered != null -> spelledAlike(installed, offered).let { (from, to) -> VersionChange.Change(from, to) }
        changing && installed != null && hasRelease -> VersionChange.NewFile(installed)
        installed != null -> VersionChange.Same(installed)
        offered != null -> VersionChange.Same(offered)
        hasRelease -> VersionChange.Unknown
        else -> null
    }
}

/**
 * An app calls itself "0.4.0" and its project tags the next release "v0.4.4". Side by side that
 * reads as a mistake, so when only one of the two carries the "v", it is shown without.
 */
fun spelledAlike(installed: String, offered: String): Pair<String, String> {
    val a = withoutV(installed)
    val b = withoutV(offered)
    return if ((a == installed) != (b == offered)) a to b else installed to offered
}

private fun withoutV(version: String): String =
    if (version.length > 1 && version[0] in "vV" && version[1].isDigit()) version.substring(1) else version

/** Whether [release] is what is installed: by version code when both know one, else by version without a leading "v". */
fun isInstalledRelease(release: Release, installed: InstalledApp?): Boolean {
    if (installed == null) return false
    release.versionCode?.let { return it == installed.versionCode }
    val a = knownVersion(release.version)?.removePrefix("v")?.removePrefix("V") ?: return false
    val b = knownVersion(installed.versionName)?.removePrefix("v")?.removePrefix("V") ?: return false
    return a == b
}

/** "https://github.com/example/app/" becomes "github.com/example/app". */
fun shortUrl(url: String): String = url.substringAfter("://", url).trimEnd('/')

fun hostOf(url: String): String {
    val rest = url.substringAfter("://", url)
    return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').lowercase()
}

/** Human name of a source type, falling back to the host for anything unknown. */
fun sourceName(spec: SourceSpec): String = when (spec.type) {
    SourceTypes.GITHUB -> "GitHub"
    SourceTypes.GITHUB_ACTIONS -> "GitHub Actions"
    SourceTypes.GITLAB -> "GitLab"
    SourceTypes.FORGEJO -> "Forgejo"
    SourceTypes.FDROID -> "F-Droid"
    SourceTypes.FDROID_REPO -> "F-Droid repository"
    SourceTypes.JENKINS -> "Jenkins"
    SourceTypes.SOURCEHUT -> "SourceHut"
    SourceTypes.SOURCEFORGE -> "SourceForge"
    else -> hostOf(spec.url)
}

/** The source's name, null when it would only repeat the host, and its short address. */
fun sourceParts(spec: SourceSpec): Pair<String?, String> {
    val name = sourceName(spec)
    return (if (name == hostOf(spec.url)) null else name) to shortUrl(spec.url)
}

private const val LETTER_FALLBACK = "?"

/** First visible character of the name, uppercased, whole code point so surrogate pairs survive. */
fun avatarLetter(name: String): String {
    val trimmed = name.trim()
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp)).uppercase()
        i += Character.charCount(cp)
    }
    return LETTER_FALLBACK
}

/** FNV-1a, so an app keeps its colour across releases and devices. */
fun avatarColorIndex(id: String, count: Int): Int {
    require(count > 0)
    var h = 0x811c9dc5.toInt()
    for (c in id) {
        h = h xor c.code
        h *= 0x01000193
    }
    return Math.floorMod(h, count)
}

/** Minutes until [atMs], rounded up, never below one. */
fun minutesUntil(atMs: Long, nowMs: Long): Long {
    val diff = atMs - nowMs
    if (diff <= 0) return 0
    return (diff + 59_999) / 60_000
}

/** Minutes between background checks to offer, from a quarter of an hour to a month, and the one chosen now. */
fun intervalChoices(current: Int): List<Int> =
    (listOf(0, 15, 30, 60, 180, 360, 720, 1440, 2880, 4320, 10080, 20160, 43200) + current).filter { it >= 0 }.distinct().sorted()

/** How an interval of [minutes] is named: in days, hours or minutes, whichever divides it evenly. */
enum class IntervalUnit { OFF, MINUTES, HOURS, DAYS }

fun intervalUnit(minutes: Int): Pair<IntervalUnit, Int> = when {
    minutes <= 0 -> IntervalUnit.OFF to 0
    minutes % (24 * 60) == 0 -> IntervalUnit.DAYS to minutes / (24 * 60)
    minutes % 60 == 0 -> IntervalUnit.HOURS to minutes / 60
    else -> IntervalUnit.MINUTES to minutes
}

fun formatDate(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))

fun formatTime(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))
