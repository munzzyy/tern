package io.github.munzzyy.tern.core.source.store

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
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * APKCombo, read from the app's page (version, name, developer, date) and from its download page,
 * whose variants tab offers the newest build for each set of processors. The store hands out
 * signed addresses that expire within hours, so a file is listed by its address without the
 * signature, and [resolve] reads the download page again for the signed address it has now.
 */
class ApkComboSource : Source {
    override val type: String = SourceTypes.APKCOMBO

    override val republishes: Boolean get() = true

    override val domains: Set<String> get() = setOf("apkcombo.com") + FILE_HOSTS

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "apkcombo.com" && host != "www.apkcombo.com") return null
        val segments = Urls.segments(uri.toString())
        // An address may start with a two-letter language, as in /de/<name>/<package>.
        val readings = if (segments.size >= 3 && segments[0].length == 2) listOf(segments.drop(1), segments) else listOf(segments)
        val (slug, pkg) = readings.firstOrNull { it.size >= 2 && SLUG.matches(it[0]) && FDroidSource.isValidPackage(it[1]) } ?: return null
        return SourceSpec(type, "https://apkcombo.com/$slug/$pkg", mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOf(spec)
        val html = page(spec.url, context)
        val version = divText(html, "version")?.substringBefore('·')?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "The page at ${spec.url} names no version")
        val facts = informationValues(html)
        val variants = variants(spec, context)
        if (variants.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "APKCombo offers no file for $pkg")
        // Builds for different processors may carry different codes; the lowest keeps any of them from reading as out of date.
        val versionCode = variants.mapNotNull { it.versionCode }.minOrNull()
            ?: facts.firstOrNull()?.let { DIGITS.find(it.substringAfterLast('(', ""))?.value?.toLongOrNull() }
        val release = Release(
            id = versionCode?.toString() ?: version,
            version = version,
            versionCode = versionCode,
            publishedAtMs = facts.getOrNull(1)?.let(::parseDate),
            pageUrl = spec.url,
            assets = variants.map { it.asset },
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = divText(html, "app_name"),
            author = divText(html, "author"),
            packageName = pkg,
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(icon(html))))
    }

    /** The app's icon, the first picture in the page's avatar. */
    private fun icon(html: String): String? {
        val start = AVATAR.find(html)?.range?.last ?: return null
        return IMAGE.find(html.substring(start, minOf(html.length, start + TEXT_WINDOW)))?.groupValues?.get(1)?.let(XmlScanner::decode)
    }

    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = guarded(context) {
        val fresh = variants(spec, it).firstOrNull { variant -> variant.asset.url == asset.url }?.signed
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "APKCombo no longer offers ${asset.name}")
        Download(fresh)
    }

    private fun packageOf(spec: SourceSpec): String {
        val pkg = spec.option(SourceOptions.PACKAGE) ?: Urls.segments(spec.url).lastOrNull()
        if (pkg == null || !FDroidSource.isValidPackage(pkg)) throw SourceException(SourceErrorKind.UNSUPPORTED, "${spec.url} names no app on APKCombo")
        return pkg
    }

    private fun page(url: String, context: CheckContext): String = context.http.execute(HttpRequest(url, headers = HEADERS)).use {
        if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no APKCombo page at $url")
        if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
        it.text(PAGE_CAP)
    }

    /** A file on offer: its lasting [asset], the code of its build, and the address that fetches it now. */
    private class Variant(val asset: Asset, val versionCode: Long?, val signed: String)

    /** The first file of each item in the variants tab, which is the newest build for that set of processors. */
    private fun variants(spec: SourceSpec, context: CheckContext): List<Variant> {
        val downloads = "${spec.url}/download/apk"
        val html = page(downloads, context)
        val tab = VARIANTS_TAB.find(html) ?: return emptyList()
        val found = LinkedHashMap<String, Variant>()
        for (item in listItems(html, tab.range.last + 1).take(MAX_VARIANTS)) {
            val processors = CODE.find(item)?.groupValues?.get(1)?.let(::plain)
                ?.replace(",", "")?.replace(":", "-")?.replace(" ", "-")
            val variant = firstFile(item, downloads, processors) ?: continue
            found.putIfAbsent(variant.asset.url, variant)
        }
        return found.values.toList()
    }

    private fun firstFile(item: String, base: String, processors: String?): Variant? {
        for (link in ANCHOR.findAll(item)) {
            val href = HREF.find(link.groupValues[1])?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: continue
            val target = unwrap(Urls.resolve(base, XmlScanner.decode(href)) ?: continue) ?: continue
            val path = (Urls.parseHttps(target)?.path ?: continue).lowercase()
            if (INSTALLABLE.none { path.endsWith(it) }) continue
            // Only the first file counts: the ones after it are older builds.
            val signed = target.takeIf { Urls.isHttps(it) }?.let(Urls::normalize)?.takeIf { Urls.host(it) in FILE_HOSTS } ?: return null
            val lasting = signed.substringBefore('?')
            val code = VERCODE.find(link.groupValues[2])?.groupValues?.get(1)?.let { DIGITS.find(it)?.value?.toLongOrNull() }
            val extension = lasting.substringAfterLast('.').lowercase()
            val name = when {
                processors.isNullOrEmpty() -> Urls.segments(lasting).last()
                code == null -> "$processors.$extension"
                else -> "$processors-$code.$extension"
            }
            return Variant(Asset(name = name, url = lasting), code, signed)
        }
        return null
    }

    /** Where a link through the store's own redirect, /r2?u=<address>, leads. */
    private fun unwrap(url: String): String? {
        if (Urls.host(url) != "apkcombo.com" || Urls.parseHttps(url)?.path != "/r2") return url
        return Urls.queryParam(url, "u")
    }

    /**
     * The items of the first list at or after [from], each as the markup inside it. A list inside an
     * item stays part of that item.
     */
    private fun listItems(html: String, from: Int): List<String> {
        val items = ArrayList<String>()
        var depth = 0
        var itemStart = -1
        for (tag in LIST_TAG.findAll(html, from)) {
            val closing = tag.groupValues[1] == "/"
            val isList = tag.groupValues[2].equals("ul", ignoreCase = true)
            if (isList) {
                depth += if (closing) -1 else 1
                if (depth > 0) continue
                if (itemStart >= 0) items.add(html.substring(itemStart, tag.range.first))
                break
            }
            if (depth != 1) continue
            if (itemStart >= 0) items.add(html.substring(itemStart, tag.range.first))
            itemStart = if (closing) -1 else tag.range.last + 1
        }
        return items
    }

    /** The text of the first `<div>` with class [name]. */
    private fun divText(html: String, name: String): String? {
        val open = Regex("""<div\b[^>]*\sclass\s*=\s*["'](?:[^"']*\s)?${Regex.escape(name)}(?:\s[^"']*)?["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        val close = html.indexOf("</div", open.range.last + 1, ignoreCase = true)
        if (close < 0 || close - open.range.last > TEXT_WINDOW) return null
        return plain(html.substring(open.range.last + 1, close))
    }

    /** The values of the information table, in their order: the version and its code, then the date. */
    private fun informationValues(html: String): List<String> {
        val start = INFORMATION.find(html)?.range?.last ?: return emptyList()
        val region = html.substring(start, minOf(html.length, start + TABLE_WINDOW))
        return VALUE.findAll(region).mapNotNull { plain(it.groupValues[1]) }.take(2).toList()
    }

    private fun parseDate(text: String): Long? = try {
        LocalDate.parse(text.trim(), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val TEXT_WINDOW = 4000
        private const val TABLE_WINDOW = 16 * 1024
        private const val MAX_VARIANTS = 20

        private val HEADERS = mapOf("Accept" to "*/*")

        /** The bucket APKCombo's signed file addresses point into. */
        val FILE_HOSTS = setOf("apks.39b7cb94d40914bac590886981b0ed6e.r2.cloudflarestorage.com")

        private val SLUG = Regex("[A-Za-z0-9._~-]{1,200}")
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")
        private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
        private val DIGITS = Regex("[0-9]{1,18}")
        private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val VARIANTS_TAB = Regex("""<[a-z][a-z0-9]*\b[^>]*\sid\s*=\s*["']variants-tab["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val INFORMATION = Regex("""<[a-z][a-z0-9]*\b[^>]*\sclass\s*=\s*["'](?:[^"']*\s)?information-table(?:\s[^"']*)?["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val VALUE = Regex("""<div\b[^>]*\sclass\s*=\s*["'](?:[^"']*\s)?value(?:\s[^"']*)?["'][^>]*>(.*?)</div>""", OPTIONS)
        private val LIST_TAG = Regex("""<(/?)(ul|li)\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val CODE = Regex("""<code\b[^>]*>(.*?)</code>""", OPTIONS)
        private val ANCHOR = Regex("""<a\b([^>]*)>(.*?)</a>""", OPTIONS)
        private val HREF = Regex("""(?:^|\s)href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)
        private val VERCODE = Regex("""<span\b[^>]*\sclass\s*=\s*["'](?:[^"']*\s)?vercode(?:\s[^"']*)?["'][^>]*>(.*?)</span>""", OPTIONS)
        private val AVATAR = Regex("""<div\b[^>]*\sclass\s*=\s*["'](?:[^"']*\s)?avatar(?:\s[^"']*)?["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val IMAGE = Regex("""<img\b[^>]*\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val MARKUP = Regex("<[^>]*>")
        private val SPACE = Regex("\\s+")

        /** What a person reads of a piece of markup, or null when that is nothing. */
        private fun plain(fragment: String): String? =
            XmlScanner.decode(fragment.replace(MARKUP, " ").replace("&nbsp;", " ")).replace(SPACE, " ").trim().ifEmpty { null }
    }
}
