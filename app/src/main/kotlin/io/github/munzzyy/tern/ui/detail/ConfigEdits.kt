package io.github.munzzyy.tern.ui.detail

import io.github.munzzyy.tern.core.text.PatternException
import io.github.munzzyy.tern.core.text.SafePattern
import io.github.munzzyy.tern.core.model.AppConfig

private val HEX = Regex("[0-9a-f]{64}")

/** Accepts the forms people paste: colons, spaces, either case. Null unless it is a whole SHA-256. */
fun normalizeFingerprint(text: String): String? {
    val clean = text.filterNot { it == ':' || it.isWhitespace() }.lowercase()
    return clean.takeIf { it.length == 64 && HEX.matches(it) }
}

fun isValidPattern(text: String): Boolean {
    if (text.isBlank()) return true
    return try {
        SafePattern.compile(text)
        true
    } catch (_: PatternException) {
        false
    }
}

/** The free-text fields of an app's settings, edited together and saved with one button. */
data class PatternDraft(
    val include: String = "",
    val exclude: String = "",
    val tag: String = "",
    val title: String = "",
    val notes: String = "",
    val version: String = "",
) {
    val invalid: Set<String>
        get() = buildSet {
            if (!isValidPattern(include)) add("include")
            if (!isValidPattern(exclude)) add("exclude")
            if (!isValidPattern(tag)) add("tag")
            if (!isValidPattern(title)) add("title")
            if (!isValidPattern(notes)) add("notes")
            if (!isValidPattern(version)) add("version")
        }

    fun applyTo(config: AppConfig): AppConfig = config.copy(
        assets = config.assets.copy(include = include.blankToNull(), exclude = exclude.blankToNull()),
        releases = config.releases.copy(
            tagFilter = tag.blankToNull(),
            titleFilter = title.blankToNull(),
            notesFilter = notes.blankToNull(),
            versionExtract = version.blankToNull(),
        ),
    )

    companion object {
        fun of(config: AppConfig) = PatternDraft(
            include = config.assets.include.orEmpty(),
            exclude = config.assets.exclude.orEmpty(),
            tag = config.releases.tagFilter.orEmpty(),
            title = config.releases.titleFilter.orEmpty(),
            notes = config.releases.notesFilter.orEmpty(),
            version = config.releases.versionExtract.orEmpty(),
        )
    }
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }
