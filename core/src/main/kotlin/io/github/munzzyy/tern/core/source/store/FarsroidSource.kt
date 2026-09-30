package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.source.web.LinkScanner
import io.github.munzzyy.tern.core.xml.XmlScanner

/**
 * Farsroid. The app's page names the post and the version its download box is for; the site's
 * /api/download-box/ answers with that box, and its links to installable files on Farsroid's own
 * servers become the files of the one release.
 */
class FarsroidSource : Source {
    override val type: String = SourceTypes.FARSROID

    override val republishes: Boolean get() = true

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase()
        if (host != "farsroid.com" && host != "www.farsroid.com") return null
        // An address of a file is left to the source for plain downloads.
        if (INSTALLABLE.any { uri.path.orEmpty().lowercase().endsWith(it) }) return null
        val slug = uri.rawPath?.split('/')?.firstOrNull { it.isNotEmpty() } ?: return null
        if (!SLUG.matches(slug) || slug.lowercase() in NOT_APPS) return null
        return SourceSpec(type, "$SITE/$slug")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val html = read(spec.url, context)
        val box = DOWNLOAD_BOX.find(html)?.value ?: throw SourceException(SourceErrorKind.NO_RELEASES, "The page at ${spec.url} has no download box")
        val postId = attribute(box, "data-post-id")?.takeIf { NUMBER.matches(it) }
        val version = attribute(box, "data-post-version")?.trim()?.takeIf { it.isNotEmpty() }
        if (postId == null || version == null) throw SourceException(SourceErrorKind.NO_RELEASES, "The download box at ${spec.url} names no version")

        val boxUrl = "$SITE/api/download-box/?post_id=$postId&post_version=${Urls.encodeSegment(version)}"
        val content = Json.parseObject(read(boxUrl, context)).obj("data")?.string("content").orEmpty()
        val assets = LinkScanner.anchors(content).asSequence()
            .mapNotNull { Urls.resolve(boxUrl, it.href) }
            .filter(::isFile)
            .distinct()
            .map { Asset(name = Urls.segments(it).last(), url = it) }
            .take(MAX_FILES)
            .toList()
        if (assets.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "The download box at ${spec.url} offers no file")
        // Obtainium's "release title as version" takes the file's name; each file is then a release, so the app's filters choose among them.
        val releases = if (spec.flag(SourceOptions.FILE_VERSION)) {
            assets.map { Release(id = it.name, version = it.name, pageUrl = spec.url, assets = listOf(it)) }
        } else {
            listOf(Release(id = version, version = version, pageUrl = spec.url, assets = assets))
        }
        return CheckResult.Listing(SourceListing(releases = releases))
    }

    private fun read(url: String, context: CheckContext): String = context.http.execute(HttpRequest(url)).use {
        if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is nothing at $url")
        if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
        it.text(PAGE_CAP)
    }

    /** An installable file on Farsroid's own servers. */
    private fun isFile(url: String): Boolean {
        val host = Urls.host(url)
        if (host != FILE_DOMAIN && !host.endsWith(".$FILE_DOMAIN")) return false
        val path = Urls.parseHttps(url)?.path?.lowercase() ?: return false
        return INSTALLABLE.any { path.endsWith(it) }
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("""\s${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?.let { XmlScanner.decode(it.groupValues[1].ifEmpty { it.groupValues[2] }) }

    companion object {
        private const val SITE = "https://www.farsroid.com"
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val MAX_FILES = 20

        /** Farsroid's files are on its own servers under this name, such as dl.farsroid.com. */
        const val FILE_DOMAIN = "farsroid.com"

        private val SLUG = Regex("(?=.*[A-Za-z0-9])[A-Za-z0-9._~%-]{1,300}")
        private val NOT_APPS = setOf("api", "wp-admin", "wp-content", "wp-includes", "wp-json", "category", "tag", "page", "author", "feed", "search", "comments")
        private val NUMBER = Regex("[0-9]{1,18}")
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")
        private val DOWNLOAD_BOX = Regex("""<[a-z][a-z0-9]*\b[^>]*\sclass\s*=\s*["'](?:[^"']*\s)?download-links(?:\s[^"']*)?["'][^>]*>""", RegexOption.IGNORE_CASE)
    }
}
