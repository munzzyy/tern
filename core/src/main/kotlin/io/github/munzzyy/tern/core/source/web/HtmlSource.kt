package io.github.munzzyy.tern.core.source.web

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
import io.github.munzzyy.tern.core.text.MatchTemplate
import io.github.munzzyy.tern.core.text.NaturalOrder
import io.github.munzzyy.tern.core.text.PatternException
import io.github.munzzyy.tern.core.text.SafePattern
import io.github.munzzyy.tern.core.version.Version

/**
 * Any web page with links to an app's files, read the way Obtainium's HTML source reads one. On
 * each page the links that match are put in natural order of their address, or kept in the
 * order of the page, and the last one is taken: on a page before the last it is followed, and on
 * the last page its release is marked as the latest. The other links stay in the listing, so a
 * file the app's filters refuse is not the end of it.
 */
class HtmlSource : Source {
    override val type: String = SourceTypes.HTML

    override fun match(url: String): SourceSpec? = null

    /** Any page, read for its links to files, when the person says that is what it is. */
    override fun matchForced(url: String, context: CheckContext): SourceSpec? = Urls.normalize(url)?.let { SourceSpec(type, it) }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    /** The file is fetched with the headers the page asks for. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = Download(asset.url, RequestHeaders.of(spec))

    /** A link found on a page: where it points, and what it says, or the end of its address when it says nothing. */
    private class Link(val url: String, val text: String)

    /** Which links of a page count and in what order they are put. A null [filter] keeps links to installable files. */
    private class Choice(
        val filter: SafePattern?,
        val byText: Boolean,
        val anyText: Boolean,
        val pageOrder: Boolean,
        val firstLink: Boolean,
        val lastSegment: Boolean,
    )

    private class Step(val choice: Choice, val arch: Boolean)

    /** How the version of a link is read: through the app's pattern when it has one, and else by a guess. */
    private class Reading(val from: String, val lastSegment: Boolean, val pattern: SafePattern?, val template: MatchTemplate?)

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
        val choice = Choice(
            filter = compileOrThrow(spec.option(SourceOptions.LINK_FILTER), "Option ${SourceOptions.LINK_FILTER}"),
            byText = spec.flag(SourceOptions.LINK_TEXT),
            anyText = spec.flag(SourceOptions.ANY_TEXT),
            pageOrder = spec.option(SourceOptions.SORT) == "page",
            firstLink = spec.flag(SourceOptions.FIRST_LINK),
            lastSegment = spec.flag(SourceOptions.LAST_SEGMENT),
        )
        val ordered = watched("Option ${SourceOptions.LINK_FILTER}") { chosen(links(html, base, choice.anyText), choice) }
        if (ordered.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable link on ${spec.url}")
        // The link Obtainium takes is the last in order. Here it comes first, and the others follow it.
        val preferred = ordered.asReversed().distinctBy { it.url }.take(MAX_LINKS)

        val highest = spec.flag(SourceOptions.HIGHEST_VERSION)
        val reading = reading(spec, context, highest)
        val versions = watched("The version pattern") { versionsOf(preferred, html, reading) }
        val versioned = LinkedHashMap<String, MutableList<Link>>()
        val unversioned = ArrayList<Link>()
        for (link in preferred) {
            val version = versions[link.url]
            if (version == null) unversioned.add(link) else versioned.getOrPut(version) { ArrayList() }.add(link)
        }

        // Tern 0.1.0 listed the highest versions, or with the page's order its first ones, whatever else the page held.
        val listed = if (!highest) {
            versioned.entries
        } else if (choice.pageOrder) {
            versioned.entries.sortedBy { entry -> entry.value.minOf { link -> ordered.indexOfFirst { it.url == link.url } } }
        } else {
            versioned.entries.sortedWith(compareByDescending { Version.parse(it.key) })
        }
        val releases = listed.take(MAX_RELEASES).map { (version, links) ->
            Release(id = version, version = version, pageUrl = finalUrl, assets = links.map { Asset(fileName(it.url), it.url) })
        }.toMutableList()
        val taken = unversioned.take(MAX_UNVERSIONED)
        if (taken.isNotEmpty()) {
            val release = followedByContent(taken, finalUrl, pseudo, headers, context)
            if (versions[preferred.first().url] == null && !highest) releases.add(0, release) else releases.add(release)
        }
        if (unversioned.isEmpty() || pseudo == PseudoVersion.LINK) context.validators.put(key, pageValidator)
        if (!highest) releases[0] = releases[0].copy(latest = true)
        return CheckResult.Listing(SourceListing(releases = releases.take(MAX_RELEASES), name = LinkScanner.title(html)))
    }

