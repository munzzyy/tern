package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
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
import io.github.munzzyy.tern.core.source.forge.Iso8601
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.source.web.ServedFile
import io.github.munzzyy.tern.core.source.web.VersionGuess
import io.github.munzzyy.tern.core.version.Version
import io.github.munzzyy.tern.core.xml.XmlScanner

/**
 * An app page on apk4free.net. The page names the app and its version and links to a download page,
 * whose buttons lead to the files on the site's own file server. A download page often holds several
 * versions at once, so the files are grouped into releases by the version in their names, and a file
 * whose name has none belongs to the version the page names.
 */
class Apk4FreeSource : Source {
    override val type: String = SourceTypes.APK4FREE

    override val republishes: Boolean get() = true

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != HOST) return null
        val slug = uri.rawPath.orEmpty().split('/').firstOrNull { it.isNotEmpty() }?.takeIf { SLUG.matches(it) && it !in RESERVED } ?: return null
        return SourceSpec(type, "https://$HOST/$slug/")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private class Page(val url: String, val html: String)

    private class Anchor(val href: String, val text: String, val button: Boolean)

    private class FileLink(val url: String, val name: String, val text: String)

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val page = page(spec.url, context)
        val headingTag = HEADING.find(page.html)
        val headingText = headingTag?.let { inside(page.html, it.range.last + 1, "</h1") }
        val heading = headingText?.let(::plain)
        // The page's own version stands right after its heading; related apps further down name theirs.
        val versionTag = if (headingTag == null || headingText == null) {
            VERSION_DIV.find(page.html)
        } else {
            val headingEnd = headingTag.range.last + 1 + headingText.length
            VERSION_DIV.find(page.html, headingEnd)?.takeIf { it.range.first - headingEnd <= NEAR_HEADING }
        }
        val pageVersion = versionTag?.let { inside(page.html, it.range.last + 1, "</div") }?.let(::plain)?.takeIf { it.isNotEmpty() }
            ?: heading?.let { TITLE_VERSION.find(it)?.groupValues?.get(1) }
        val anchors = anchors(page.html)
        val downloadPage = (anchors.filter { it.button } + anchors).asSequence()
            .mapNotNull { Urls.resolve(page.url, it.href) }
            .firstOrNull { "/download/" in it && Urls.host(it).removePrefix("www.") == HOST }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "${spec.url} links to no download page")

        val downloads = page(downloadPage, context)
        val links = anchors(downloads.html)
        val chosen = links.filter { it.button }.ifEmpty { links.filter { Asset.kindOf(it.href) in INSTALLABLE } }
        val files = chosen.mapNotNull { file(downloads.url, it) }.distinctBy { it.url }.take(MAX_FILES)
        if (files.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "${spec.url} offers no file on the site's own servers")

        val grouped = LinkedHashMap<String, MutableList<FileLink>>()
        for (file in files) {
            val version = VersionGuess.find(file.name) ?: VersionGuess.find(file.text) ?: pageVersion ?: continue
            grouped.getOrPut(version) { ArrayList() }.add(file)
        }
        if (grouped.isEmpty()) throw SourceException(SourceErrorKind.PARSE, "${spec.url} names no version")
        val modified = MODIFIED.find(page.html)?.groupValues?.get(1)?.let(Iso8601::parseMs)
        val releases = grouped.entries
            .sortedWith(compareByDescending { Version.parse(it.key) })
            .take(MAX_RELEASES)
            .map { (version, group) ->
                Release(
                    id = version,
                    version = version,
                    title = group.first().text.takeIf { it.isNotEmpty() },
                    // The page's date is when its post last changed, which is when its own version came.
                    publishedAtMs = modified?.takeIf { version == pageVersion },
                    prerelease = group.all { STAGE.containsMatchIn(it.text) || STAGE.containsMatchIn(it.name) },
                    pageUrl = spec.url,
                    assets = group.map { Asset(name = it.name, url = it.url) },
                )
            }
        val name = heading?.let(::cleanTitle)?.takeIf { it.isNotEmpty() } ?: Urls.segments(spec.url).lastOrNull()
        return CheckResult.Listing(SourceListing(releases = releases, name = name))
    }

    private fun page(url: String, context: CheckContext): Page = context.http.execute(HttpRequest(url)).use {
        if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no page at $url")
        if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
        Page(Urls.normalize(it.url) ?: url, it.text(PAGE_CAP))
    }

    private fun file(base: String, anchor: Anchor): FileLink? {
        val url = Urls.resolve(base, anchor.href)?.takeIf { isFileHost(Urls.host(it)) } ?: return null
        val name = Urls.segments(url).lastOrNull()?.let(ServedFile::clean) ?: return null
        return FileLink(url, name, anchor.text)
    }

    private fun anchors(html: String): List<Anchor> = ANCHOR.findAll(html).take(MAX_ANCHORS).mapNotNull { m ->
        val attributes = m.groupValues[1]
        val href = HREF.find(attributes)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(XmlScanner::decode)?.trim()
            ?: return@mapNotNull null
        val classes = CLASS.find(attributes)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }.orEmpty()
        Anchor(href, plain(m.groupValues[2]), classes.split(' ').contains(BUTTON_CLASS))
    }.toList()

    private fun inside(html: String, from: Int, closing: String): String? {
        val close = html.indexOf(closing, from, ignoreCase = true)
        if (close < 0 || close - from > WINDOW) return null
        return html.substring(from, close)
    }

    private fun plain(html: String): String =
        XmlScanner.decode(TAG.replace(html, " ").replace("&nbsp;", " ")).replace(SPACES, " ").trim()

    /** The app's name without what the site adds to it: the version, "MOD APK", "(Unlocked)" and the like. */
    private fun cleanTitle(title: String): String = title.take(MAX_TITLE)
        .replace(BRACKETS, "")
        .replace(TYPE_WORDS, "")
        .replace(MOD_NOTES, "")
        .replace(VERSION_TAIL, "")
        .replace(JOINERS, " ")
        .replace(SPACES, " ")
        .trim()

    companion object {
        /** The site's own file server. */
        val FILE_HOSTS: Set<String> = setOf("apps.apk4free.net")

        private const val HOST = "apk4free.net"
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val WINDOW = 4096
        private const val NEAR_HEADING = 200
        private const val MAX_ANCHORS = 5000
        private const val MAX_FILES = 40
        private const val MAX_RELEASES = 30
        private const val MAX_TITLE = 300
        private const val BUTTON_CLASS = "downloadAPK"
        private val INSTALLABLE = setOf(AssetKind.APK, AssetKind.BUNDLE)
        private val SLUG = Regex("[A-Za-z0-9_-]{1,200}")
        private val RESERVED = setOf(
            "apps", "games", "blog", "about", "contact-us", "dmca-copyright", "privacy-policy", "editors-choice",
            "category", "tag", "page", "author", "feed", "search", "wp-content", "wp-json", "wp-admin",
        )

        fun isFileHost(host: String): Boolean = host == HOST || host == "www.$HOST" || host in FILE_HOSTS

        private val HEADING = Regex("""<h1\s[^>]{0,1000}?class="(?:[^"]{0,500}\s)?main-box-title(?:\s[^"]{0,500})?"[^>]{0,1000}>""", RegexOption.IGNORE_CASE)
        private val VERSION_DIV = Regex("""<div\s[^>]{0,1000}?class="(?:[^"]{0,500}\s)?version(?:\s[^"]{0,500})?"[^>]{0,1000}>""", RegexOption.IGNORE_CASE)
        private val ANCHOR = Regex("""<a\s([^>]{0,2000})>(.{0,4000}?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val HREF = Regex("""(?:^|\s)href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        private val CLASS = Regex("""(?:^|\s)class\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        private val MODIFIED = Regex(""""dateModified"\s*:\s*"([^"]{1,40})"""")
        private val TITLE_VERSION = Regex("""v?(\d+(?:\.\d+)+)""")
        private val STAGE = Regex("""(?<![a-z])(nightly|beta|alpha|canary|preview)(?![a-z])""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")

        // What Obtainium takes out of a title to leave the app's name. Android refuses a bare closing brace that Java takes.
        private val BRACKETS = Regex("""\[.*?\]|\{.*?\}""")
        private val TYPE_WORDS = Regex("""\b(APK|MOD|XAPK|HACK)\b""", RegexOption.IGNORE_CASE)
        private val MOD_NOTES = Regex(
            """\((?:[^)]*?(?:Unlocked|Mod|Premium|Money|Menu|Full|Patched|Subscribed|AdFree|BG Play|Paid|Unlimited|God Mode)[^)]*?)\)""",
            RegexOption.IGNORE_CASE,
        )
        private val VERSION_TAIL = Regex("""\s+v?\d+(\.\d+)+.*$""", RegexOption.IGNORE_CASE)
        private val JOINERS = Regex("""\s+[+\-]\s+""")
    }
}
