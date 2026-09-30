package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.engine.ExportFormat

/**
 * The name of the kept export's file. A name the person typed loses the characters no file name
 * can hold, as in Obtainium, and always ends in .json, so that an import finds the file again.
 */
object KeptExportName {
    const val MAX_LENGTH = 80
    private const val SUFFIX = ".json"
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** [name] as a file can be called, or null when nothing of it is left. */
    fun clean(name: String?): String? {
        val kept = name.orEmpty().filter { it !in FORBIDDEN && !it.isISOControl() }.trim()
        val bare = if (kept.endsWith(SUFFIX, ignoreCase = true)) kept.dropLast(SUFFIX.length) else kept
        val base = bare.trim().trimStart('.').trim().take(MAX_LENGTH - SUFFIX.length)
        return if (base.isEmpty()) null else base + SUFFIX
    }

    /** The file the kept export is written to. */
    fun of(name: String?, format: ExportFormat): String = clean(name) ?: usual(format)

    fun usual(format: ExportFormat): String = when (format) {
        ExportFormat.TERN -> "tern-apps.json"
        ExportFormat.OBTAINIUM -> "obtainium-export.json"
    }
}
