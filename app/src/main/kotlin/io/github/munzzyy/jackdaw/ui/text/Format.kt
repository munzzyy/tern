package io.github.munzzyy.jackdaw.ui.text

import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.engine.AppRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Wraps a value in first-strong isolates so a version or size keeps its own order inside a right-to-left sentence. */
fun isolate(value: String): String = "\u2068$value\u2069"

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

fun versionChange(row: AppRow): VersionChange? {
    val offered = knownVersion(row.latest?.version)
    val installed = knownVersion(row.installed?.versionName)
    val hasRelease = row.latest != null
    return when {
        offered != null && installed != null && installed != offered -> VersionChange.Change(installed, offered)
        offered != null -> VersionChange.Same(offered)
        installed != null && hasRelease && isUpdate(row) -> VersionChange.NewFile(installed)
        installed != null -> VersionChange.Same(installed)
        hasRelease -> VersionChange.Unknown
        else -> null
    }
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

fun intervalChoices(current: Int): List<Int> =
    (listOf(0, 1, 3, 6, 12, 24) + current).filter { it >= 0 }.distinct().sorted()

fun formatDate(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))

fun formatTime(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))
