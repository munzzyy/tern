package io.github.munzzyy.tern.core.source.web

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
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.forge.Iso8601
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlScanner

/**
 * Telegram for Android as telegram.org hands it out. The version is the newest post of the channel
 * t.me/s/TAndroidAPK that names one, such as "12.10.5 (7105)", and the file is always at
 * telegram.org/dl/android/apk, which sends the download on to Telegram's file servers.
 */
class TelegramSource : Source {
    override val type: String = SourceTypes.TELEGRAM

    /**
     * telegram.org and its pages about the apps. The rest of the site, such as the blog, is not an
     * app, and the address of the file itself is left to [DirectSource].
     */
    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != HOST) return null
        val path = uri.rawPath.orEmpty().trimEnd('/').lowercase()
        if (path !in APP_PAGES) return null
        return SourceSpec(type, HOME)
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(it) }

    private fun checkOnce(context: CheckContext): CheckResult {
        val html = context.http.execute(HttpRequest(CHANNEL)).use {
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no channel at $CHANNEL")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $CHANNEL")
            it.text(PAGE_CAP)
        }
        val post = posts(html).lastOrNull()
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "No post on $CHANNEL names a version")
        val release = Release(
            id = post.version,
            version = post.version,
            versionCode = post.build?.let { it * 10 + STANDALONE_FLAVOUR },
            publishedAtMs = post.publishedAtMs,
            pageUrl = post.link,
            assets = listOf(Asset(name = "telegram-${post.version}.apk", url = APK)),
        )
        val listing = SourceListing(releases = listOf(release), name = "Telegram", author = "Telegram", packageName = PACKAGE)
        return CheckResult.Listing(listing.withIcons(HOME, listOf(ICON)))
    }

    /** Where the file is right now: telegram.org answers with a redirect to one of [FILE_HOSTS], which is only asked, not followed. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download {
        if (asset.url != APK) return Download(asset.url)
        context.http.execute(HttpRequest(APK, followRedirects = false)).use {
            if (it.isSuccess) return Download(APK)
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "telegram.org has no file at $APK")
            if (it.status !in REDIRECTS) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $APK")
            val location = it.headers["Location"]?.let { target -> Urls.resolve(APK, target) }
                ?: throw SourceException(SourceErrorKind.PARSE, "telegram.org sent the download on, but not to an https address")
            val host = Urls.host(location)
            if (host !in FILE_HOSTS) {
                throw SourceException(SourceErrorKind.PARSE, "telegram.org sent the download to $host, which is not one of Telegram's file servers")
            }
            return Download(location)
        }
    }

    private class Post(val version: String, val build: Long?, val publishedAtMs: Long?, val link: String?)

    /** The posts that name a version, in the order of the page: oldest first. */
    private fun posts(html: String): List<Post> {
        val starts = ArrayList<Int>()
        var at = html.indexOf(WRAP)
        while (at >= 0 && starts.size < MAX_POSTS) {
            starts.add(at)
            at = html.indexOf(WRAP, at + WRAP.length)
        }
        return starts.mapIndexedNotNull { i, start ->
            val end = minOf(starts.getOrNull(i + 1) ?: html.length, start + POST_WINDOW)
            post(html.substring(start, end))
        }
    }

    private fun post(chunk: String): Post? {
        val text = MESSAGE_TEXT.find(chunk)?.groupValues?.get(1) ?: return null
        val firstLine = plain(text.split(LINE_BREAK, limit = 2).first())
        val version = firstLine.substringBefore(' ').takeIf { VERSION.matches(it) } ?: return null
        val build = BUILD.find(firstLine)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 1..MAX_BUILD }
        val published = DATETIME.find(chunk)?.groupValues?.get(1)?.let(Iso8601::parseMs)
        val link = POST_ID.find(chunk)?.groupValues?.get(1)?.let { "https://t.me/$CHANNEL_NAME/$it" }
        return Post(version, build, published, link)
    }

    private fun plain(html: String): String =
        XmlScanner.decode(TAG.replace(html, " ").replace("&nbsp;", " ")).replace(SPACES, " ").trim()

    companion object {
        const val PACKAGE = "org.telegram.messenger.web"

        /** Telegram's own file servers, where telegram.org sends the download. */
        val FILE_HOSTS: Set<String> = setOf("cdn1.telesco.pe", "cdn2.telesco.pe", "cdn3.telesco.pe", "cdn4.telesco.pe", "cdn5.telesco.pe")

        private const val HOST = "telegram.org"
        private const val HOME = "https://telegram.org"
        private const val CHANNEL_NAME = "TAndroidAPK"
        private const val CHANNEL = "https://t.me/s/$CHANNEL_NAME"
        private const val APK = "https://telegram.org/dl/android/apk"
        private const val ICON = "https://telegram.org/img/apple-touch-icon.png"
        private val APP_PAGES = setOf("", "/android", "/apps", "/dl", "/dl/android")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /**
         * The file on telegram.org is Telegram's standalone "afat" build, whose version code is the
         * build number in the post times ten plus 9 (TMessagesProj_AppStandalone/build.gradle).
         */
        private const val STANDALONE_FLAVOUR = 9L

        /** Keeps the version code within what Android takes. */
        private const val MAX_BUILD = 214_748_363L

        private const val PAGE_CAP = 2 * 1024 * 1024
        private const val MAX_POSTS = 100
        private const val POST_WINDOW = 64 * 1024
        private const val WRAP = "tgme_widget_message_wrap"
        private val MESSAGE_TEXT = Regex("""class="[^"]*\btgme_widget_message_text\b[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_BREAK = Regex("""<br\s*/?>|\n""", RegexOption.IGNORE_CASE)
        private val VERSION = Regex("""\d+(?:\.\d+)+[0-9A-Za-z.-]{0,20}""")
        private val BUILD = Regex("""^\S+\s*\((\d{1,9})\)""")
        private val DATETIME = Regex("""<time[^>]*\bdatetime="([^"]{1,40})"""")
        private val POST_ID = Regex("""data-post="$CHANNEL_NAME/(\d{1,12})"""")
        private val TAG = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")
    }
}
