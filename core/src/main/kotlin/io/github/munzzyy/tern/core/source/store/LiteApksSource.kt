package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.source.web.ServedFile
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.util.Base64

/**
 * An app page on liteapks.com, read through the site's WordPress API: the post of the page's slug
 * gives its id, and the site's own record of that post gives the name, the publisher, the version
 * and the download links. Every request names the page it comes from, as the site wants. The file
 * server wants a token of the time with each download, which [resolve] adds.
 */
class LiteApksSource : Source {
    override val type: String = SourceTypes.LITEAPKS

    override val republishes: Boolean get() = true

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != HOST) return null
        val slug = uri.rawPath.orEmpty().split('/').firstOrNull { it.isNotEmpty() }?.removeSuffix(".html") ?: return null
        if (!SLUG.matches(slug) || slug in RESERVED) return null
        return SourceSpec(type, "https://$HOST/$slug.html")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val slug = Urls.segments(spec.url).singleOrNull()?.removeSuffix(".html")?.takeIf { SLUG.matches(it) }
            ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "${spec.url} is not an app page of LiteAPKs")
        val posts = Json.parse(get("$ORIGIN/wp-json/wp/v2/posts?slug=$slug", context)) as? JsonArray
        val id = posts?.objects()?.firstOrNull()?.long("id")?.takeIf { it > 0 }
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "LiteAPKs has no post $slug")
        val data = Json.parseObject(get("$ORIGIN/wp-json/v2/posts/$id", context)).obj("data")
            ?: throw SourceException(SourceErrorKind.PARSE, "LiteAPKs sent no record of $slug")
        val latest = data.array("versions")?.objects()?.firstOrNull()
            ?: throw SourceException(SourceErrorKind.PARSE, "LiteAPKs names no version of $slug")
        val version = latest.string("version")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SourceException(SourceErrorKind.PARSE, "LiteAPKs names no version of $slug")
        val assets = latest.array("version_downloads")?.objects().orEmpty()
            .mapNotNull { it.string("version_download_link")?.let(::asset) }
            .distinctBy { it.url }
            .take(MAX_ASSETS)
        if (assets.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "LiteAPKs offers no download of $slug on its own servers")
        val release = Release(id = version, version = version, pageUrl = spec.url, assets = assets)
        val listing = SourceListing(
            releases = listOf(release),
            name = data.string("title")?.let(XmlScanner::decode),
            author = data.string("publisher")?.let(XmlScanner::decode),
        )
        return CheckResult.Listing(listing)
    }

    /** The link with a fresh token, sent from the app's page. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download {
        if (!Urls.isHttps(asset.url) || !isFileHost(Urls.host(asset.url))) {
            throw SourceException(SourceErrorKind.PARSE, "${asset.url} is not a file on LiteAPKs' servers")
        }
        val separator = if ('?' in asset.url) '&' else '?'
        return Download("${asset.url}${separator}token=${token(context.nowMs())}", mapOf("Referer" to spec.url))
    }

    private fun get(url: String, context: CheckContext): String = context.http.execute(HttpRequest(url, headers = mapOf("Referer" to url))).use {
        if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Nothing at $url")
        if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
        it.text(JSON_CAP)
    }

    private fun asset(link: String): Asset? {
        val address = Urls.resolve("$ORIGIN/", link)?.takeIf { isFileHost(Urls.host(it)) } ?: return null
        val name = Urls.segments(address).lastOrNull()?.let(ServedFile::clean) ?: return null
        return Asset(name = name, url = address)
    }

    companion object {
        /** The site and its own subdomains, which the download links are expected to point to. */
        const val FILE_DOMAIN = "liteapks.com"

        private const val HOST = "liteapks.com"
        private const val ORIGIN = "https://liteapks.com"
        private const val JSON_CAP = 2 * 1024 * 1024
        private const val MAX_ASSETS = 20

        /** How far ahead of now the file server wants the token's time to be. */
        private const val TOKEN_AHEAD_SECONDS = 3 * 60 * 60L
        private val SLUG = Regex("[A-Za-z0-9_-]{1,200}")
        private val RESERVED = setOf("category", "tag", "page", "author", "search", "download", "feed", "wp-json", "wp-content", "wp-admin")

        fun isFileHost(host: String): Boolean = host == FILE_DOMAIN || host.endsWith(".$FILE_DOMAIN")

        /** The time three hours on, in seconds, in base64 twice over, as the file server takes it. */
        private fun token(nowMs: Long): String {
            val seconds = (nowMs / 1000 + TOKEN_AHEAD_SECONDS).toString()
            val once = Base64.getEncoder().encodeToString(seconds.toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(once.toByteArray(Charsets.US_ASCII)).replace("=", "%3D")
        }
    }
}
