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

/** A group number, or a template that names at least one group, such as $1.$2; blank takes the pattern's own rule. */
fun isValidMatchGroup(text: String): Boolean {
    val t = text.trim()
    return t.isEmpty() || t.all { it in '0'..'9' } || Regex("""\$\d""").containsMatchIn(t)
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
    val versionFilter: String = "",
    val matchGroup: String = "",
    val innerFilter: String = "",
    val customName: String = "",
    val customAuthor: String = "",
) {
    val invalid: Set<String>
        get() = buildSet {
            if (!isValidPattern(include)) add("include")
            if (!isValidPattern(exclude)) add("exclude")
            if (!isValidPattern(tag)) add("tag")
            if (!isValidPattern(title)) add("title")
            if (!isValidPattern(notes)) add("notes")
            if (!isValidPattern(version)) add("version")
            if (!isValidPattern(versionFilter)) add("versionFilter")
            if (!isValidMatchGroup(matchGroup)) add("matchGroup")
            if (!isValidPattern(innerFilter)) add("innerFilter")
        }

    fun applyTo(config: AppConfig): AppConfig = config.copy(
        assets = config.assets.copy(include = include.blankToNull(), exclude = exclude.blankToNull(), innerFilter = innerFilter.blankToNull()),
        releases = config.releases.copy(
            tagFilter = tag.blankToNull(),
            titleFilter = title.blankToNull(),
            notesFilter = notes.blankToNull(),
            versionExtract = version.blankToNull(),
            versionFilter = versionFilter.blankToNull(),
            matchGroup = matchGroup.blankToNull(),
        ),
        customName = customName.blankToNull()?.take(MAX_SHOWN_NAME),
        customAuthor = customAuthor.blankToNull()?.take(MAX_SHOWN_NAME),
    )

    companion object {
        fun of(config: AppConfig) = PatternDraft(
            include = config.assets.include.orEmpty(),
            exclude = config.assets.exclude.orEmpty(),
            tag = config.releases.tagFilter.orEmpty(),
            title = config.releases.titleFilter.orEmpty(),
            notes = config.releases.notesFilter.orEmpty(),
            version = config.releases.versionExtract.orEmpty(),
            versionFilter = config.releases.versionFilter.orEmpty(),
            matchGroup = config.releases.matchGroup.orEmpty(),
            innerFilter = config.assets.innerFilter.orEmpty(),
            customName = config.customName.orEmpty(),
            customAuthor = config.customAuthor.orEmpty(),
        )
    }
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

/** What the package name field keeps of what is typed or pasted: no white space, and no more than a package name can be. */
fun packageEntry(text: String): String = text.filterNot { it.isWhitespace() }.take(MAX_PACKAGE_NAME)

/** The package name the field gives the app: none when it is empty, and then Tern reads it from the app's file again. */
fun packageNameOf(entry: String): String? = entry.blankToNull()

private const val MAX_PACKAGE_NAME = 255

/** The longest name or author a person may give an app, as the export keeps it. */
const val MAX_SHOWN_NAME = 200
