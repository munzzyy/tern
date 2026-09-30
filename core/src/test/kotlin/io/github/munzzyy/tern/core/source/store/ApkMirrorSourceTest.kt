package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class ApkMirrorSourceTest {
    private val source = ApkMirrorSource()
    private val app = "https://www.apkmirror.com/apk/example-labs/example-app"
    private val feed = "$app/feed/"
    private val page = "$app/"

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore())

    private fun check(http: FakeHttp, spec: SourceSpec = SourceSpec(SourceTypes.APKMIRROR, app)): SourceListing =
        (source.check(spec, context(http)) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp): SourceErrorKind = try {
        source.check(SourceSpec(SourceTypes.APKMIRROR, app), context(http))
        fail("expected SourceException")
        error("unreachable")
    } catch (e: SourceException) {
        e.kind
    }

    @Test
    fun matchesAnAppAndItsReleasePages() {
        assertEquals(SourceSpec(SourceTypes.APKMIRROR, app), source.match("https://www.apkmirror.com/apk/example-labs/example-app/"))
        assertEquals(app, source.match("https://apkmirror.com/apk/example-labs/example-app/example-app-2-1-0-release/")?.url)
        assertEquals(app, source.match("http://www.apkmirror.com/apk/Example-Labs/Example-App")?.url)
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://www.apkmirror.com/apk/example-labs/"))
        assertNull(source.match("https://www.apkmirror.com/uploads/?devcategory=example-labs"))
        assertNull(source.match("https://www.apkmirror.com/"))
        assertNull(source.match("https://example.org/apk/example-labs/example-app"))
        assertNull(source.match("https://www.apkmirror.com.example.org/apk/example-labs/example-app"))
    }

    @Test
    fun followsTheFeedAndLearnsThePackageFromTheIcon() {
        val http = FakeHttp().resource(feed, "store/apkmirror_feed.xml").resource(page, "store/apkmirror_app.html")
        val listing = check(http)

        assertTrue(source.trackOnly)
        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals(mapOf(SourceOptions.PACKAGE to "org.example.app"), listing.learnedOptions)
        assertEquals(listOf("2.2.0", "2.1.0", "2.0.0"), listing.releases.map { it.version })

        val beta = listing.releases[0]
        assertTrue(beta.prerelease)
        assertEquals("Example App 2.2.0 beta 1 by Example Labs", beta.title)
        assertEquals("$app/example-app-2-2-0-beta-1-release/", beta.id)
        assertEquals("$app/example-app-2-2-0-beta-1-release/", beta.pageUrl)
        assertEquals(Instant.parse("2026-09-26T08:00:00Z").toEpochMilli(), beta.publishedAtMs)

        val stable = listing.releases[1]
        assertFalse(stable.prerelease)
        assertEquals(Instant.parse("2026-09-25T19:51:09Z").toEpochMilli(), stable.publishedAtMs)
        assertTrue(listing.releases.all { it.assets.isEmpty() })

        val elsewhere = listing.releases[2]
        assertNull(elsewhere.pageUrl)
        assertEquals("http://www.apkmirror.com/?p=1000001", elsewhere.id)
        assertNull(elsewhere.publishedAtMs)

        assertEquals(listOf(feed, page), http.requests.map { it.url })
        assertTrue(http.requests.all { it.headers["User-Agent"] == "APKUpdater-v3.5.9 Tern" })
    }

    @Test
    fun aKnownPackageSavesReadingThePage() {
        val http = FakeHttp().resource(feed, "store/apkmirror_feed.xml")
        val listing = check(http, SourceSpec(SourceTypes.APKMIRROR, app, mapOf(SourceOptions.PACKAGE to "org.example.app")))
        assertEquals("org.example.app", listing.packageName)
        assertTrue(listing.learnedOptions.isEmpty())
        assertEquals(listOf(feed), http.requests.map { it.url })
    }

    @Test
    fun aPageThatCannotBeReadLeavesThePackageUnknown() {
        val http = FakeHttp().resource(feed, "store/apkmirror_feed.xml").text(page, "", status = 403)
        val listing = check(http)
        assertNull(listing.packageName)
        assertTrue(listing.learnedOptions.isEmpty())
        assertEquals(3, listing.releases.size)
    }

    @Test
    fun anUnchangedFeedIsNotReadAgain() {
        val validators = InMemoryValidatorStore()
        val spec = SourceSpec(SourceTypes.APKMIRROR, app, mapOf(SourceOptions.PACKAGE to "org.example.app"))
        val first = FakeHttp().on(feed) { HttpResponse.of(200, Fixtures.text("store/apkmirror_feed.xml"), Headers.of("ETag" to "W/\"abc\""), feed) }
        source.check(spec, CheckContext(first, validators))

        val second = FakeHttp().on(feed) { HttpResponse.of(304, "", url = feed) }
        assertEquals(CheckResult.Unchanged, source.check(spec, CheckContext(second, validators)))
        assertEquals("W/\"abc\"", second.requests.single().headers["If-None-Match"])
    }

    @Test
    fun failuresAreReported() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(feed, "", status = 404)))
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(feed, "<html>Just a moment...</html>", status = 403)))
        assertEquals(SourceErrorKind.PARSE, failure(FakeHttp().text(feed, "<rss><channel><item>")))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(feed, "<rss><channel><title>Download Example App APKs for Android</title></channel></rss>")))
    }

    @Test
    fun readsTheVersionOutOfWhatTheTitleAdds() {
        assertEquals("9.0.36.643", ApkMirrorSource.versionOf("Example Player 9.0.36.643 (arm64-v8a) (Android 7.0+) APK Download by Example Labs"))
        assertEquals("25.01", ApkMirrorSource.versionOf("7-Example 25.01 (arm64-v8a) (Android 4.4+) APK Download by Example Labs"))
        assertEquals("42.5.15-21", ApkMirrorSource.versionOf("Example Store 42.5.15-21 [0] [PR] 630551585 (arm64-v8a) (Android 12+) by Example Labs"))
        assertEquals("6.32", ApkMirrorSource.versionOf("1.1.1.1: Example Tunnel 6.32 (arm64-v8a) (Android 5.0+) by Example, Inc."))
        assertEquals("1.1.5", ApkMirrorSource.versionOf("Example Installer (Official) 1.1.5 (arm64-v8a) (Android 8.0+) by Example Labs"))
        assertEquals("Example App", ApkMirrorSource.versionOf("Example App (Android 8.0+) by Example Labs"))
        assertEquals("Example App 1.2.3", ApkMirrorSource.cleanTitle("Example App 1.2.3 [0] (arm64-v8a) (Android 8.0+) by Example Labs"))
    }

    @Test
    fun aStageAfterTheVersionMarksAPrerelease() {
        assertTrue(ApkMirrorSource.isPrerelease("Example App 2.25.29.2 beta by Example Labs"))
        assertFalse(ApkMirrorSource.isPrerelease("Example App Beta 141.0.7390.20 by Example Labs"))
        assertFalse(ApkMirrorSource.isPrerelease("Example App 2.1.0 (arm64-v8a) by Example Labs"))
    }

    @Test
    fun readsThePackageFromTheIconFileName() {
        assertEquals("org.example.app", ApkMirrorSource.packageFromIcon("https://downloadr2.apkmirror.com/wp-content/uploads/2026/01/11/65a71d34ecd19_org.example.app.png"))
        assertEquals("org.example.my_app", ApkMirrorSource.packageFromIcon("https://downloadr2.apkmirror.com/wp-content/uploads/2026/01/11/65a71d34ecd19_org.example.my_app.png"))
        assertNull(ApkMirrorSource.packageFromIcon("https://downloadr2.apkmirror.com/wp-content/uploads/2026/01/11/65a71d34ecd19.png"))
        assertNull(ApkMirrorSource.packageFromIcon("https://downloadr2.apkmirror.com/wp-content/uploads/2026/01/11/65a71d34ecd19_com.apkmirror.helper.png"))
    }
}
