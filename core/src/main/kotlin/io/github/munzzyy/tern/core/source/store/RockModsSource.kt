package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonValue
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
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlScanner

/**
 * An app page on rockmods.net, followed without its files: the site is built so that nothing but a
 * browser gets one. The page's JSON-LD SoftwareApplication gives the name, the version, the developer
 * and the description; the page's heading stands in for a missing name.
 */
class RockModsSource : Source {
    override val type: String = SourceTypes.ROCKMODS

    override val republishes: Boolean get() = true

    override val trackOnly: Boolean get() = true

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != HOST) return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || !segments[0].equals("apps", ignoreCase = true)) return null
        val slug = segments[1].takeIf { SLUG.matches(it) } ?: return null
        return SourceSpec(type, "https://www.$HOST/apps/$slug")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val html = context.http.execute(HttpRequest(spec.url)).use {
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no app page at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for ${spec.url}")
            it.text(PAGE_CAP)
        }
        val app = softwareApplication(html) ?: throw SourceException(SourceErrorKind.PARSE, "${spec.url} describes no app")
        val version = text(app["softwareVersion"]) ?: throw SourceException(SourceErrorKind.PARSE, "${spec.url} names no version")
        val name = text(app["name"])
            ?: HEADING.find(html)?.groupValues?.get(1)?.let(::plain)?.takeIf { it.isNotEmpty() }
            ?: Urls.segments(spec.url).lastOrNull()
        val author = when (val by = app["author"]) {
            is JsonObject -> text(by["name"])
            is JsonArray -> by.objects().firstNotNullOfOrNull { text(it["name"]) }
            else -> text(by)
        }
        val release = Release(id = version, version = version, pageUrl = spec.url)
        val listing = SourceListing(releases = listOf(release), name = name, author = author, description = text(app["description"]))
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(text(app["image"]))))
    }

    /** The first JSON-LD block that is, or holds, a SoftwareApplication. */
    private fun softwareApplication(html: String): JsonObject? {
        var pos = 0
        repeat(MAX_BLOCKS) {
            val open = LD_JSON.find(html, pos) ?: return null
            val close = html.indexOf("</script", open.range.last + 1, ignoreCase = true)
            if (close < 0) return null
            pos = close
            val value = try {
                Json.parse(html.substring(open.range.last + 1, close).trim())
            } catch (_: JsonException) {
                return@repeat
            }
            find(value, 0)?.let { return it }
        }
        return null
    }

    private fun find(value: JsonValue, depth: Int): JsonObject? = when {
        depth > MAX_DEPTH -> null
        value is JsonObject && isApplication(value) -> value
        value is JsonObject -> value.array("@graph")?.firstNotNullOfOrNull { find(it, depth + 1) }
        value is JsonArray -> value.firstNotNullOfOrNull { find(it, depth + 1) }
        else -> null
    }

    private fun isApplication(value: JsonObject): Boolean = when (val kind = value["@type"]) {
        is JsonString -> kind.value in APPLICATION_TYPES
        is JsonArray -> kind.strings().any { it in APPLICATION_TYPES }
        else -> false
    }

    private fun text(value: JsonValue?): String? = when (value) {
        is JsonString -> value.value
        is JsonNumber -> value.raw
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun plain(html: String): String =
        XmlScanner.decode(TAG.replace(html, " ").replace("&nbsp;", " ")).replace(SPACES, " ").trim()

    companion object {
        private const val HOST = "rockmods.net"
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val MAX_BLOCKS = 20
        private const val MAX_DEPTH = 4
        private val APPLICATION_TYPES = setOf("SoftwareApplication", "MobileApplication")
        private val SLUG = Regex("[A-Za-z0-9_-]{1,200}")
        private val LD_JSON = Regex("""<script\b[^>]{0,200}?\btype\s*=\s*["']application/ld\+json["'][^>]{0,200}>""", RegexOption.IGNORE_CASE)
        private val HEADING = Regex("""<h1(?:\s[^>]{0,1000})?>(.{0,2000}?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")
    }
}
