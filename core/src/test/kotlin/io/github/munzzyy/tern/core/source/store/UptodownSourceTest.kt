package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
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

class UptodownSourceTest {
    private val source = UptodownSource { "0123456789abcdef" }
    private val page = "https://example-app.en.uptodown.com/android/download"
    private val filePage = "$page/7654321-x"
    private val auth = "https://www.uptodown.app/eapi/auth/token?identifier=0123456789abcdef"
    private val downloadUrl = "https://www.uptodown.app/eapi/apps/1234567/file/7654321/downloadUrl"
    private val search = "https://en.uptodown.com/android/en/s"
    private val file = "https://dw.uptodown.net/dwn/0a1b2c3d/example-app-2-1-0.xapk"

    /** A session token whose claims say it runs until [expires], in seconds. */
    private fun token(expires: Long): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"typ":"JWT","alg":"HS256"}""".toByteArray())
        val claims = encoder.encodeToString("""{"iat":${expires - 1800},"exp":$expires}""".toByteArray())
        return "$header.$claims.c2lnbmF0dXJl"
    }

    private val now = 1_790_730_000_000L

    private fun context(http: FakeHttp, nowMs: Long = now) = CheckContext(http, InMemoryValidatorStore(), nowMs = { nowMs })

    private fun spec() = source.match(page)!!

    private fun check(http: FakeHttp): SourceListing = (source.check(spec(), context(http)) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp): SourceErrorKind = try {
        source.check(spec(), context(http))
        fail("expected SourceException")
        error("unreachable")
    } catch (e: SourceException) {
        e.kind
    }

    private fun session(http: FakeHttp, expires: Long = 1_790_731_800L): FakeHttp = http.text(auth, """{"token":"${token(expires)}"}""")

    private fun asset() = Asset("org.example.app.xapk", filePage)

    @Test
    fun matchesAppPagesInAnyLanguageAndReadsTheEnglishDownloadPage() {
        assertEquals(SourceSpec(SourceTypes.UPTODOWN, page), source.match("https://example-app.en.uptodown.com/android"))
        assertEquals(page, source.match("https://example-app.br.uptodown.com/android/download")?.url)
        assertEquals(page, source.match("https://example-app.uptodown.com/android/descargar/")?.url)
        assertEquals(page, source.match("http://example-app.en.uptodown.com")?.url)
        assertEquals(page, source.match("$page/7654321-x")?.url)
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://en.uptodown.com/android"))
        assertNull(source.match("https://www.uptodown.com/"))
        assertNull(source.match("https://uptodown.com/app/fourteen"))
        assertNull(source.match("https://dw.uptodown.com/dwn/0a1b2c3d"))
        assertNull(source.match("https://a.b.c.uptodown.com/android"))
        assertNull(source.match("https://example-app.en.uptodown.com.example.org/android"))
        assertNull(source.match("https://example.org/android"))
    }

    @Test
    fun readsTheDownloadPage() {
        val http = FakeHttp().resource(page, "store/uptodown_download.html")
        val listing = check(http)

        assertEquals("Example App", listing.name)
        assertEquals("Example Labs & Friends", listing.author)
        assertEquals("org.example.app", listing.packageName)
        val release = listing.releases.single()
        assertEquals("7654321", release.id)
        assertEquals("2.1.0", release.version)
        assertNull(release.versionCode)
        assertEquals(Instant.parse("2026-09-25T00:00:00Z").toEpochMilli(), release.publishedAtMs)
        assertEquals(page, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("org.example.app.xapk", asset.name)
        assertEquals(filePage, asset.url)
        assertEquals(AssetKind.BUNDLE, asset.kind)
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9", asset.sha256)
        assertNull(asset.size)
        assertEquals(listOf(page), http.requests.map { it.url })
    }

    @Test
    fun detailsInBlocksInsideTheSameElementAreAllRead() {
        val html = Fixtures.text("store/uptodown_download.html")
            .replace("<section class=\"info\" id=\"technical-information\">", "<div class=\"info\" id=\"technical-information\">")
            .replace("</div>\n</section>\n<section id=\"versions\">", "</div>\n</div>\n<section id=\"versions\">")
        val listing = check(FakeHttp().text(page, html))
        assertEquals("org.example.app", listing.packageName)
        assertEquals("org.example.app.xapk", listing.releases.single().assets.single().name)
    }

    @Test
    fun rowsWithoutALabelAreReadByTheirPlace() {
        val html = """
            <h1 id="detail-app-name" data-code="1234567" data-file-id="7654321">Example App</h1>
            <div class="version">2.1.0</div>
            <div id="technical-information"><table>
            <tr><th></th><td>Free</td></tr><tr><th></th><td>Android</td></tr>
            <tr><th></th><td>September 25, 2026</td></tr><tr><th></th><td>APK</td></tr>
            <tr><th></th><td>50.12 MB</td></tr><tr><th></th><td>English</td></tr>
            <tr><th></th><td>org.example.app</td></tr>
            </table></div>
        """.trimIndent()
        val release = check(FakeHttp().text(page, html)).releases.single()
        assertEquals("org.example.app.apk", release.assets.single().name)
        assertEquals(Instant.parse("2026-09-25T00:00:00Z").toEpochMilli(), release.publishedAtMs)
    }

    @Test
    fun aPageWithoutAVersionOrAFileHasNoRelease() {
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, """<h1 id="detail-app-name" data-file-id="7654321">Example App</h1>""")))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, """<div class="version">2.1.0</div>""")))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, """<div class="version">2.1.0</div><button id="detail-download-button" data-file-id="../x">""")))
    }

    @Test
    fun aMissingPageIsNotFoundAndOtherFailuresAreReported() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "<html><body>404 Not Found</body></html>", status = 404)))
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "", status = 410)))
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(page, "", status = 503)))
    }

    @Test
    fun aDownloadIsResolvedThroughAnAnonymousSession() {
        val http = session(FakeHttp().resource(filePage, "store/uptodown_download.html"))
            .text(downloadUrl, """{"success":1,"data":{"downloadURL":"$file"}}""")

        val download = SourceRegistry(listOf(source)).resolve(spec(), asset(), context(http))
        assertEquals(Download(file, mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 16; Pixel 8 Pro Build/BP4A.260205.001)")), download)

        val opened = http.requestsTo(auth).single()
        assertEquals("POST", opened.method)
        assertEquals("application/x-www-form-urlencoded", opened.headers["Content-Type"])
        assertEquals("Uptodown_Android", opened.headers["Identificador"])
        assertEquals("739", opened.headers["Identificador-Version"])
        assertEquals(
            "identifier=0123456789abcdef&id_plataforma=13&lang=en&unixtime=1790730000" +
                "&hmac=09e69ce82645d4dda65f30b4fb39462f864159e14e280c55df2b016db465dc53",
            String(opened.body!!, Charsets.UTF_8),
        )
        val asked = http.requestsTo(downloadUrl).single()
        assertEquals("Bearer ${token(1_790_731_800L)}", asked.authorization)
        assertTrue(asked.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
        assertEquals("Uptodown_Android", asked.headers["Identificador"])
    }

    @Test
    fun theSessionIsKeptUntilItIsAboutToRunOut() {
        val http = session(FakeHttp().resource(filePage, "store/uptodown_download.html"))
            .text(downloadUrl, """{"success":1,"data":{"downloadURL":"$file"}}""")
        source.resolve(spec(), asset(), context(http))
        source.resolve(spec(), asset(), context(http, nowMs = now + 1_000_000L))
        assertEquals(1, http.requestsTo(auth).size)
        source.resolve(spec(), asset(), context(http, nowMs = now + 1_750_000L))
        assertEquals(2, http.requestsTo(auth).size)
    }

    @Test
    fun aRefusedSessionIsRenewedOnce() {
        var asked = 0
        val http = session(FakeHttp().resource(filePage, "store/uptodown_download.html"))
            .on(downloadUrl) {
                asked++
                if (asked == 1) HttpResponse.of(401, "", url = downloadUrl) else HttpResponse.of(200, """{"success":1,"data":{"downloadURL":"$file"}}""", url = downloadUrl)
            }
        assertEquals(file, source.resolve(spec(), asset(), context(http)).url)
        assertEquals(2, http.requestsTo(auth).size)

        val refused = session(FakeHttp().resource(filePage, "store/uptodown_download.html")).text(downloadUrl, "", status = 401)
        try {
            UptodownSource { "0123456789abcdef" }.resolve(spec(), asset(), context(refused))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.AUTH, e.kind)
        }
        assertEquals(2, refused.requestsTo(downloadUrl).size)
    }

    @Test
    fun aFileOnAnotherHostIsRefused() {
        val http = session(FakeHttp().resource(filePage, "store/uptodown_download.html"))
            .text(downloadUrl, """{"success":1,"data":{"downloadURL":"https://files.example.org/example-app.xapk"}}""")
        try {
            source.resolve(spec(), asset(), context(http))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun onlyAFileOfThisAppIsResolved() {
        val http = FakeHttp()
        for (address in listOf("https://files.example.org/7654321-x", "$page/../../7654321-x", "https://other-app.en.uptodown.com/android/download/7654321-x")) {
            try {
                source.resolve(spec(), Asset("org.example.app.apk", address), context(http))
                fail("resolved $address")
            } catch (e: SourceException) {
                assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
            }
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun aSessionThatCannotBeReadIsRefused() {
        val http = FakeHttp().resource(filePage, "store/uptodown_download.html").text(auth, """{"token":"not-a-token"}""")
        try {
            source.resolve(spec(), asset(), context(http))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun searchKeepsAndroidAppsTheSourceAccepts() {
        val http = FakeHttp().resource(search, "store/uptodown_search.json")
        val hits = source.search("example app", context(http))

        assertEquals(
            listOf(
                Hit("Example App", "Example Labs", null, page),
                Hit("Example Tools", null, null, "https://example-tools.en.uptodown.com/android/download"),
            ),
            hits,
        )
        val request: HttpRequest = http.requestsTo(search).single()
        assertEquals("POST", request.method)
        assertEquals("queryString=example%20app", String(request.body!!, Charsets.UTF_8))
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("Uptodown", source.origin)
    }

    @Test
    fun aFailedSearchIsReported() {
        for (http in listOf(FakeHttp().text(search, "", status = 500), FakeHttp().text(search, """{"success":0}"""))) {
            try {
                source.search("example", context(http))
                fail("expected SourceException")
            } catch (e: SourceException) {
                assertEquals(SourceErrorKind.NETWORK, e.kind)
            }
        }
    }
}
