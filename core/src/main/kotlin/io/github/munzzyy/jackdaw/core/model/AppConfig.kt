package io.github.munzzyy.jackdaw.core.model

data class SourceSpec(
    /** Matches [io.github.munzzyy.jackdaw.core.source.Source.type]. */
    val type: String,
    /** Canonical URL of the project or repository, always https. */
    val url: String,
    /** Source-specific options, string-typed so they survive export and import unchanged. */
    val options: Map<String, String> = emptyMap(),
) {
    fun option(key: String): String? = options[key]?.takeIf { it.isNotEmpty() }

    fun flag(key: String, default: Boolean = false): Boolean = options[key]?.toBooleanStrictOrNull() ?: default
}

enum class UpdateMode {
    /** Only show that an update exists. */
    NOTIFY,

    /** Download and install in the background when the system allows it without a prompt. */
    AUTO,

    /** Never check in the background. */
    MANUAL,
}

data class ReleasePolicy(
    val includePrereleases: Boolean = false,
    /** Applied to the release id (tag). Releases that do not match are ignored. */
    val tagFilter: String? = null,
    val titleFilter: String? = null,
    val notesFilter: String? = null,
    /** Applied to the raw version; the first capture group (or whole match) becomes the version. */
    val versionExtract: String? = null,
    /** Hide a release until it has been public for this many days. */
    val minAgeDays: Int = 0,
    /** Release id the user chose to skip. */
    val skippedReleaseId: String? = null,
    /** When the newest release has no usable file, fall back to the newest one that does. */
    val fallbackToOlder: Boolean = true,
)

data class AssetPolicy(
    val include: String? = null,
    val exclude: String? = null,
    /** Narrow by device architecture when several files are offered. */
    val matchDevice: Boolean = true,
)

data class AppConfig(
    /** Local identity. Stays the same when the package name becomes known. */
    val id: String,
    val source: SourceSpec,
    val name: String,
    val author: String? = null,
    val packageName: String? = null,
    val releases: ReleasePolicy = ReleasePolicy(),
    val assets: AssetPolicy = AssetPolicy(),
    val updates: UpdateMode = UpdateMode.NOTIFY,
    /** Only follow releases; never download or install. */
    val trackOnly: Boolean = false,
    /** SHA-256 of each signing certificate accepted for this app, lowercase hex without colons. */
    val pinnedSigners: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val favorite: Boolean = false,
    val notes: String? = null,
)
