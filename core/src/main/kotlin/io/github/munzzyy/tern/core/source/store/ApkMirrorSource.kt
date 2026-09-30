package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.NotesFormat
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
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlElement
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/**
 * APKMirror, whose owners forbid downloads by others, so it is only followed. The app's feed at
 * `<app>/feed/` names each release: the version is read from its title, the date from when it was
 * posted. The package is read from the file name of the app's icon on its page, where the name
 * holds it, once; it is then kept with the source. What is new in a release and the size of its
 * file are read from the page of the release, for the ones that may be offered.
 */
class ApkMirrorSource : Source {
    override val type: String = SourceTypes.APKMIRROR

    override val republishes: Boolean get() = true

    override val trackOnly: Boolean get() = true

    override val domains: Set<String> get() = setOf("apkmirror.com")

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase()
        if (host != "apkmirror.com" && host != "www.apkmirror.com") return null
        val segments = Urls.segments(uri.toString()).map { it.lowercase() }
        if (segments.size < 3 || segments[0] != "apk" || !SLUG.matches(segments[1]) || !SLUG.matches(segments[2])) return null
        return SourceSpec(type, "https://www.apkmirror.com/apk/${segments[1]}/${segments[2]}")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val feedUrl = "${spec.url}/feed/"
        val key = validatorKey(spec, feedUrl)
        val conditional = context.validators.get(key)?.conditionalHeaders().orEmpty()
        val (channel, validator) = context.http.execute(HttpRequest(feedUrl, headers = conditional)).use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "APKMirror has no app at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $feedUrl")
            val root = XmlScanner.parse(it.text(FEED_CAP))
            (root.child("channel") ?: root) to Validator.from(it.headers)
        }
        val items = channel.children("item")
        val releases = items.asSequence().mapNotNull { release(it, feedUrl) }.take(MAX_RELEASES).toList()
        if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "APKMirror lists no release at ${spec.url}")
        context.validators.put(key, validator)

        val detailed = withDetails(releases, context)
        val known = spec.option(SourceOptions.PACKAGE)?.takeIf { FDroidSource.isValidPackage(it) }
        val icon = if (known == null) pageIcon(spec, context) else null
        val learned = if (known == null) icon?.let(::packageFromIcon) else null
        val listing = SourceListing(
            releases = detailed,
            name = channel.childText("title")?.let { APP_NAME.find(it)?.groupValues?.get(1) },
            author = releases.first().title?.let(::author),
            packageName = known ?: learned,
            learnedOptions = learned?.let { mapOf(SourceOptions.PACKAGE to it) }.orEmpty(),
        )
        val feedIcon = items.firstOrNull()?.childText("encoded")?.let { IMAGE.find(it)?.groupValues?.get(1) }
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(icon, feedIcon)))
    }

    private fun release(item: XmlElement, feedUrl: String): Release? {
        val title = item.childText("title")?.replace(SPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val page = item.childText("link")?.let { Urls.resolve(feedUrl, it) }?.takeIf { Urls.host(it) in HOSTS }
        return Release(
            id = page ?: item.childText("guid") ?: title,
            version = versionOf(title),
            title = title,
            publishedAtMs = item.childText("pubDate")?.let(::parseDate),
            prerelease = isPrerelease(title),
            pageUrl = page,
        )
    }

    /**
     * The newest release and the newest that is not a pre-release, one of which is the one offered,
     * with what their pages say: what is new in them, and the size of their file, which is read
     * from the page of its download when the page of the release does not state it.
     */
    private fun withDetails(releases: List<Release>, context: CheckContext): List<Release> {
        val stable = releases.indexOfFirst { !it.countsAsPrerelease }
        return releases.mapIndexed { i, release ->
            val address = release.pageUrl?.takeIf { i == 0 || i == stable } ?: return@mapIndexed release
            val html = fetch(address, context) ?: return@mapIndexed release
            val size = sizeIn(html) ?: downloadPage(html, address)?.let { fetch(it, context) }?.let(::sizeIn)
            release.copy(notes = whatsNew(html), notesFormat = NotesFormat.PLAIN, fileSize = size)
        }
    }

    /** A page of APKMirror, or null when it cannot be read: what it tells is never needed to follow the app. */
    private fun fetch(url: String, context: CheckContext): String? = try {
        context.http.execute(HttpRequest(url)).use { if (it.isSuccess) it.text(PAGE_CAP) else null }
    } catch (_: IOException) {
        null
    }

    /** The address of the app's icon as its page names it, or null when the page cannot be read. */
    private fun pageIcon(spec: SourceSpec, context: CheckContext): String? {
        val html = fetch("${spec.url}/", context) ?: return null
        val metas = META.findAll(html).map { it.value }.toList()
        val chosen = metas.firstOrNull { OG_IMAGE.containsMatchIn(it) } ?: metas.firstOrNull { TWITTER_IMAGE.containsMatchIn(it) } ?: return null
        return CONTENT.find(chosen)?.let { XmlScanner.decode(it.groupValues[1].ifEmpty { it.groupValues[2] }) }?.takeIf { Urls.isHttps(it) }
    }

    private fun parseDate(text: String): Long? = try {
        ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        private const val FEED_CAP = 4 * 1024 * 1024
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val MAX_RELEASES = 30

        private val HOSTS = setOf("www.apkmirror.com", "apkmirror.com")
        private val SLUG = Regex("[a-z0-9][a-z0-9._-]{0,199}")
        private val SPACE = Regex("\\s+")
        private val APP_NAME = Regex("^Download (.+?) APKs? for Android")
        private val VERSION = Regex("""(?:^|\s)v?(\d[\d.\-+_]*)(?=\s|$)""")
        private val STAGE = Regex("^\\s+(alpha|beta|rc|preview|canary|nightly)\\b", RegexOption.IGNORE_CASE)
        private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val OG_IMAGE = Regex("""\sproperty\s*=\s*["']og:image["']""", RegexOption.IGNORE_CASE)
        private val TWITTER_IMAGE = Regex("""\sname\s*=\s*["']twitter:image["']""", RegexOption.IGNORE_CASE)
        private val CONTENT = Regex("""\scontent\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        private val IMAGE = Regex("""<img\b[^>]*\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val SIZE_SUFFIX = Regex("-\\d{1,4}x\\d{1,4}$")

        private const val NOTES_WINDOW = 64 * 1024
        private const val MAX_PARTS = 50
        private const val SIZE_LABEL = "File size"
        private const val SIZE_WINDOW = 400
        private const val MAX_SIZE_LABELS = 20
        private val HEADING = Regex("<h([1-6])\\b[^>]{0,1000}>(.{0,2000}?)</h\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val WHATS_NEW = Regex("^what['’]s new in\\s", RegexOption.IGNORE_CASE)
        private val STOPS = listOf("about ", "download ")
        private val NOISE = setOf("advertisement", "scroll to available downloads", "a more recent upload may be available below!")
        private val HIDDEN = Regex("<!--.{0,4000}?-->|<(script|style)\\b.{0,20000}?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9]*)\\b[^>]{0,2000}?(/?)>")
        private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
        private val LIST_ITEM = Regex("<li\\b[^>]{0,2000}>", RegexOption.IGNORE_CASE)
        private val BLOCK_EDGE = Regex("</?(?:p|div|ul|ol|li|h[1-6]|br|section|article|blockquote|pre|table|tr)\\b[^>]{0,2000}>", RegexOption.IGNORE_CASE)
        private val MARKUP = Regex("<[^>]{0,2000}>")
        private val SPACE_OR_NBSP = Regex("[\\s ]+")
        private val FILE_SIZE = Regex("^File size:\\s*([0-9][0-9.]{0,20})\\s*(B|KB|MB|GB)\\b(?:\\s*\\(([0-9][0-9,]{0,20}) bytes\\))?", RegexOption.IGNORE_CASE)
        private val HREF = Regex("""<a\b[^>]{0,2000}?\shref\s*=\s*["']([^"'<>]{1,2000})["']""", RegexOption.IGNORE_CASE)

        /** The title without what APKMirror adds to it: " by <developer>", "(arm64-v8a)", "(Android 8.0+)", "[0]". */
        internal fun cleanTitle(title: String): String {
            val by = title.lowercase().lastIndexOf(" by ")
            val named = if (by > 0) title.substring(0, by) else title
            return named.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*]"), " ").replace(SPACE, " ").trim()
        }

        /** The version in a release title, as in "Example App 42.5.15-21 (arm64-v8a) by Example Labs"; the cleaned title when none stands out. */
        internal fun versionOf(title: String): String {
            val cleaned = cleanTitle(title)
            return VERSION.find(cleaned)?.groupValues?.get(1) ?: cleaned.ifEmpty { title }
        }

        /** True when a stage follows the version, as in "Example App 2.1.0 beta by Example Labs"; a stage in the name alone says nothing. */
        internal fun isPrerelease(title: String): Boolean {
            val cleaned = cleanTitle(title)
            val version = VERSION.find(cleaned) ?: return false
            return STAGE.containsMatchIn(cleaned.substring(version.range.last + 1))
        }

        private fun author(title: String): String? {
            val by = title.lowercase().lastIndexOf(" by ")
            return if (by > 0) title.substring(by + 4).trim().takeIf { it.isNotEmpty() } else null
        }

        /**
         * What is new in a release, as its page tells under the heading "What's new in ...": the text
         * of each part that follows the heading, a line for each paragraph and item of a list, up to
         * the part that starts something else, such as "About ...". What the site puts between them,
         * such as "Advertisement", is left out. Null when the page has no such heading.
         */
        internal fun whatsNew(html: String): String? {
            val heading = HEADING.findAll(html).firstOrNull { WHATS_NEW.containsMatchIn(readable(it.groupValues[2]).replace('\n', ' ')) } ?: return null
            val parts = ArrayList<String>()
            for (part in partsAfter(html, heading.range.last + 1)) {
                val text = readable(part)
                val said = text.replace('\n', ' ').lowercase()
                if (STOPS.any { said.startsWith(it) } || said.contains(" screenshots") || said.contains(" trailer")) break
                if (text.isNotEmpty() && said !in NOISE && !said.startsWith("verified safe to install")) parts += text
            }
            return parts.joinToString("\n\n").ifEmpty { null }
        }

        /** The elements after [from] that share the parent of the one before it, as markup, up to where that parent closes. */
        private fun partsAfter(html: String, from: Int): List<String> {
            val region = html.substring(from, minOf(html.length, from + NOTES_WINDOW)).replace(HIDDEN, " ")
            val parts = ArrayList<String>()
            var depth = 0
            var start = 0
            for (tag in TAG.findAll(region)) {
                when {
                    tag.groupValues[1] == "/" -> {
                        if (depth == 0) break
                        depth--
                        if (depth == 0) parts += region.substring(start, tag.range.last + 1)
                    }
                    tag.groupValues[3] == "/" || tag.groupValues[2].lowercase() in VOID -> Unit
                    else -> {
                        if (depth == 0) start = tag.range.first
                        depth++
                    }
                }
                if (parts.size >= MAX_PARTS) break
            }
            return parts
        }

        /** What a person reads of [markup]: a line for each paragraph or other block, one starting "- " for each item of a list. */
        private fun readable(markup: String): String {
            val text = markup.replace(LIST_ITEM, "\n- ").replace(BLOCK_EDGE, "\n").replace(MARKUP, "").replace("&nbsp;", " ")
            return XmlScanner.decode(text).lines()
                .map { it.replace(SPACE_OR_NBSP, " ").trim() }
                .filter { it.isNotEmpty() && it != "-" }
                .joinToString("\n")
        }

        /** The size of the file a page states, as in "File size: 270.70 MB", or exactly when it adds the bytes. */
        internal fun sizeIn(html: String): Long? {
            var at = html.indexOf(SIZE_LABEL, ignoreCase = true)
            repeat(MAX_SIZE_LABELS) {
                if (at < 0) return null
                val said = XmlScanner.decode(html.substring(at, minOf(html.length, at + SIZE_WINDOW)).replace(MARKUP, " ").replace("&nbsp;", " "))
                FILE_SIZE.find(said.replace(SPACE_OR_NBSP, " "))?.let { found ->
                    found.groupValues[3].replace(",", "").toLongOrNull()?.let { return it.takeIf { bytes -> bytes > 0 } }
                    val number = found.groupValues[1].toDoubleOrNull() ?: return null
                    val unit = when (found.groupValues[2].uppercase()) {
                        "GB" -> 1L shl 30
                        "MB" -> 1L shl 20
                        "KB" -> 1L shl 10
                        else -> 1L
                    }
                    return (number * unit).roundToLong().takeIf { it > 0 }
                }
                at = html.indexOf(SIZE_LABEL, at + SIZE_LABEL.length, ignoreCase = true)
            }
            return null
        }

        /** The first link of a release page to a page where a file of that same release is downloaded. */
        internal fun downloadPage(html: String, releaseUrl: String): String? {
            val release = withSlash(releaseUrl.substringBefore('?'))
            return HREF.findAll(html).mapNotNull { Urls.resolve(releaseUrl, XmlScanner.decode(it.groupValues[1])) }
                .map { withSlash(it.substringBefore('?')) }
                .firstOrNull { it.startsWith(release) && it.endsWith("-apk-download/") }
        }

        private fun withSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

        /** The package an icon's file name holds, as in `65a71d34ecd19_com.example.app.png`. */
        internal fun packageFromIcon(url: String): String? {
            val file = Urls.segments(url).lastOrNull() ?: return null
            val stem = (if (file.lastIndexOf('.') > 0) file.substring(0, file.lastIndexOf('.')) else file).replace(SIZE_SUFFIX, "")
            val candidates = listOf(stem.substringAfter('_', "")) + stem.split('_').reversed()
            return candidates.firstOrNull { FDroidSource.isValidPackage(it) && !it.lowercase().contains("apkmirror") }
        }
    }
}
