package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class TelegramSourceTest {
    private val source = TelegramSource()
    private val channel = "https://t.me/s/TAndroidAPK"
    private val apk = "https://telegram.org/dl/android/apk"
    private val spec = SourceSpec(source.type, "https://telegram.org")

    private fun listing(http: FakeHttp): SourceListing =
        (source.check(spec, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp, block: (CheckContext) -> Unit = { source.check(spec, it) }): SourceException {
        try {
            block(CheckContext(http, InMemoryValidatorStore()))
        } catch (e: SourceException) {
            return e
        }
        fail("expected a SourceException")
        throw AssertionError()
    }

    private fun post(text: String, number: Int = 7, time: String = "2026-03-01T12:00:00+00:00") =
        """<div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post="TAndroidAPK/$number">""" +
            """<div class="tgme_widget_message_text js-message_text" dir="auto">$text</div>""" +
            """<time datetime="$time" class="time">12:00</time></div></div>"""

    @Test
    fun theSiteAndItsPagesAboutTheAppsAreTelegram() {
        for (url in listOf(
            "https://telegram.org",
            "https://telegram.org/",
            "telegram.org/android",
            "https://www.telegram.org/apps/",
            "http://telegram.org/dl/android",
            "https://telegram.org/dl",
            "https://telegram.org/?setln=en",
            "https://TELEGRAM.ORG/Android",
        )) {
            assertEquals(url, SourceSpec(source.type, "https://telegram.org"), source.match(url))
        }
    }

    @Test
    fun theRestOfTheSiteAndOtherHostsAreNot() {
        for (url in listOf(
            "https://telegram.org/blog/example-post",
            "https://telegram.org/faq",
            "https://telegram.org/privacy",
            "https://desktop.telegram.org/",
            "https://core.telegram.org/api",
            "https://t.me/s/TAndroidAPK",
            "https://telegram.org.example.com/android",
            "https://example.org/android",
        )) {
            assertNull(url, source.match(url))
        }
    }

    @Test
    fun theAddressOfTheFileItselfIsLeftToTheDirectSource() {
        assertNull(source.match(apk))
    }

    @Test
    fun readsTheNewestPostThatNamesAVersion() {
        val http = FakeHttp().resource(channel, "web/telegram_channel.html")
        val listing = listing(http)
        val release = listing.releases.single()
        assertEquals("4.1.2", release.version)
        assertEquals("4.1.2", release.id)
        assertEquals(41209L, release.versionCode)
        assertEquals(1771096609000L, release.publishedAtMs)
        assertEquals("https://t.me/TAndroidAPK/202", release.pageUrl)
        assertEquals(listOf(Asset("telegram-4.1.2.apk", apk)), release.assets)
        assertEquals(AssetKind.APK, release.assets.single().kind)
        assertEquals("org.telegram.messenger.web", listing.packageName)
        assertEquals("Telegram", listing.name)
        assertEquals("https://telegram.org/img/apple-touch-icon.png", listing.iconUrl)
        assertEquals(listOf(channel), http.requests.map { it.url })
    }

    @Test
    fun aPostWithoutABuildNumberGivesNoVersionCode() {
        val http = FakeHttp().text(channel, "<html><body>" + post("4.2.0<br/>Directly downloadable") + "</body></html>")
        val release = listing(http).releases.single()
        assertEquals("4.2.0", release.version)
        assertNull(release.versionCode)
        assertEquals("https://t.me/TAndroidAPK/7", release.pageUrl)
    }

    @Test
    fun aChannelWithoutAVersionHasNoRelease() {
        val http = FakeHttp().text(channel, "<html><body>" + post("Nothing new today") + "</body></html>")
        assertEquals(SourceErrorKind.NO_RELEASES, failure(http).kind)
    }

    @Test
    fun aMissingChannelIsNotFoundAndAFailingOneIsANetworkProblem() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(channel, "", status = 404)).kind)
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(channel, "", status = 502)).kind)
    }

    private fun redirecting(location: String) = FakeHttp().on(apk) { HttpResponse.of(302, "", Headers.of("Location" to location), apk) }

    @Test
    fun resolveAsksWhereTheFileIsWithoutGoingThere() {
        val cdn = "https://cdn4.telesco.pe/file/Telegram.apk?token=abc-123"
        val http = redirecting(cdn)
        val download = source.resolve(spec, Asset("telegram-4.1.2.apk", apk), CheckContext(http, InMemoryValidatorStore()))
        assertEquals(cdn, download.url)
        assertEquals(emptyMap<String, String>(), download.headers)
        assertFalse(http.requests.single().followRedirects)
    }

    @Test
    fun resolveTakesTheAddressItselfWhenTheSiteServesTheFile() {
        val http = FakeHttp().on(apk) { HttpResponse.of(200, "PK", Headers.of("Content-Type" to "application/vnd.android.package-archive"), apk) }
        assertEquals(apk, source.resolve(spec, Asset("telegram-4.1.2.apk", apk), CheckContext(http, InMemoryValidatorStore())).url)
    }

    @Test
    fun resolveRefusesAFileServerThatIsNotTelegrams() {
        for (location in listOf("https://files.example.org/Telegram.apk", "http://cdn4.telesco.pe/file/Telegram.apk", "https://cdn4.telesco.pe.example.org/x.apk")) {
            val e = failure(redirecting(location)) { source.resolve(spec, Asset("telegram-4.1.2.apk", apk), it) }
            assertEquals(location, SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun theRegistryHandsOnTheResolvedAddress() {
        val cdn = "https://cdn1.telesco.pe/file/Telegram.apk?token=xyz"
        val registry = SourceRegistry(listOf(source))
        val download = registry.resolve(spec, Asset("telegram-4.1.2.apk", apk), CheckContext(redirecting(cdn), InMemoryValidatorStore()))
        assertEquals(cdn, download.url)
    }
}
