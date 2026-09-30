package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
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
import io.github.munzzyy.tern.core.source.web.LinkScanner
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * A game or app on itch.io, <author>.itch.io/<game>. The page gives the title, the author and the
 * uploads with the platforms each is for; only the uploads marked for Android are listed. When the
 * page asks for a price first, the free download page itch.io hands out in its place is read. The
 * version is the highest "v1.2.3" or "Version 1.2.3" in the page's main part, else the day of the
 * newest date on the page. A check reads only the page: each file is listed by the page and its
 * upload id, under the name the page gives it, and [resolve] asks itch.io for the file, at an
 * address that lasts a minute, just before the download. None of this needs a cookie: the token in
 * the page is enough.
 */
class ItchIoSource : Source {
    override val type: String = SourceTypes.ITCHIO

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.endsWith(".$HOST")) return null
        val author = host.removeSuffix(".$HOST")
        if (!AUTHOR.matches(author) || author in RESERVED) return null
        val game = uri.rawPath.orEmpty().split('/').firstOrNull { it.isNotEmpty() }?.takeIf { GAME.matches(it) } ?: return null
        return SourceSpec(type, "https://$author.$HOST/$game")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val base = spec.url
        val page = page(base, context)
        val csrf = csrfToken(page)
        var version = pageVersion(page)
        var updated = newestDate(page)
        var uploads = uploads(page)
        if (uploads.isEmpty() && csrf != null) {
            downloadPage(base, csrf, context)?.let { other ->
                uploads = uploads(other)
                version = version ?: pageVersion(other)
                updated = updated ?: newestDate(other)
            }
        }
        val android = uploads.filter { it.android }.take(MAX_UPLOADS)
        if (android.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "$base offers no file for Android")
        // Asking for a file is what itch.io's download button does, so a check only reads the page.
        // An upload marked for Android is an APK; the page may call it anything, and the file is read before it is installed.
        val assets = android.map { Asset(name = it.name, url = "$base/download/${it.id}", kind = Asset.kindOf(it.name).takeIf { kind -> kind != AssetKind.OTHER } ?: AssetKind.APK) }
        val shown = version ?: updated?.let(::dayOf) ?: "latest"
        val release = Release(id = shown, version = shown, publishedAtMs = updated, pageUrl = base, assets = assets)
        val listing = SourceListing(
            releases = listOf(release),
            name = LinkScanner.title(page)?.substringBeforeLast(" by ")?.trim()?.takeIf { it.isNotEmpty() },
            author = author(page) ?: Urls.host(base).substringBefore('.'),
            description = OG_DESCRIPTION.find(page)?.groupValues?.get(1)?.let(XmlScanner::decode)?.trim()?.takeIf { it.isNotEmpty() },
        )
        return CheckResult.Listing(listing)
    }

    /** A fresh address for the upload [asset] stands for, asked for with a fresh token from the page. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download {
        val id = asset.url.removePrefix("${spec.url}/download/").takeIf { it != asset.url && UPLOAD_NUMBER.matches(it) }
            ?: throw SourceException(SourceErrorKind.PARSE, "${asset.url} is not a file of ${spec.url}")
        val csrf = csrfToken(page(spec.url, context))
            ?: throw SourceException(SourceErrorKind.PARSE, "The page ${spec.url} carries no token to ask for its files with")
        val answer = post(fileEndpoint(spec.url, id), csrf, referer(spec.url, id), context)
        if (answer.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "itch.io no longer has upload $id of ${spec.url}")
        if (answer.status !in 200..299) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${answer.status} for upload $id of ${spec.url}")
        val named = answer.json?.string("url") ?: throw SourceException(SourceErrorKind.PARSE, "itch.io named no address for upload $id")
        val address = fileAddress(spec.url, named)
            ?: throw SourceException(SourceErrorKind.PARSE, "itch.io named an address outside its own file store for upload $id")
        return Download(address)
    }

    private class Upload(val id: String, val name: String, val android: Boolean)

    private class Answer(val status: Int, val json: JsonObject?)

    private fun page(url: String, context: CheckContext): String = context.http.execute(HttpRequest(url)).use {
        if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is nothing at $url")
        if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
        it.text(PAGE_CAP)
    }

    /** The download page itch.io hands out in place of the price, as its "No thanks" link does; null when it hands out none. */
    private fun downloadPage(base: String, csrf: String, context: CheckContext): String? {
        val address = post("$base/download_url", csrf, emptyMap(), context).json?.string("url")?.let { Urls.resolve(base, it) } ?: return null
        if (Urls.host(address) != Urls.host(base)) return null
        return context.http.execute(HttpRequest(address)).use { if (it.isSuccess) it.text(PAGE_CAP) else null }
    }

    /** A POST of the page's token, as the page's own script sends it. */
    private fun post(url: String, csrf: String, headers: Map<String, String>, context: CheckContext): Answer {
        val body = Json.write(Json.obj("csrf_token" to csrf))
        val request = HttpRequest.post(url, body, "application/json", headers + ("X-Requested-With" to "XMLHttpRequest"))
        return context.http.execute(request).use {
            Answer(it.status, if (it.isSuccess) objectOrNull(it.text(JSON_CAP)) else null)
        }
    }

    private fun objectOrNull(text: String): JsonObject? = try {
        Json.parse(text) as? JsonObject
    } catch (_: JsonException) {
        null
    }

    /** [named] as an address in itch.io's file store, or null when it is anywhere else. */
    private fun fileAddress(base: String, named: String): String? = Urls.resolve(base, named)?.takeIf { Urls.host(it) in FILE_HOSTS }

    private fun fileEndpoint(base: String, id: String) = "$base/file/$id?as_props=1&source=game_download"

    private fun referer(base: String, id: String) = mapOf("Referer" to "$base/download/$id")

    private fun csrfToken(html: String): String? =
        (CSRF_INPUT.find(html) ?: CSRF_JSON.find(html))?.groupValues?.get(1)?.replace("\\/", "/")?.takeIf { TOKEN.matches(it) }

    /** The uploads that have a download button, in the order of the page. */
    private fun uploads(html: String): List<Upload> {
        val starts = UPLOAD.findAll(html).take(MAX_BLOCKS).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, start ->
            val block = html.substring(start, minOf(starts.getOrNull(i + 1) ?: html.length, start + BLOCK_WINDOW))
            val button = DOWNLOAD_BUTTON.find(block)?.value ?: return@mapIndexedNotNull null
            val id = UPLOAD_ID.find(button)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val nameTag = NAME.find(block)
            val name = nameTag?.let { TITLE.find(it.groupValues[1])?.groupValues?.get(1) ?: it.groupValues[2] }
                ?.let(::plain)?.takeIf { it.isNotEmpty() } ?: "upload $id"
            Upload(id, name, platforms(block).contains(ANDROID_ICON))
        }
    }

    /** The platform icons of an upload: from its download_platforms span to the end of the name row. */
    private fun platforms(block: String): String {
        val at = PLATFORMS.find(block)?.range?.first ?: return ""
        val end = block.indexOf("</div", at, ignoreCase = true)
        return block.substring(at, if (end < 0) block.length else end)
    }

    private fun author(html: String): String? =
        FOLLOW.find(html)?.groupValues?.get(1)?.let(XmlScanner::decode)?.trim()?.takeIf { it.isNotEmpty() }

    /** The highest version the page's main part names as "v1.2.3" or "Version 1.2.3". */
    private fun pageVersion(html: String): String? {
        val widget = pageWidget(html)?.let(::withoutDrawings) ?: return null
        return (V_VERSION.findAll(widget) + WORD_VERSION.findAll(widget))
            .take(MAX_VERSIONS)
            .map { it.groupValues[1] }
            .maxWithOrNull(NUMBERS)
    }

    /** What is inside the page's main part, the div whose class list holds "page_widget". */
    private fun pageWidget(html: String): String? {
        val start = PAGE_WIDGET.find(html) ?: return null
        val open = html.indexOf('>', start.range.last)
        if (open < 0) return null
        var depth = 1
        var pos = open + 1
        while (true) {
            val edge = DIV_EDGE.find(html, pos) ?: return html.substring(open + 1)
            depth += if (edge.value.startsWith("</")) -1 else 1
            if (depth == 0) return html.substring(open + 1, edge.range.first)
            pos = edge.range.last + 1
        }
    }

    /** [html] without scripts, styles and drawings, whose numbers are no versions. */
    private fun withoutDrawings(html: String): String {
        val out = StringBuilder(html.length)
        var pos = 0
        while (true) {
            val open = DRAWING.find(html, pos) ?: break
            out.append(html, pos, open.range.first)
            val close = html.indexOf("</${open.groupValues[1]}", open.range.last, ignoreCase = true)
            if (close < 0) return out.toString()
            val end = html.indexOf('>', close)
            if (end < 0) return out.toString()
            pos = end + 1
        }
        return out.append(html, pos, html.length).toString()
    }

    /** The newest date on the page; itch.io writes them as "20 September 2026 @ 13:48 UTC". */
    private fun newestDate(html: String): Long? =
        ABBR_TITLE.findAll(html).take(MAX_DATES).mapNotNull { dateMs(it.groupValues[1]) }.maxOrNull()

    private fun dateMs(text: String): Long? = try {
        LocalDateTime.parse(text.trim(), ABBR_DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun dayOf(ms: Long): String = DAY.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    private fun plain(html: String): String =
        XmlScanner.decode(TAG.replace(html, " ").replace("&nbsp;", " ")).replace(SPACES, " ").trim()

    companion object {
        /** itch.io's file store, where the address it hands out for a file points. */
        val FILE_HOSTS: Set<String> = setOf("itchio-mirror.cb031a832f44726753d6267436f3b414.r2.cloudflarestorage.com")

        private const val HOST = "itch.io"
        private val RESERVED = setOf("www", "api", "static", "img")
        private val AUTHOR = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val GAME = Regex("[A-Za-z0-9_-]{1,100}")
        private val UPLOAD_NUMBER = Regex("\\d{1,15}")
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val JSON_CAP = 64 * 1024
        private const val MAX_UPLOADS = 8
        private const val MAX_BLOCKS = 200
        private const val BLOCK_WINDOW = 16 * 1024
        private const val MAX_VERSIONS = 1000
        private const val MAX_DATES = 500
        private const val ANDROID_ICON = "icon-android"

        private val CSRF_INPUT = Regex("""name="csrf_token" value="([^"]{1,512})"""")
        private val CSRF_JSON = Regex(""""csrf_token":"([^"]{1,512})"""")
        private val TOKEN = Regex("[A-Za-z0-9+/=._-]{1,512}")
        // Every run inside a tag is bounded, so a page without closing brackets costs no more than one with them.
        private val UPLOAD = Regex("""<div\s[^>]{0,1000}?class="(?:[^"]{0,500}\s)?upload(?:\s[^"]{0,500})?"""")
        private val DOWNLOAD_BUTTON = Regex("""<a\s[^>]{0,1000}?\bdownload_btn\b[^>]{0,1000}>""")
        private val UPLOAD_ID = Regex("""data-upload_id="(\d{1,15})"""")
        private val NAME = Regex(
            """(<strong\s[^>]{0,1000}?class="(?:[^"]{0,500}\s)?name(?:\s[^"]{0,500})?"[^>]{0,1000}>)(.*?)</strong>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TITLE = Regex("""\btitle="([^"]{0,500})"""")
        private val PLATFORMS = Regex("""class="(?:[^"]{0,500}\s)?download_platforms(?:\s[^"]{0,500})?"""")
        private val FOLLOW = Regex("""class="on_follow"[^>]{0,1000}>\s*<span class="full_label">\s*Follow ([^<]{1,200})</span>""")
        private val OG_DESCRIPTION = Regex("""<meta\s+property="og:description"\s+content="([^"]{0,2000})"""")
        private val PAGE_WIDGET = Regex("""<div\s[^>]{0,1000}?class="(?:[^"]{0,500}\s)?page_widget(?:\s[^"]{0,500})?"""")
        private val DIV_EDGE = Regex("""<div\b|</div\s*>""", RegexOption.IGNORE_CASE)
        private val DRAWING = Regex("""<(script|style|svg)\b""", RegexOption.IGNORE_CASE)
        private val V_VERSION = Regex("""[vV](\d{1,9}\.\d{1,9}(?:\.\d{1,9}){0,6})""")
        private val WORD_VERSION = Regex("""Version (\d{1,9}\.\d{1,9}(?:\.\d{1,9}){0,6})""")
        private val ABBR_TITLE = Regex("""<abbr\s[^>]{0,1000}?\btitle="([^"]{1,60})"""")
        private val TAG = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")

        private val ABBR_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMMM yyyy '@' HH:mm 'UTC'")
            .toFormatter(Locale.ENGLISH)
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)

        /** Dotted numbers by their parts, as itch.io's versions are compared: 1.10 after 1.9. */
        private val NUMBERS = Comparator<String> { a, b ->
            val x = a.split('.').map { it.toLongOrNull() ?: 0L }
            val y = b.split('.').map { it.toLongOrNull() ?: 0L }
            (0 until maxOf(x.size, y.size)).asSequence()
                .map { (x.getOrNull(it) ?: 0L).compareTo(y.getOrNull(it) ?: 0L) }
                .firstOrNull { it != 0 } ?: 0
        }
    }
}
