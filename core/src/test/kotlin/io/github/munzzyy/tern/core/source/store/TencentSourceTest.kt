package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TencentSourceTest {
    private val source = TencentSource()
    private val canonical = "https://sj.qq.com/appdetail/org.example.app"
    private val pageUrl = "https://a.app.qq.com/o/simple.jsp?pkgname=org.example.app"

    private fun listing(http: FakeHttp): SourceListing =
        (source.check(source.match(canonical)!!, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing

    @Test
    fun matchesTheStoreAddressesAndKeepsThePackage() {
        for (url in listOf(
            canonical,
            "http://sj.qq.com/appdetail/org.example.app",
            "https://sj.qq.com/appdetail/org.example.app?from=share",
            "https://a.app.qq.com/o/simple.jsp?pkgname=org.example.app",
        )) {
            val spec = source.match(url)
            assertEquals(url, canonical, spec?.url)
            assertEquals(url, "org.example.app", spec?.option(SourceOptions.PACKAGE))
        }
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://sj.qq.com/"))
        assertNull(source.match("https://sj.qq.com/appdetail/"))
        assertNull(source.match("https://sj.qq.com/search/org.example.app"))
        assertNull(source.match("https://a.app.qq.com/o/other.jsp?pkgname=org.example.app"))
        assertNull(source.match("https://example.com/appdetail/org.example.app"))
    }

    @Test
    fun readsTheRecordOnTheDownloadPage() {
        val http = FakeHttp().resource(pageUrl, "store/tencent-page.html")
        val listing = listing(http)
        assertFalse(http.requestsTo(pageUrl).single().followRedirects)
        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An app for trying things out", listing.description)
        val release = listing.releases.single()
        assertEquals("5.2.0", release.version)
        assertEquals(520L, release.versionCode)
        assertEquals("520", release.id)
        assertEquals(canonical, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("org.example.app_5.2.0_64.apk", asset.name)
        assertEquals("https://imtt.dd.qq.com/sjy.00022/sjy.00004/16891/apk/00112233445566778899AABBCCDDEEFF.apk?fsname=org.example.app_5.2.0_64.apk", asset.url)
        assertEquals(4096000L, asset.size)
    }

    @Test
    fun aPhoneThatRunsOnly32BitCodeGetsTheOtherBuild() {
        val phone32 = DeviceProfile(listOf("armeabi-v7a", "armeabi"), sdk = 30, densityDpi = 320)
        val context = CheckContext(FakeHttp().resource(pageUrl, "store/tencent-page.html"), InMemoryValidatorStore(), device = phone32)
        val asset = (source.check(source.match(canonical)!!, context) as CheckResult.Listing).listing.releases.single().assets.single()
        assertEquals("org.example.app_5.2.0.apk", asset.name)
        assertTrue(asset.url.contains("/0123456789ABCDEF0123456789ABCDEF.apk"))
    }

    @Test
    fun aVersionCodeOfZeroMeansNone() {
        val page = Fixtures.text("store/tencent-page.html").replace("\"versionCode\":\"520\"", "\"versionCode\":\"0\"")
        val release = listing(FakeHttp().text(pageUrl, page)).releases.single()
        assertNull(release.versionCode)
        assertEquals("5.2.0", release.id)
    }

    @Test
    fun theFileNameFallsBackToThePackageAndVersion() {
        val page = Fixtures.text("store/tencent-page.html").replace(Regex("\\?fsname=[^\"]*"), "")
        assertEquals("org.example.app_5.2.0.apk", listing(FakeHttp().text(pageUrl, page)).releases.single().assets.single().name)
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        val page = Fixtures.text("store/tencent-page.html").replace("imtt.dd.qq.com", "files.example.org")
        val release = listing(FakeHttp().text(pageUrl, page)).releases.single()
        assertEquals("5.2.0", release.version)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun anAppTheStoreDoesNotKnowIsNotFound() {
        assertFailure(SourceErrorKind.NOT_FOUND) { listing(FakeHttp().resource(pageUrl, "store/tencent-missing.html")) }
        val moved = FakeHttp().on(pageUrl) { HttpResponse.of(302, "", Headers.of("Location" to "https://sj.qq.com/"), pageUrl) }
        assertFailure(SourceErrorKind.NOT_FOUND) { listing(moved) }
    }

    @Test
    fun aFailingStoreIsANetworkProblem() {
        assertFailure(SourceErrorKind.NETWORK) { listing(FakeHttp().text(pageUrl, "", status = 500)) }
    }

    @Test
    fun anAnswerForAnotherPackageIsRefused() {
        val page = Fixtures.text("store/tencent-page.html").replace("\"packageName\":\"org.example.app\"", "\"packageName\":\"org.example.other\"")
        assertFailure(SourceErrorKind.PARSE) { listing(FakeHttp().text(pageUrl, page)) }
    }

    private fun assertFailure(kind: SourceErrorKind, block: () -> Unit) {
        try {
            block()
            fail("expected a SourceException of kind $kind")
        } catch (e: SourceException) {
            assertEquals(e.message, kind, e.kind)
        }
    }
}
