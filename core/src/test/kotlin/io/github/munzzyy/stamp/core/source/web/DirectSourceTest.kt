package io.github.munzzyy.stamp.core.source.web

import io.github.munzzyy.stamp.core.model.AssetKind
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.Headers
import io.github.munzzyy.stamp.core.net.HttpResponse
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DirectSourceTest {
    private val source = DirectSource()

    @Test
    fun matchesHttpsApkUrl() {
        val spec = source.match("https://example.com/downloads/app.apk")
        assertEquals("https://example.com/downloads/app.apk", spec?.url)
    }

    @Test
    fun doesNotMatchNonApkUrl() {
        assertNull(source.match("https://example.com/downloads/app.zip"))
    }

    @Test
    fun upgradesATypedHttpAddress() {
        assertEquals("https://example.com/app.apk", source.match("http://example.com/app.apk")?.url)
        assertNull(source.match("ftp://example.com/app.apk"))
    }

    @Test
    fun usesEtagAsIdentity() {
        val url = "https://example.com/app.apk"
        val http = FakeHttp().on(url) { HttpResponse.of(200, "", Headers.of("ETag" to "\"v1\"", "Content-Length" to "42"), url) }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("\"v1\"", listing.releases[0].id)
        assertEquals(42L, listing.releases[0].assets[0].size)
    }

    @Test
    fun retriesAsRangeGetWhenHeadIsForbidden() {
        val url = "https://example.com/app.apk"
        val http = FakeHttp().on(url) { request ->
            if (request.method == "HEAD") HttpResponse.of(403, "", Headers.EMPTY, url)
            else HttpResponse.of(206, "x", Headers.of("Content-Range" to "bytes 0-0/999"), url)
        }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(999L, listing.releases[0].assets[0].size)
    }

    @Test
    fun notModifiedReturnsUnchanged() {
        val url = "https://example.com/app.apk"
        val store = InMemoryValidatorStore()
        val http = FakeHttp().on(url) { HttpResponse.of(304, "", Headers.EMPTY, url) }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, store))
        assertTrue(result is CheckResult.Unchanged)
    }

    @Test
    fun versionFromLastModifiedDate() {
        val url = "https://example.com/app.apk"
        val http = FakeHttp().on(url) {
            HttpResponse.of(200, "", Headers.of("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT", "Content-Length" to "5"), url)
        }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("2015.10.21", listing.releases[0].version)
    }

    private val bare = "https://example.org/dl/android/apk"

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore())

    private fun serving(vararg headers: Pair<String, String>, finalUrl: String = bare, headStatus: Int = 200): FakeHttp =
        FakeHttp().on(bare) { request ->
            if (request.method == "HEAD" && headStatus != 200) {
                HttpResponse.of(headStatus, "", Headers.EMPTY, bare)
            } else {
                HttpResponse.of(if (request.method == "HEAD") 200 else 206, "x", Headers.of(headers.toList() + ("ETag" to "\"e1\"")), finalUrl)
            }
        }

    private fun listed(http: FakeHttp) = (source.check(SourceSpec(source.type, bare), context(http)) as CheckResult.Listing).listing.releases[0].assets[0]

    @Test
    fun probeRecognisesTheApkContentType() {
        val http = serving("Content-Type" to "application/vnd.android.package-archive; charset=binary", "Content-Length" to "64")
        assertEquals(SourceSpec(source.type, bare), source.probe(bare, context(http)))
        assertEquals(listOf("HEAD"), http.requests.map { it.method })
    }

    @Test
    fun probeRecognisesAnInstallableNameInContentDisposition() {
        val http = serving("Content-Type" to "application/octet-stream", "Content-Disposition" to "attachment; filename=\"Example.apk\"")
        assertEquals(bare, source.probe(bare, context(http))?.url)
        assertEquals("Example.apk", listed(http).name)
    }

    @Test
    fun probeAsksForOneByteWhenHeadIsRefused() {
        val http = serving("Content-Type" to "application/vnd.android.package-archive", headStatus = 405)
        assertEquals(bare, source.probe(bare, context(http))?.url)
        val get = http.requests.last()
        assertEquals("GET", get.method)
        assertEquals("bytes=0-0", get.headers["Range"])
        assertTrue(http.requests.none { it.method == "GET" && it.headers["Range"] == null })
    }

    @Test
    fun probeIgnoresPagesArchivesAndFailures() {
        assertNull(source.probe(bare, context(serving("Content-Type" to "text/html"))))
        assertNull(source.probe(bare, context(serving("Content-Type" to "application/octet-stream", "Content-Disposition" to "attachment; filename=app.zip"))))
        assertNull(source.probe(bare, context(serving("Content-Type" to "application/octet-stream", finalUrl = "https://cdn.example.org/app.apk"))))
        val missing = FakeHttp().on(bare) { HttpResponse.of(404, "", Headers.of("Content-Type" to "application/vnd.android.package-archive"), bare) }
        assertNull(source.probe(bare, context(missing)))
        assertNull(source.probe("http://", context(FakeHttp())))
    }

    @Test
    fun nameComesFromTheFinalAddressWhenNothingElseNamesIt() {
        val http = serving("Content-Type" to "application/vnd.android.package-archive", finalUrl = "https://cdn.example.org/file/Example-2.1.apk?token=abc")
        val asset = listed(http)
        assertEquals("Example-2.1.apk", asset.name)
        assertEquals(bare, asset.url)
        assertEquals(AssetKind.APK, asset.kind)
    }

    @Test
    fun anApkByTypeAloneIsAnApkEvenWithoutAnExtension() {
        val asset = listed(serving("Content-Type" to "application/vnd.android.package-archive"))
        assertEquals("apk", asset.name)
        assertEquals(AssetKind.APK, asset.kind)
    }

    @Test
    fun anAddressThatStopsServingAnAppIsNoRelease() {
        try {
            listed(serving("Content-Type" to "text/html"))
            fail("expected a failure")
        } catch (e: SourceException) {
            assertTrue(e.message.orEmpty().contains("no longer serves"))
        }
    }
}
