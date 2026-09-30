package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.text.NaturalOrder
import io.github.munzzyy.tern.core.text.PatternException
import io.github.munzzyy.tern.core.text.SafePattern
import io.github.munzzyy.tern.core.version.Version

class HtmlSource : Source {
    override val type: String = SourceTypes.HTML

    override fun match(url: String): SourceSpec? = null

    /** Any page, read for its links to files, when the person says that is what it is. */
    override fun matchForced(url: String, context: CheckContext): SourceSpec? = Urls.normalize(url)?.let { SourceSpec(type, it) }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    /** The file is fetched with the headers the page asks for. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = Download(asset.url, RequestHeaders.of(spec))

    private class Found(val url: String, val version: String?, val order: Int)

    /**
     * One page to pass through: the link whose address, or with [text] its text, matches [filter].
     * With [arch], one that names this device's processor is preferred.
     */
    private class Step(val filter: SafePattern, val text: Boolean, val arch: Boolean)

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val headers = RequestHeaders.of(spec)
        val pseudo = PseudoVersion.of(spec)
        var currentUrl = spec.url
        for (step in parseSteps(spec)) {
            currentUrl = followStep(currentUrl, step, headers, context)
        }

        val key = validatorKey(spec, "page")
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(currentUrl, headers = headers + (validator?.conditionalHeaders() ?: emptyMap())))
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
        val lastSegment = spec.flag(SourceOptions.LAST_SEGMENT)

        val found = watched(SourceOptions.LINK_FILTER) {
            scan(html, base, linkFilter, versionFrom, lastSegment, spec.flag(SourceOptions.ANY_TEXT))
        }.distinctBy { it.url }
        if (found.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable link on ${spec.url}")
        val offered = if (spec.flag(SourceOptions.FIRST_LINK)) listOf(first(found, sort, lastSegment)) else found

        val releases = ArrayList<Release>()
        val versioned = offered.filter { it.version != null }.groupBy { it.version!! }
        val ordered = if (sort == "page") {
            versioned.entries.sortedBy { entry -> entry.value.minOf { it.order } }
        } else {
            versioned.entries.sortedWith(compareByDescending { Version.parse(it.key) })
        }
        for ((version, links) in ordered.take(MAX_RELEASES)) {
            releases.add(Release(id = version, version = version, pageUrl = finalUrl, assets = links.map { Asset(fileName(it.url), it.url) }))
        }

        val unversioned = offered.filter { it.version == null }.take(MAX_UNVERSIONED)
        if (unversioned.isNotEmpty()) releases.add(followedByContent(unversioned, finalUrl, pseudo, headers, context))
        if (unversioned.isEmpty() || pseudo == PseudoVersion.LINK) context.validators.put(key, pageValidator)
        return CheckResult.Listing(SourceListing(releases = releases.take(MAX_RELEASES), name = LinkScanner.title(html)))
    }

    private fun scan(html: String, base: String, linkFilter: SafePattern, versionFrom: String, lastSegment: Boolean, anyText: Boolean): List<Found> {
        val links = if (anyText) LinkScanner.anchors(html) + LinkScanner.addresses(html) else LinkScanner.anchors(html)
        return links.mapIndexedNotNull { index, anchor ->
            val resolved = Urls.resolve(base, anchor.href) ?: return@mapIndexedNotNull null
            if (!linkFilter.matches(resolved.take(2000))) return@mapIndexedNotNull null
            val candidate = when (versionFrom) {
                "text" -> anchor.text
                "page" -> html
                else -> if (lastSegment) lastSegmentOf(resolved) else resolved.substringAfter("://").substringAfter('/')
            }
            Found(resolved, VersionGuess.find(candidate.take(2000)), index)
        }
    }

    /**
     * The link Obtainium's "take first link" takes: the first on the page when links keep the
     * page's order, else the lowest in natural order of the address, or of its last segment.
     */
    private fun first(found: List<Found>, sort: String, lastSegment: Boolean): Found {
        if (sort == "page") return found.first()
        return found.minWith { a, b ->
            NaturalOrder.compare(if (lastSegment) lastSegmentOf(a.url) else a.url, if (lastSegment) lastSegmentOf(b.url) else b.url)
        }
    }

    /** The part after the last slash, query included, as Obtainium's "last link segment" reads it. */
    private fun lastSegmentOf(url: String): String = url.split('/').lastOrNull { it.isNotEmpty() } ?: url

    private fun <T> watched(optionName: String, body: () -> T): T = try {
        SafePattern.watched(optionName, body = body)
    } catch (e: PatternException) {
        throw SourceException(SourceErrorKind.UNSUPPORTED, "Option $optionName: ${e.message}", cause = e)
    }

    /**
     * A link such as latest.apk says nothing about what it holds, and the page around it does not
     * change when the file does. Its identity is therefore taken from the file itself, and the page
     * is fetched in full every time, unless [PseudoVersion.LINK] makes the address its identity.
     */
    private fun followedByContent(links: List<Found>, pageUrl: String, pseudo: PseudoVersion?, headers: Map<String, String>, context: CheckContext): Release {
        val identities = ArrayList<String>()
        val assets = links.map { link ->
            val probe = DirectSource.identify(link.url, pseudo, context, headers)
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

    private fun followStep(url: String, step: Step, headers: Map<String, String>, context: CheckContext): String {
        val response = context.http.execute(HttpRequest(url, headers = headers))
        return response.use {
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
            val text = it.text(PAGE_CAP)
            val pageUrl = Urls.normalize(it.url) ?: url
            val base = LinkScanner.baseHref(text)?.let { b -> Urls.resolve(pageUrl, b) } ?: pageUrl
            val device = context.device
            watched(SourceOptions.STEPS) {
                val matching = LinkScanner.anchors(text).asSequence()
                    .mapNotNull { anchor -> Urls.resolve(base, anchor.href)?.let { address -> address to anchor.text } }
                    .filter { (address, label) -> step.filter.matches((if (step.text) label else address).take(2000)) }
                if (step.arch && device != null) forDevice(matching.toList(), device) else matching.firstOrNull()?.first
            } ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "No link on $url matched a step pattern")
        }
    }

    /**
     * The first link that names this device's most preferred processor; failing that, the first
     * that names none, and only then one that names another processor.
     */
    private fun forDevice(links: List<Pair<String, String>>, device: DeviceProfile): String? {
        val abis = device.abis.map(AssetPicker::canonical)
        return links.minByOrNull { (address, label) ->
            val named = AssetPicker.abisIn("$address $label")
            if (named.isEmpty()) abis.size else named.map { abis.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: (abis.size + 1)
        }?.first
    }

    private fun parseSteps(spec: SourceSpec): List<Step> {
        val raw = spec.option(SourceOptions.STEPS) ?: return emptyList()
        val array = try {
            Json.parseArray(raw)
        } catch (e: JsonException) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} is not a JSON array", cause = e)
        }
        val entries = array.filter { it is JsonString || it is JsonObject }
        if (entries.size > MAX_STEPS) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} allows at most $MAX_STEPS entries")
        }
        return entries.map { entry ->
            if (entry is JsonObject) {
                Step(compileStep(entry.string("filter")), text = entry.bool("text") == true, arch = entry.bool("arch") == true)
            } else {
                Step(compileStep((entry as JsonString).value), text = false, arch = false)
            }
        }
    }

    private fun compileStep(raw: String?): SafePattern =
        compileOrThrow(raw, SourceOptions.STEPS) ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} holds an empty pattern")

    private fun compileOrThrow(raw: String?, optionName: String): SafePattern? = try {
        SafePattern.compileOrNull(raw)
    } catch (e: PatternException) {
        throw SourceException(SourceErrorKind.UNSUPPORTED, "Option $optionName: ${e.message}", cause = e)
    }

    companion object {
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val MAX_STEPS = 5
        private const val MAX_RELEASES = 30
        private const val MAX_UNVERSIONED = 4
        private val DEFAULT_LINK_FILTER = SafePattern.compile("\\.(apk|xapk|apks|apkm)(\\?.*)?$")
    }
}
