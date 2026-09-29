package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonException
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.Validator
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.Source
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.verify.Fingerprints
import io.github.munzzyy.jackdaw.core.version.Version
import java.nio.charset.StandardCharsets
import java.util.regex.PatternSyntaxException

class HtmlSource : Source {
    override val type: String = SourceTypes.HTML

    override fun match(url: String): SourceSpec? = null

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        var currentUrl = spec.url
        for (pattern in parseSteps(spec)) {
            currentUrl = followStep(currentUrl, pattern, context)
        }

        val key = validatorKey(spec, "page")
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(currentUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        val html: String
        val finalUrl: String
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $currentUrl")
            context.validators.put(key, Validator.from(it.headers))
            html = it.text(PAGE_CAP)
            finalUrl = it.url
        }

        val base = LinkScanner.baseHref(html)?.let { Urls.resolveHttps(finalUrl, it) } ?: finalUrl
        val linkFilter = compileOrThrow(spec.option(SourceOptions.LINK_FILTER), SourceOptions.LINK_FILTER) ?: DEFAULT_LINK_FILTER
        val versionFrom = spec.option(SourceOptions.VERSION_FROM) ?: "link"
        val sort = spec.option(SourceOptions.SORT) ?: "version"

        data class Match(val url: String, val version: String?, val order: Int)

        val matches = LinkScanner.anchors(html).mapIndexedNotNull { index, anchor ->
            val resolved = Urls.resolveHttps(base, anchor.href) ?: return@mapIndexedNotNull null
            if (!matchesCapped(linkFilter, resolved)) return@mapIndexedNotNull null
            val candidate = when (versionFrom) {
                "text" -> anchor.text
                "page" -> html
                else -> resolved
            }
            Match(resolved, VersionGuess.find(candidate.take(2000)), index)
        }
        if (matches.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable link on ${spec.url}")

        val grouped = LinkedHashMap<String, MutableList<Asset>>()
        val firstSeenAt = LinkedHashMap<String, Int>()
        for (match in matches) {
            val id = match.version ?: Fingerprints.sha256(match.url.toByteArray(StandardCharsets.UTF_8))
            grouped.getOrPut(id) { ArrayList() }.add(Asset(name = match.url.substringAfterLast('/').substringBefore('?'), url = match.url))
            firstSeenAt.putIfAbsent(id, match.order)
        }

        val ordered = if (sort == "page") {
            grouped.entries.sortedBy { firstSeenAt.getValue(it.key) }
        } else {
            grouped.entries.sortedWith(compareByDescending { Version.parse(it.key) })
        }
        val releases = ordered.take(30).map { (id, assets) -> Release(id = id, version = id, assets = assets) }
        return CheckResult.Listing(SourceListing(releases = releases))
    }

    private fun followStep(url: String, pattern: Regex, context: CheckContext): String {
        val response = context.http.execute(HttpRequest(url))
        return response.use {
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
            val text = it.text(PAGE_CAP)
            val base = LinkScanner.baseHref(text)?.let { b -> Urls.resolveHttps(it.url, b) } ?: it.url
            LinkScanner.anchors(text).asSequence()
                .mapNotNull { anchor -> Urls.resolveHttps(base, anchor.href) }
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
        private val DEFAULT_LINK_FILTER = Regex("(?i)\\.(apk|xapk|apks|apkm)(\\?.*)?$")
    }
}
