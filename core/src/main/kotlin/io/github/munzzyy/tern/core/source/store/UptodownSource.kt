package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Uptodown, read from the English download page of an app: the version, the name, the author and
 * the technical details, among them the package, the date and the kind of file. The file is listed
 * at `<page>/<file number>-x`, an address that lasts; Uptodown's own addresses expire, so [resolve]
 * asks the service Uptodown's app uses, with an anonymous session, where the file is right now.
 * Searched through the site's own search.
 *
 * [newIdentifier] names the device to the service when a session is opened; tests fix it.
 */
class UptodownSource(private val newIdentifier: () -> String = { randomIdentifier() }) : Source, Searchable {
    override val type: String = SourceTypes.UPTODOWN

    override val republishes: Boolean get() = true

    override val origin: String = "Uptodown"

    private class Session(val token: String, val expiresAtSeconds: Long)

    @Volatile
    private var session: Session? = null

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.endsWith(".uptodown.com")) return null
        val labels = host.removeSuffix(".uptodown.com").split('.')
        val app = when {
            labels.size == 2 && LANGUAGE.matches(labels[1]) -> labels[0]
            // Without a language the page is the Spanish one, as in <app>.uptodown.com/android/descargar.
            labels.size == 1 && labels[0].length > 2 -> labels[0]
            else -> return null
        }
        if (!NAME.matches(app) || app in NOT_APPS) return null
        return SourceSpec(type, "https://$app.en.uptodown.com/android/download")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val html = page(spec.url, context)
        val version = elementText(html, "version") { tag -> tag.name == "div" && "version" in classes(tag) }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "The page at ${spec.url} names no version")
        val fileId = fileNumber(html) ?: throw SourceException(SourceErrorKind.NO_RELEASES, "The page at ${spec.url} offers no file")
        val (details, cells) = technicalDetails(html)
        val pkg = (details["package name"] ?: cells.lastOrNull())?.takeIf { FDroidSource.isValidPackage(it) }
        val date = details["date"] ?: cells.getOrNull(cells.size - 5)
        val extension = (details["file type"] ?: cells.getOrNull(cells.size - 4))?.lowercase()?.takeIf { it in EXTENSIONS } ?: "apk"
        val asset = Asset(
            name = "${pkg ?: Urls.host(spec.url).substringBefore('.')}.$extension",
            url = "${spec.url}/$fileId-x",
            // The page names the digest of the very file it offers.
            sha256 = details["sha256"]?.lowercase()?.takeIf { SHA256.matches(it) },
        )
        val release = Release(
            id = fileId,
            version = version,
            publishedAtMs = date?.let(::parseDate),
            pageUrl = spec.url,
            assets = listOf(asset),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = elementText(html, "detail-app-name") { it.attributes["id"] == "detail-app-name" },
            author = elementText(html, "author-link") { it.attributes["id"] == "author-link" },
            packageName = pkg,
        )
        val icon = tags(html, "og:image").firstOrNull { it.name == "meta" && it.attributes["property"] == "og:image" }?.attributes?.get("content")
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(icon)))
    }

    /**
     * The page of the file names the numbers the app and the file go by, and the service is asked,
     * with a session, where that file is now, as Uptodown's app does. A refused session is renewed once.
     */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = guarded(context) {
        if (!FILE_PAGE.matches(asset.url.removePrefix(spec.url))) {
            throw SourceException(SourceErrorKind.UNSUPPORTED, "${asset.url} is not a file of ${spec.url}")
        }
        val html = page(asset.url, it)
        val button = tags(html, "detail-download-button").firstOrNull { tag -> tag.attributes["id"] == "detail-download-button" }
        val heading = tags(html, "detail-app-name").firstOrNull { tag -> tag.attributes["id"] == "detail-app-name" }
        val appId = (button?.attributes?.get("data-app-id") ?: heading?.attributes?.get("data-code"))?.takeIf { id -> NUMBER.matches(id) }
        val fileId = fileNumber(html)
        if (appId == null || fileId == null) throw SourceException(SourceErrorKind.NOT_FOUND, "Uptodown no longer offers the file at ${asset.url}")
        Download(downloadAddress(appId, fileId, it), mapOf("User-Agent" to USER_AGENT))
    }

    override fun search(query: String, context: CheckContext): List<Hit> = guarded(context) {
        val text = query.trim().take(MAX_QUERY)
        if (text.isEmpty()) return@guarded emptyList()
        val request = HttpRequest.post(SEARCH, "queryString=${Urls.encodeSegment(text)}", FORM, mapOf("User-Agent" to USER_AGENT))
        it.http.execute(request).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${response.status} from the Uptodown search")
            val answer = Json.parseObject(response.text(BODY_CAP))
            if (answer.long("success") != 1L) throw SourceException(SourceErrorKind.NETWORK, "The Uptodown search failed")
            answer.obj("data")?.array("apps")?.objects().orEmpty()
                .mapNotNull(::hit)
                .distinctBy { hit -> hit.url }
                .take(MAX_HITS)
        }
    }

    private fun hit(app: JsonObject): Hit? {
        if (app.string("platformURL") != "/android") return null
        val url = app.string("url")?.let(::match)?.url ?: return null
        val name = app.string("name")?.let(::plain) ?: return null
        return Hit(name = name, owner = app.string("author")?.trim()?.takeIf { it.isNotEmpty() }, description = null, url = url)
    }

    private fun page(url: String, context: CheckContext): String = context.http.execute(HttpRequest(url)).use {
        if (it.status == 404 || it.status == 410) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no Uptodown page at $url")
        if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $url")
        it.text(PAGE_CAP)
    }

    /** The number of the file the page offers. */
    private fun fileNumber(html: String): String? {
        val button = tags(html, "detail-download-button").firstOrNull { it.attributes["id"] == "detail-download-button" }
        val heading = tags(html, "detail-app-name").firstOrNull { it.attributes["id"] == "detail-app-name" }
        return (button?.attributes?.get("data-file-id") ?: heading?.attributes?.get("data-file-id"))?.takeIf { NUMBER.matches(it) }
    }

    private fun downloadAddress(appId: String, fileId: String, context: CheckContext): String {
        repeat(2) { attempt ->
            val token = session(context, renew = attempt > 0)
            val request = HttpRequest("https://$API_HOST/eapi/apps/$appId/file/$fileId/downloadUrl", headers = CLIENT, authorization = "Bearer $token")
            context.http.execute(request).use {
                if (it.status == 401) return@repeat
                if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Uptodown no longer offers file $fileId")
                if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} from Uptodown")
                val answer = Json.parseObject(it.text(BODY_CAP))
                val address = answer.takeIf { a -> a.long("success") == 1L }?.obj("data")?.string("downloadURL")
                    ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Uptodown could not say where file $fileId is")
                val normalized = address.takeIf(Urls::isHttps)?.let(Urls::normalize)
                if (normalized == null || Urls.host(normalized) !in FILE_HOSTS) {
                    throw SourceException(SourceErrorKind.PARSE, "Uptodown named a file somewhere other than its download servers")
                }
                return normalized
            }
        }
        throw SourceException(SourceErrorKind.AUTH, "Uptodown refused the session twice")
    }

    /** A token for the service: the one held while it has more than a minute to run, or a new one. */
    private fun session(context: CheckContext, renew: Boolean): String {
        val now = context.nowMs() / 1000
        session?.takeIf { !renew && now < it.expiresAtSeconds - 60 }?.let { return it.token }
        val identifier = newIdentifier()
        val body = "identifier=$identifier&id_plataforma=13&lang=en&unixtime=$now&hmac=${signature(now.toString())}"
        val request = HttpRequest.post("https://$API_HOST$AUTH_PATH?identifier=$identifier", body, FORM, CLIENT)
        val token = context.http.execute(request).use {
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Uptodown opened no session: status ${it.status}")
            Json.parseObject(it.text(BODY_CAP)).string("token")
        }
        val expires = token?.let(::expiry) ?: throw SourceException(SourceErrorKind.PARSE, "Uptodown answered with a session that cannot be read")
        session = Session(token, expires)
        return token
    }

    /** When [token], a JSON web token, runs out, in seconds since 1970; null when it is not one. */
    private fun expiry(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            Json.parseObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).long("exp")
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JsonException) {
            null
        }
    }

    private fun parseDate(text: String): Long? {
        for (format in DATES) {
            try {
                return LocalDate.parse(text.trim(), format).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private class Tag(val name: String, val attributes: Map<String, String>, val end: Int)

    /** The start tags that hold [hint], in the order of the page. */
    private fun tags(html: String, hint: String): Sequence<Tag> = START_TAG.findAll(html)
        .filter { hint in it.value }
        .map { Tag(it.groupValues[1].lowercase(), attributes(it.groupValues[2]), it.range.last + 1) }

    /** The text of the first element whose start tag holds [hint] and passes [wanted], up to where the first element of its name closes. */
    private fun elementText(html: String, hint: String, wanted: (Tag) -> Boolean): String? {
        val tag = tags(html, hint).firstOrNull(wanted) ?: return null
        val close = html.indexOf("</${tag.name}", tag.end, ignoreCase = true)
        if (close < 0 || close - tag.end > TEXT_WINDOW) return null
        return plain(html.substring(tag.end, close))
    }

    /** Where the element [tag] opens ends: at its own closing tag, with elements of its name inside it counted. */
    private fun closeOf(html: String, tag: Tag): Int {
        var depth = 1
        for (m in Regex("<(/?)${Regex.escape(tag.name)}\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html, tag.end)) {
            depth += if (m.groupValues[1] == "/") -1 else 1
            if (depth == 0) return m.range.first
        }
        return html.length
    }

    /**
     * The rows of the technical details, which may be spread over several tables, by the label in
     * their header cell with the text of their last cell; and every cell with text in the order of
     * the page, for a page whose rows carry no label.
     */
    private fun technicalDetails(html: String): Pair<Map<String, String>, List<String>> {
        val section = tags(html, "technical-information").firstOrNull { it.attributes["id"] == "technical-information" }
            ?: return emptyMap<String, String>() to emptyList()
        val region = html.substring(section.end, minOf(closeOf(html, section), section.end + TABLE_WINDOW))
        val labelled = LinkedHashMap<String, String>()
        for (row in ROW.findAll(region)) {
            val label = HEAD.find(row.groupValues[1])?.groupValues?.get(1)?.let(::plain)?.lowercase() ?: continue
            val value = CELL.findAll(row.groupValues[1]).lastOrNull()?.groupValues?.get(1)?.let(::plain) ?: continue
            labelled.putIfAbsent(label, value)
        }
        return labelled to CELL.findAll(region).mapNotNull { plain(it.groupValues[1]) }.toList()
    }

    companion object {
        private const val API_HOST = "www.uptodown.app"
        private const val AUTH_PATH = "/eapi/auth/token"
        private const val SEARCH = "https://en.uptodown.com/android/en/s"
        private const val CLIENT_VERSION = "739"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 16; Pixel 8 Pro Build/BP4A.260205.001)"

        /** The key Uptodown's app signs the time with to open an anonymous session. */
        private const val SESSION_KEY = "MDGMXUMdvHJBG/vjdFgmqX6LUdy7ecfwvYNd0gyfOCs="
        private const val FORM = "application/x-www-form-urlencoded"
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val BODY_CAP = 1024 * 1024
        private const val TEXT_WINDOW = 4000
        private const val TABLE_WINDOW = 64 * 1024
        private const val MAX_QUERY = 200
        private const val MAX_HITS = 20

        /** Where the service says Uptodown's files are. */
        val FILE_HOSTS = setOf("dw.uptodown.com", "dw.uptodown.net")

        private val CLIENT = mapOf("User-Agent" to USER_AGENT, "Identificador" to "Uptodown_Android", "Identificador-Version" to CLIENT_VERSION)
        private val EXTENSIONS = setOf("apk", "xapk", "apks")
        private val LANGUAGE = Regex("[a-z]{2,3}")
        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,62}")
        private val NOT_APPS = setOf("www", "dw", "img", "blog", "static", "api")
        private val NUMBER = Regex("[0-9]{1,18}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FILE_PAGE = Regex("/[0-9]{1,18}-x")
        private val DATES = listOf(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US), DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        private val START_TAG = Regex("""<([A-Za-z][A-Za-z0-9]*)((?:\s[^<>]*)?)>""")
        private val ATTRIBUTE = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'<>`=]+))""")
        private val ROW = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val HEAD = Regex("<th\\b[^>]*>(.*?)</th>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val CELL = Regex("<td\\b[^>]*>(.*?)</td>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val MARKUP = Regex("<[^>]*>")
        private val SPACE = Regex("\\s+")

        private fun randomIdentifier(): String = hex(ByteArray(8).also(SecureRandom()::nextBytes))

        private fun signature(text: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(SESSION_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return hex(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun attributes(text: String): Map<String, String> {
            val found = LinkedHashMap<String, String>()
            for (m in ATTRIBUTE.findAll(text)) {
                val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
                found.putIfAbsent(m.groupValues[1].lowercase(), XmlScanner.decode(value))
            }
            return found
        }

        private fun classes(tag: Tag): List<String> = tag.attributes["class"]?.split(SPACE).orEmpty()

        /** What a person reads of a piece of markup, or null when that is nothing. */
        private fun plain(fragment: String): String? =
            XmlScanner.decode(fragment.replace(MARKUP, " ").replace("&nbsp;", " ")).replace(SPACE, " ").trim().ifEmpty { null }
    }
}
