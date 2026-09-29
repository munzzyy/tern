package io.github.munzzyy.stamp.core.model

import io.github.munzzyy.stamp.core.version.Version

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
) {
    companion object {
        fun kindOf(name: String): AssetKind {
            val n = name.lowercase().substringBefore('?')
            return when {
                n.endsWith(".apk") -> AssetKind.APK
                n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm") -> AssetKind.BUNDLE
                n.endsWith(".sha256") || n.endsWith(".sha256sum") || n.endsWith(".sha256sums") ||
                    n.endsWith(".sha256.txt") || n.substringAfterLast('/').let { it.contains("sha256sum") || it.contains("checksum") } -> AssetKind.CHECKSUM
                n.endsWith(".asc") || n.endsWith(".sig") || n.endsWith(".minisig") || n.endsWith(".idsig") -> AssetKind.SIGNATURE
                n.endsWith(".zip") || n.endsWith(".tar.gz") || n.endsWith(".tgz") -> AssetKind.ARCHIVE
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
) {
    val installable: List<Asset> get() = assets.filter { it.kind == AssetKind.APK || it.kind == AssetKind.BUNDLE }

    /** True when the source marks it as a pre-release, and when its version says so: 17.9.6-RC-1 is one whatever the source marks. */
    val countsAsPrerelease: Boolean get() = prerelease || Version.parse(version).isPrerelease
}
