package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.NotesFormat
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
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A download page of Neutron Code's own site, neutroncode.com/downloads/file/<slug>. The page names
 * the file, its version and its date, and its last paragraph holds the notes of the latest version.
 * The file is at neutroncode.com/download/<file name>. The page is made anew for every request and
 * answers no conditional one, so it is read in full each time.
 */
class NeutronCodeSource : Source {
    override val type: String = SourceTypes.NEUTRONCODE

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != HOST) return null
        val slug = FILE_PAGE.find(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: return null
        return SourceSpec(type, "https://$HOST/downloads/file/$slug")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val html = context.http.execute(HttpRequest(spec.url)).use {
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no download page at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for ${spec.url}")
            it.text(PAGE_CAP)
        }
        val fileName = inside(html, "pd-filename")?.let { inside(it, "pd-float") }?.let(::plain)
            ?.takeIf { FILE_NAME.matches(it) && Asset.kindOf(it) in INSTALLABLE }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "${spec.url} offers no Android app")
        val version = afterLabel(html, "pd-version-txt")?.let(::plain)?.takeIf { it.isNotEmpty() }
            ?: throw SourceException(SourceErrorKind.PARSE, "${spec.url} names no version")
        val paragraphs = inside(html, "pd-fdesc")?.let { PARAGRAPH.findAll(it).map { p -> p.groupValues[1].trim() }.toList() }.orEmpty()
        val release = Release(
            id = version,
            version = version,
            notes = paragraphs.lastOrNull()?.takeIf { it.isNotEmpty() },
            notesFormat = NotesFormat.HTML,
            publishedAtMs = afterLabel(html, "pd-date-txt")?.let(::plain)?.let(::dateMs),
            pageUrl = spec.url,
            assets = listOf(Asset(name = fileName, url = "https://$HOST/download/${Urls.encodeSegment(fileName)}")),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = inside(html, "pd-title")?.let(::plain)?.takeIf { it.isNotEmpty() },
            author = "Neutron Code",
            // The first paragraph says what the file is for; the last one is the notes.
            description = paragraphs.takeIf { it.size > 1 }?.first()?.let(::plain)?.takeIf { it.isNotEmpty() },
        )
        return CheckResult.Listing(listing)
    }

    /** What the first element whose class list holds [name] contains, up to the first closing div after it. */
    private fun inside(html: String, name: String): String? {
        val at = classed(name).find(html)?.range?.last ?: return null
        val open = html.indexOf('>', at)
        if (open < 0) return null
        val close = html.indexOf("</div", open, ignoreCase = true)
        return html.substring(open + 1, if (close < 0) minOf(html.length, open + 1 + WINDOW) else close)
    }

    /** The element after the label whose class list holds [label], as in `<div class="pd-version-txt">Version:</div><div>2.28.3</div>`. */
    private fun afterLabel(html: String, label: String): String? {
        val at = classed(label).find(html)?.range?.last ?: return null
        val labelEnd = html.indexOf("</div", at, ignoreCase = true)
        if (labelEnd < 0) return null
        val next = html.indexOf("<div", labelEnd + 5, ignoreCase = true)
        if (next < 0 || next - labelEnd > WINDOW) return null
        val open = html.indexOf('>', next)
        if (open < 0) return null
        val close = html.indexOf("</div", open, ignoreCase = true)
        if (close < 0 || close - open > WINDOW) return null
        return html.substring(open + 1, close)
    }

    private fun classed(name: String): Regex = CLASSES.getValue(name)

    private fun plain(html: String): String =
        XmlScanner.decode(TAG.replace(html, " ").replace("&nbsp;", " ")).replace(SPACES, " ").trim()

    /**
     * A date such as "19 April 2026", the day, month and year in any order: the month is the word,
     * and the year is the number above 31 or the one with four digits.
     */
    private fun dateMs(text: String): Long? {
        val parts = text.split(' ').map { it.trim(',', '.') }.filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val monthAt = parts.indexOfFirst { it.toIntOrNull() == null }
        if (monthAt < 0) return null
        val month = MONTHS[parts[monthAt].lowercase().take(3)] ?: return null
        val numbers = parts.filterIndexed { i, _ -> i != monthAt }.map { it.toIntOrNull() ?: return null }
        val (a, b) = numbers
        val year = when {
            a > 31 -> a
            b > 31 -> b
            a.toString().length == 4 -> a
            else -> b
        }
        val day = if (a == year) b else a
        return try {
            LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    companion object {
        private const val HOST = "neutroncode.com"
        private const val PAGE_CAP = 2 * 1024 * 1024
        private const val WINDOW = 64 * 1024
        private val FILE_PAGE = Regex("^/downloads/file/([A-Za-z0-9_.-]{1,200})(?:/.*)?$", RegexOption.IGNORE_CASE)
        private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.()+ -]{0,199}")
        private val INSTALLABLE = setOf(AssetKind.APK, AssetKind.BUNDLE)
        private val CLASSES = listOf("pd-title", "pd-filename", "pd-float", "pd-version-txt", "pd-date-txt", "pd-fdesc")
            .associateWith { Regex("""class="(?:[^"]*\s)?${Regex.escape(it)}(?:\s[^"]*)?"""") }
        private val PARAGRAPH = Regex("""<p(?:\s[^>]*)?>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .withIndex().associate { (i, name) -> name to i + 1 }
    }
}
