package io.github.munzzyy.tern.core.model

data class SourceSpec(
    /** Matches [io.github.munzzyy.tern.core.source.Source.type]. */
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

/** Where a release's version is read from, before [ReleasePolicy.versionExtract] is applied. */
enum class VersionFrom {
    /** The version the source gives, usually the tag. */
    TAG,

    /** The release title, where it is not blank. */
    TITLE,

    /**
     * The day and time the release was published, in UTC, written yyyy.MM.dd.HHmm. The time is
     * always there, so two releases of one day stay apart and a release's version never changes
     * with its neighbours. For sources whose version strings say nothing. Undated releases keep theirs.
     */
    DATE,
}

/** Which release counts as the newest. A release the source marks as latest comes first in every order. */
enum class ReleaseOrder {
    /** Highest version first; releases without a version follow in the order the source gave. */
    VERSION,

    /** Most recently published first; undated releases follow in the order the source gave. */
    DATE,

    /** Exactly the order the source gave. */
    SOURCE,

    /** Natural order of the version text, numbers read as numbers, highest first. */
    NAME,
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
    /**
     * Which part of the [versionExtract] match becomes the version: `N` or `$N` for group N (0 is the
     * whole match), or a template such as `$1.$2`, where `\$` writes a dollar sign. A group the
     * pattern lacks counts as no match. Null: the first group, else the whole match.
     */
    val matchGroup: String? = null,
    /** Where the version is read from: the tag, the title or the publishing date. */
    val versionFrom: VersionFrom = VersionFrom.TAG,
    /** Which release counts as the newest: by version, by date, as the source gave them, or by name. */
    val order: ReleaseOrder = ReleaseOrder.VERSION,
    /** Pass over this many of the newest releases that would otherwise be chosen, 0 to 5. */
    val stayBehind: Int = 0,
    /** Applied to the version once extracted. Releases whose version does not match are ignored. */
    val versionFilter: String? = null,
)

data class AssetPolicy(
    val include: String? = null,
    val exclude: String? = null,
    /** Narrow by device architecture when several files are offered. */
    val matchDevice: Boolean = true,
    /** Offer .zip and tar archives as files that hold the app. One ranks below a plain APK of the same score. */
    val archives: Boolean = false,
    /**
     * Applied to the names of the files inside an archive or bundle; only those that match are
     * installed. Checked with the other filters, applied by the install.
     */
    val innerFilter: String? = null,
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
    /** The name the person chose. Shown instead of [name], and never replaced by what a source or an install says. */
    val customName: String? = null,
    /** The author the person chose, shown instead of [author] and kept the same way. */
    val customAuthor: String? = null,
    /** No notification that an update is available. */
    val muted: Boolean = false,
    /** Check the source again right before a download, for sources whose file addresses do not last. */
    val refreshFirst: Boolean = false,
    /** Name Google Play as the installer when a privileged installer (Shizuku or root) installs it. */
    val playInstaller: Boolean = false,
) {
    val shownName: String get() = customName?.takeIf { it.isNotBlank() } ?: name

    val shownAuthor: String? get() = customAuthor?.takeIf { it.isNotBlank() } ?: author
}
