package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonException
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.Urls
import io.github.munzzyy.jackdaw.core.net.Validator
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.Source
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.source.guarded
import io.github.munzzyy.jackdaw.core.version.Version
import java.util.regex.PatternSyntaxException

class HtmlSource : Source {
    override val type: String = SourceTypes.HTML

    override fun match(url: String): SourceSpec? = null

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private class Found(val url: String, val version: String?, val order: Int)

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        var currentUrl = spec.url
        for (pattern in parseSteps(spec)) {
            currentUrl = followStep(currentUrl, pattern, context)
        }

        val key = validatorKey(spec, "page")
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(currentUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        val html: String
        val finalUrl: String
        val pageValidator: Validator
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no page at $currentUrl")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $currentUrl")
            pageValidator = Validator.from(it.headers)
            html = it.text(PAGE_CAP)
            finalUrl = Urls.normalize(it.url) ?: currentUrl
        }

        val base = LinkScanner.baseHref(html)?.let { Urls.resolve(finalUrl, it) } ?: finalUrl
        val linkFilter = compileOrThrow(spec.option(SourceOptions.LINK_FILTER), SourceOptions.LINK_FILTER) ?: DEFAULT_LINK_FILTER
        val versionFrom = spec.option(SourceOptions.VERSION_FROM) ?: "link"
        val sort = spec.option(SourceOptions.SORT) ?: "version"

        val found = LinkScanner.anchors(html).mapIndexedNotNull { index, anchor ->
            val resolved = Urls.resolve(base, anchor.href) ?: return@mapIndexedNotNull null
            if (!matchesCapped(linkFilter, resolved)) return@mapIndexedNotNull null
            val candidate = when (versionFrom) {
                "text" -> anchor.text
                "page" -> html
                else -> resolved.substringAfter("://").substringAfter('/')
            }
            Found(resolved, VersionGuess.find(candidate.take(2000)), index)
        }.distinctBy { it.url }
        if (found.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable link on ${spec.url}")

        val releases = ArrayList<Release>()
        val versioned = found.filter { it.version != null }.groupBy { it.version!! }
        val ordered = if (sort == "page") {
            versioned.entries.sortedBy { entry -> entry.value.minOf { it.order } }
        } else {
            versioned.entries.sortedWith(compareByDescending { Version.parse(it.key) })
        }
        for ((version, links) in ordered.take(MAX_RELEASES)) {
            releases.add(Release(id = version, version = version, pageUrl = finalUrl, assets = links.map { Asset(fileName(it.url), it.url) }))
        }

        val unversioned = found.filter { it.version == null }.take(MAX_UNVERSIONED)
        if (unversioned.isNotEmpty()) {
            releases.add(followedByContent(unversioned, finalUrl, context))
        } else {
            context.validators.put(key, pageValidator)
        }
        return CheckResult.Listing(SourceListing(releases = releases.take(MAX_RELEASES), name = LinkScanner.title(html)))
    }

    /**
     * A link such as latest.apk says nothing about what it holds, and the page around it does not
     * change when the file does. Its identity is therefore taken from the file itself, and the page
     * is fetched in full every time.
     */
    private fun followedByContent(links: List<Found>, pageUrl: String, context: CheckContext): Release {
        val identities = ArrayList<String>()
        val assets = links.map { link ->
            val probe = DirectSource.probe(link.url, context)
            identities.add(probe.identity ?: link.url)
            Asset(fileName(link.url), link.url, size = probe.size)
        }
        val id = "file:" + identities.joinToString("|").take(400)
        return Release(id = id, version = "", pageUrl = pageUrl, assets = assets)
    }

    private fun fileName(url: String): String {
        val last = Urls.segments(url).lastOrNull() ?: return "download.apk"
        return last.take(200)
    }

    private fun followStep(url: String, pattern: Regex, context: CheckContext): String {
        val response = context.http.execute(HttpRequest(url))
        return response.use {
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
            val text = it.text(PAGE_CAP)
            val pageUrl = Urls.normalize(it.url) ?: url
            val base = LinkScanner.baseHref(text)?.let { b -> Urls.resolve(pageUrl, b) } ?: pageUrl
            LinkScanner.anchors(text).asSequence()
                .mapNotNull { anchor -> Urls.resolve(base, anchor.href) }
                .firstOrNull { candidate -> matchesCapped(pattern, candidate) }
                ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "No link on $url matched a step pattern")
        }
    }

    private fun parseSteps(spec: SourceSpec): List<Regex> {
        val raw = spec.option(SourceOptions.STEPS) ?: return emptyList()
        val array = try {
            Json.parseArray(raw)
        } catch (e: JsonException) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} is not a JSON array", cause = e)
        }
        val patterns = array.strings()
        if (patterns.size > MAX_STEPS) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} allows at most $MAX_STEPS entries")
        }
        return patterns.map { compileOrThrow(it, SourceOptions.STEPS) ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Empty pattern") }
    }

    private fun compileOrThrow(raw: String?, optionName: String): Regex? {
        if (raw == null) return null
        if (raw.length > MAX_PATTERN) throw SourceException(SourceErrorKind.UNSUPPORTED, "Option $optionName is longer than $MAX_PATTERN characters")
        return try {
            Regex(raw, RegexOption.IGNORE_CASE)
        } catch (e: PatternSyntaxException) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "Option $optionName is not a valid pattern", cause = e)
        }
    }

    private fun matchesCapped(pattern: Regex, text: String): Boolean = pattern.containsMatchIn(text.take(2000))

    companion object {
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val MAX_STEPS = 5
        private const val MAX_RELEASES = 30
        private const val MAX_UNVERSIONED = 4
        private const val MAX_PATTERN = 500
        private val DEFAULT_LINK_FILTER = Regex("(?i)\\.(apk|xapk|apks|apkm)(\\?.*)?$")
    }
}
