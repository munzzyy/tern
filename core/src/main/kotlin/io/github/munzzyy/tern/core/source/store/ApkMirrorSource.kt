package io.github.munzzyy.tern.core.source.store

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

/**
 * APKMirror, whose owners forbid downloads by others, so it is only followed. The app's feed at
 * `<app>/feed/` names each release: the version is read from its title, the date from when it was
 * posted. The package is read from the file name of the app's icon on its page, where the name
 * holds it, once; it is then kept with the source.
 */
class ApkMirrorSource : Source {
    override val type: String = SourceTypes.APKMIRROR

    override val republishes: Boolean get() = true

    override val trackOnly: Boolean get() = true

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
        val (channel, validator) = context.http.execute(HttpRequest(feedUrl, headers = HEADERS + conditional)).use {
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

        val known = spec.option(SourceOptions.PACKAGE)?.takeIf { FDroidSource.isValidPackage(it) }
        val icon = if (known == null) pageIcon(spec, context) else null
        val learned = if (known == null) icon?.let(::packageFromIcon) else null
        val listing = SourceListing(
            releases = releases,
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

    /** The address of the app's icon as its page names it, or null when the page cannot be read. */
    private fun pageIcon(spec: SourceSpec, context: CheckContext): String? {
        val html = try {
            context.http.execute(HttpRequest("${spec.url}/", headers = HEADERS)).use { if (it.isSuccess) it.text(PAGE_CAP) else null }
        } catch (_: IOException) {
            null
        } ?: return null
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

        /** APKMirror lets through only clients whose User-Agent names APKUpdater, as Obtainium's does; the rest is who asks. */
        private val HEADERS = mapOf("User-Agent" to "APKUpdater-v3.5.9 Tern")

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

        /** The package an icon's file name holds, as in `65a71d34ecd19_com.example.app.png`. */
        internal fun packageFromIcon(url: String): String? {
            val file = Urls.segments(url).lastOrNull() ?: return null
            val stem = (if (file.lastIndexOf('.') > 0) file.substring(0, file.lastIndexOf('.')) else file).replace(SIZE_SUFFIX, "")
            val candidates = listOf(stem.substringAfter('_', "")) + stem.split('_').reversed()
            return candidates.firstOrNull { FDroidSource.isValidPackage(it) && !it.lowercase().contains("apkmirror") }
        }
    }
}