    /**
     * Every link of a page, in the order of the page: its `<a>` tags, and where [anyText] asks for
     * it or the page has no link tag at all, the addresses in its JSON, its text and its attributes.
     */
    private fun links(html: String, base: String, anyText: Boolean): List<Link> {
        val anchors = LinkScanner.anchors(html).filter { it.href.isNotBlank() }
        val found = if (anchors.isEmpty() || anyText) anchors + LinkScanner.addresses(html) else anchors
        return found.mapNotNull { anchor ->
            val url = Urls.resolve(base, anchor.href) ?: return@mapNotNull null
            Link(url, anchor.text.ifEmpty { lastSegmentOf(url) })
        }
    }

    /**
     * The links [choice] keeps, in its order: natural order of the address or of its last segment,
     * or the order of the page, turned around with [Choice.firstLink]. The last is the one taken.
     */
    private fun chosen(links: List<Link>, choice: Choice): List<Link> {
        val filter = choice.filter ?: DEFAULT_LINK_FILTER
        val kept = links.filter { link -> filter.matches((if (choice.byText) link.text else Urls.decode(link.url)).take(MAX_MATCHED)) }
        val ordered = if (choice.pageOrder) kept else kept.sortedWith { a, b -> NaturalOrder.compare(sortKey(a, choice), sortKey(b, choice)) }
        return if (choice.firstLink) ordered.asReversed() else ordered
    }

    private fun sortKey(link: Link, choice: Choice): String = if (choice.lastSegment) lastSegmentOf(link.url) else link.url

    /** The part after the last slash, query included, as Obtainium's "last link segment" reads it. */
    private fun lastSegmentOf(url: String): String = url.split('/').lastOrNull { it.isNotEmpty() } ?: url

