package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class ApkComboSourceTest {
    private val source = ApkComboSource()
    private val page = "https://apkcombo.com/example-app/org.example.app"
    private val downloads = "$page/download/apk"
    private val bucket = "https://apks.39b7cb94d40914bac590886981b0ed6e.r2.cloudflarestorage.com"
    private val universal = "$bucket/org.example.app/2.1.0/210.0123456789abcdef0123456789abcdef01234567.apk"
    private val bundle = "$bucket/org.example.app/2.1.0/210.89abcdef0123456789abcdef0123456789abcdef.apks"

    private fun spec() = source.match(page)!!

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore())

    private fun check(http: FakeHttp): SourceListing = (source.check(spec(), context(http)) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp): SourceErrorKind = try {
        source.check(spec(), context(http))
        fail("expected SourceException")
        error("unreachable")
    } catch (e: SourceException) {
        e.kind
    }

    private fun store() = FakeHttp().resource(page, "store/apkcombo_app.html").resource(downloads, "store/apkcombo_download.html")

    @Test
    fun matchesAppPagesWithOrWithoutALanguage() {
        assertEquals(SourceSpec(SourceTypes.APKCOMBO, page, mapOf(SourceOptions.PACKAGE to "org.example.app")), source.match("https://apkcombo.com/example-app/org.example.app/"))
        assertEquals(page, source.match("https://www.apkcombo.com/de/example-app/org.example.app/download/apk")?.url)
        assertEquals("https://apkcombo.com/vk/org.example.app", source.match("apkcombo.com/vk/org.example.app/download/apk")?.url)
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://apkcombo.com/developer/Example%20Labs/"))
        assertNull(source.match("https://apkcombo.com/category/tools/"))
        assertNull(source.match("https://apkcombo.com/example-app"))
        assertNull(source.match("https://example.org/example-app/org.example.app"))
        assertNull(source.match("https://apkcombo.com.example.org/example-app/org.example.app"))
        assertNull(source.match("https://m.apkcombo.com/example-app/org.example.app"))
    }

    @Test
    fun readsTheAppAndTheNewestBuildForEachSetOfProcessors() {
        val http = store()
        val listing = check(http)

        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        val release = listing.releases.single()
        assertEquals("210", release.id)
        assertEquals("2.1.0", release.version)
        assertEquals(210L, release.versionCode)
        assertEquals(Instant.parse("2026-09-25T00:00:00Z").toEpochMilli(), release.publishedAtMs)
        assertEquals(page, release.pageUrl)
        assertEquals(listOf("arm64-v8a-armeabi-v7a-x86-x86_64-210.apk", "arm64-v8a-210.apks"), release.assets.map { it.name })
        assertEquals(listOf(universal, bundle), release.assets.map { it.url })
        assertEquals(listOf(AssetKind.APK, AssetKind.BUNDLE), release.assets.map { it.kind })

        assertEquals(listOf(page, downloads), http.requests.map { it.url })
        assertTrue("asked as Tern, which PoliteHttp names", http.requests.all { it.headers == mapOf("Accept" to "*/*") })
    }

    @Test
    fun withoutACodeOnTheFilesTheInformationTableGivesIt() {
        val bare = Fixtures.text("store/apkcombo_download.html").replace("<span class=\"vercode\">(210)</span>", "")
        val release = check(FakeHttp().resource(page, "store/apkcombo_app.html").text(downloads, bare)).releases.single()
        assertEquals(210L, release.versionCode)
        assertEquals(listOf("arm64-v8a-armeabi-v7a-x86-x86_64.apk", "arm64-v8a.apks"), release.assets.map { it.name })
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        val addresses = check(store()).releases.single().assets.map { it.url }
        assertTrue(addresses.all { it.startsWith("$bucket/") })
        assertTrue(addresses.none { it.contains("x86.apk") })
    }

    @Test
    fun aDownloadIsResolvedToTheAddressSignedNow() {
        val asset = check(store()).releases.single().assets[0]
        val fresh = Fixtures.text("store/apkcombo_download.html").replace("deadbeef01", "cafef00d01")
        val http = FakeHttp().text(downloads, fresh)

        val download = SourceRegistry(listOf(source)).resolve(spec(), asset, context(http))
        assertTrue(download.url.startsWith("$universal?response-content-disposition="))
        assertTrue(download.url.endsWith("&X-Amz-Signature=cafef00d01"))
        assertEquals(listOf(downloads), http.requests.map { it.url })
    }

    @Test
    fun aFileNoLongerOfferedCannotBeResolved() {
        val gone = Asset("arm64-v8a-200.apk", "$bucket/org.example.app/2.0.0/200.2222222222222222222222222222222222222222.apk")
        try {
            source.resolve(spec(), gone, context(FakeHttp().resource(downloads, "store/apkcombo_download.html")))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun aMissingAppIsNotFoundAndAPageWithoutFilesHasNoRelease() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "", status = 404)))
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(page, "", status = 403)))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, "<html><body>Example App</body></html>")))
        val empty = FakeHttp().resource(page, "store/apkcombo_app.html").text(downloads, "<html><body>No files</body></html>")
        assertEquals(SourceErrorKind.NO_RELEASES, failure(empty))
    }
}
