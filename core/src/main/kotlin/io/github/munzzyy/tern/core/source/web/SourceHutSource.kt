package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class SourceHutSource : Source {
    override val type: String = SourceTypes.SOURCEHUT

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase() != "git.sr.ht") return null
        // The refs page of a repository names the repository too, as Obtainium reads it.
        val path = uri.path?.trimEnd('/')?.removeSuffix("/refs") ?: return null
        if (!REPO_PATH.matches(path)) return null
        return SourceSpec(type, "https://git.sr.ht$path")
    }

    /** A repository on a SourceHut of any host, such as one a project runs for itself. */
    override fun matchForced(url: String, context: CheckContext): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val path = uri.path?.trimEnd('/') ?: return null
        if (!REPO_PATH.matches(path)) return null
        return SourceSpec(type, "https://${uri.authority}$path")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val feedUrl = "${spec.url}/refs/rss.xml"
        val key = validatorKey(spec, feedUrl)
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(feedUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $feedUrl")
            context.validators.put(key, Validator.from(it.headers))
            val root = try {
                XmlScanner.parse(it.text(2 * 1024 * 1024))
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Could not read the refs feed", cause = e)
            }
            val channel = root.child("channel") ?: root
            val items = channel.children("item").take(MAX_REFS)
            if (items.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No refs at ${spec.url}")
            val releases = items.mapNotNull { item ->
                val tag = item.childText("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val link = item.childText("link") ?: item.childText("guid") ?: return@mapNotNull null
                // Only a page of this repository's refs is fetched, never an address the feed names elsewhere.
                val refUrl = Urls.normalize(link)?.takeIf { it.startsWith("${spec.url}/refs/") } ?: return@mapNotNull null
                Release(id = tag, version = tag, publishedAtMs = item.childText("pubDate")?.let(::parseDate), pageUrl = refUrl, assets = fetchArtifacts(refUrl, context))
            }
            val author = items.firstNotNullOfOrNull { it.childText("author")?.trim()?.takeIf { a -> a.isNotEmpty() } } ?: Urls.segments(spec.url).firstOrNull()
            return CheckResult.Listing(SourceListing(releases = releases, name = Urls.segments(spec.url).lastOrNull(), author = author))
        }
    }

    private fun parseDate(text: String): Long? = try {
        ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun fetchArtifacts(refUrl: String, context: CheckContext): List<Asset> {
        val response = try {
            context.http.execute(HttpRequest(refUrl))
        } catch (_: java.io.IOException) {
            return emptyList()
        }
        return response.use { page ->
            if (!page.isSuccess) return emptyList()
            LinkScanner.anchors(page.text(4 * 1024 * 1024)).mapNotNull { link ->
                val resolved = Urls.resolve(page.url, link.href) ?: return@mapNotNull null
                val path = resolved.substringBefore('?').lowercase()
                if (INSTALLABLE.none { path.endsWith(it) }) return@mapNotNull null
                Asset(name = resolved.substringAfterLast('/'), url = resolved)
            }
        }
    }

    companion object {
        /** Obtainium reads the six newest refs. */
        private const val MAX_REFS = 6
        private val REPO_PATH = Regex("/~[^/]+/[^/]+")
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")
    }
}