    /** With [highest] the pattern is left to the release filters, which run it over the guessed version as 0.1.0 did. */
    private fun reading(spec: SourceSpec, context: CheckContext, highest: Boolean): Reading {
        val from = spec.option(SourceOptions.VERSION_FROM) ?: "link"
        val raw = context.app?.releases?.versionExtract?.takeIf { it.isNotBlank() && !highest }
        val pattern = try {
            raw?.let { if (from == "page") SafePattern.compileForPages(it) else SafePattern.compile(it) }
        } catch (e: PatternException) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "The version pattern: ${e.message}", cause = e)
        }
        return Reading(from, spec.flag(SourceOptions.LAST_SEGMENT), pattern, MatchTemplate.parse(context.app?.releases?.matchGroup))
    }

    /**
     * The version of each link, null where none can be read. The app's pattern reads the link's
     * decoded address, what it says, or the whole page with its line breaks written as \n, as
     * Obtainium's does. Where it has no pattern, or its pattern finds nothing, a dotted number in
     * the address, its last segment, what it says or the page is taken.
     */
    private fun versionsOf(links: List<Link>, html: String, reading: Reading): Map<String, String?> {
        if (reading.from == "page") {
            val page = reading.pattern?.let { extract(it, reading.template, html.replace("\r\n", "\n").replace("\n", "\\n")) }
            val version = page ?: VersionGuess.find(html.take(MAX_MATCHED))
            return links.associate { it.url to version }
        }
        return links.associate { link ->
            val text = if (reading.from == "text") link.text else Urls.decode(link.url)
            val guessed = when {
                reading.from == "text" -> link.text
                reading.lastSegment -> lastSegmentOf(link.url)
                else -> link.url.substringAfter("://").substringAfter('/')
            }
            link.url to (reading.pattern?.let { extract(it, reading.template, text.take(MAX_MATCHED)) } ?: VersionGuess.find(guessed.take(MAX_MATCHED)))
        }
    }

    private fun extract(pattern: SafePattern, template: MatchTemplate?, text: String): String? =
        if (template == null) pattern.extract(text) else pattern.extract(text, template)

    private fun <T> watched(what: String, body: () -> T): T = try {
        SafePattern.watched(what, body = body)
    } catch (e: PatternException) {
        throw SourceException(SourceErrorKind.UNSUPPORTED, "$what: ${e.message}", cause = e)
    }

    /**
     * A link such as latest.apk says nothing about what it holds, and the page around it does not
     * change when the file does. Its identity is therefore taken from the file itself, and the page
     * is fetched in full every time, unless [PseudoVersion.LINK] makes the address its identity.
     */
    private fun followedByContent(links: List<Link>, pageUrl: String, pseudo: PseudoVersion?, headers: Map<String, String>, context: CheckContext): Release {
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

    /** The address of the link the step takes on the page at [url]. */
    private fun followStep(url: String, step: Step, headers: Map<String, String>, context: CheckContext): String {
        val response = context.http.execute(HttpRequest(url, headers = headers))
        return response.use {
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
            val text = it.text(PAGE_CAP)
            val pageUrl = Urls.normalize(it.url) ?: url
            val base = LinkScanner.baseHref(text)?.let { b -> Urls.resolve(pageUrl, b) } ?: pageUrl
            val device = context.device
            watched("Option ${SourceOptions.STEPS}") {
                val ordered = chosen(links(text, base, step.choice.anyText), step.choice)
                val kept = if (step.arch && device != null) forDevice(ordered, device) else ordered
                kept.lastOrNull()?.url
            } ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "No link on $url matched a step pattern")
        }
    }

    /**
     * The links that name this device's most preferred processor; failing that, those that name
     * none, and only then all of them. The order stays as it was.
     */
    private fun forDevice(links: List<Link>, device: DeviceProfile): List<Link> {
        val named = links.map { AssetPicker.abisIn("${it.url} ${it.text}") }
        for (abi in device.abis.map(AssetPicker::canonical)) {
            val fit = links.filterIndexed { i, _ -> abi in named[i] }
            if (fit.isNotEmpty()) return fit
        }
        return links.filterIndexed { i, _ -> named[i].isEmpty() }.ifEmpty { links }
    }

    private fun parseSteps(spec: SourceSpec): List<Step> {
        val steps = HtmlStep.parse(spec.option(SourceOptions.STEPS))
            ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} is not a JSON array")
        if (steps.size > HtmlStep.MAX) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} allows at most ${HtmlStep.MAX} entries")
        }
        return steps.map { step ->
            val filter = compileOrThrow(step.filter, "Option ${SourceOptions.STEPS}")
                ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.STEPS} holds an empty pattern")
            Step(Choice(filter, step.byText, step.anyText, step.pageOrder, step.firstLink, step.lastSegment), step.arch)
        }
    }

    private fun compileOrThrow(raw: String?, what: String): SafePattern? = try {
        SafePattern.compileOrNull(raw)
    } catch (e: PatternException) {
        throw SourceException(SourceErrorKind.UNSUPPORTED, "$what: ${e.message}", cause = e)
    }

    companion object {
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val MAX_RELEASES = 30
        private const val MAX_UNVERSIONED = 4

        /** The most links of the last page that get a version read, the one taken first. */
        private const val MAX_LINKS = 400

        /** How much of an address or a text a filter or a guess reads. */
        private const val MAX_MATCHED = 2000
        private val DEFAULT_LINK_FILTER = SafePattern.compile("\\.(apk|xapk|apks|apkm)(\\?.*)?$")
    }
}
