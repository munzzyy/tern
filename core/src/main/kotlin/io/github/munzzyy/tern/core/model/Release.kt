package io.github.munzzyy.tern.core.model

import io.github.munzzyy.tern.core.version.Version

enum class AssetKind { APK, BUNDLE, ARCHIVE, CHECKSUM, SIGNATURE, OTHER }

enum class NotesFormat { MARKDOWN, HTML, PLAIN }

data class Asset(
    val name: String,
    val url: String,
    val size: Long? = null,
    /** Lowercase hex, as published by the source itself (API digest or signed index). */
    val sha256: String? = null,
    val kind: AssetKind = kindOf(name),
    /** True when the download needs the source's token (for example a CI artifact). */
    val needsAuth: Boolean = false,
    /** SHA-256 of the signing certificates, when a signed index names them. Lowercase hex. */
    val signers: List<String> = emptyList(),
    /** True for an archive the source knows to hold the app, such as a CI artifact; it is ranked like an installable file. */
    val holdsApps: Boolean = false,
    /**
     * The addresses of the splits that go with the base at [url], for a source that names each file
     * of an app by itself. They are fetched one by one and installed with the base as one bundle.
     */
    val parts: List<String> = emptyList(),
) {
    companion object {
        /** The most splits one asset may name. */
        const val MAX_PARTS = 256

        private val ARCHIVES = listOf(".zip", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz")

        fun kindOf(name: String): AssetKind {
            val n = name.lowercase().substringBefore('?')
            return when {
                n.endsWith(".apk") -> AssetKind.APK
                n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm") -> AssetKind.BUNDLE
                n.endsWith(".sha256") || n.endsWith(".sha256sum") || n.endsWith(".sha256sums") ||
                    n.endsWith(".sha256.txt") || n.substringAfterLast('/').let { it.contains("sha256sum") || it.contains("checksum") } -> AssetKind.CHECKSUM
                n.endsWith(".asc") || n.endsWith(".sig") || n.endsWith(".minisig") || n.endsWith(".idsig") -> AssetKind.SIGNATURE
                ARCHIVES.any { n.endsWith(it) } -> AssetKind.ARCHIVE
                else -> AssetKind.OTHER
            }
        }
    }
}

data class Release(
    /** Stable identity inside its source: a tag, a build number, a versionCode. Never shown as the version. */
    val id: String,
    val version: String,
    val versionCode: Long? = null,
    val title: String? = null,
    val notes: String? = null,
    val notesFormat: NotesFormat = NotesFormat.MARKDOWN,
    val publishedAtMs: Long? = null,
    val prerelease: Boolean = false,
    val pageUrl: String? = null,
    val assets: List<Asset> = emptyList(),
    /** Set by a source that knows which release the project marked as its latest. It comes first whatever the order. */
    val latest: Boolean = false,
    /** The size of the release's file as a source that lists no file states it, such as a store that is only followed. */
    val fileSize: Long? = null,
    /** The project's source at this release, as the forge packs it. Offered to save and never installed, so kept apart from [assets]. */
    val sourceArchives: List<Asset> = emptyList(),
) {
    /** Files that can be installed as they are, and archives the source knows to hold the app. */
    val installable: List<Asset> get() = assets.filter { it.kind == AssetKind.APK || it.kind == AssetKind.BUNDLE || (it.kind == AssetKind.ARCHIVE && it.holdsApps) }

    /** Every file a person may save: the release's own files, then the archives of its source. */
    val savable: List<Asset> get() = assets + sourceArchives

    /** True when the source marks it as a pre-release, and when its version says so: 17.9.6-RC-1 is one whatever the source marks. */
    val countsAsPrerelease: Boolean get() = prerelease || Version.parse(version).isPrerelease
}
