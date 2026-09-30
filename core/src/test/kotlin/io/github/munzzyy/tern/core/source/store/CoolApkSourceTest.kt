package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
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

class CoolApkSourceTest {
    private val source = CoolApkSource()
    private val canonical = "https://www.coolapk.com/apk/org.example.app"
    private val detailUrl = "https://api2.coolapk.com/v6/apk/detail?id=org.example.app"
    private val fileUrl = "https://api2.coolapk.com/v6/apk/download?pn=org.example.app&aid=4242&vc=20001"
    private val now = 1790730000123L

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore(), nowMs = { now })

    private fun listing(http: FakeHttp): SourceListing = (source.check(source.match(canonical)!!, context(http)) as CheckResult.Listing).listing

    @Test
    fun bcryptMatchesTheReferenceHashes() {
        val vectors = listOf(
            Triple("", "\$2a\$06\$DCq7YPn5Rq63x1Lad4cll.", "\$2a\$06\$DCq7YPn5Rq63x1Lad4cll.TV4S6ytwfsfvkgY8jIucDrjc8deX1s."),
            Triple("a", "\$2a\$06\$m0CrhHm10qJ3lXRY.5zDGO", "\$2a\$06\$m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe"),
            Triple("abc", "\$2a\$06\$If6bvum7DFjUnE9p2uDeDu", "\$2a\$06\$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i"),
            Triple("abcdefghijklmnopqrstuvwxyz", "\$2a\$06\$.rCVZVOThsIa97pEDOxvGu", "\$2a\$06\$.rCVZVOThsIa97pEDOxvGuRRgzG64bvtJ0938xuqzv18d3ZpQhstC"),
            Triple("~!@#\$%^&*()      ~!@#\$%^&*()PNBFRD", "\$2a\$06\$fPIsBO8qRqkjj273rfaOI.", "\$2a\$06\$fPIsBO8qRqkjj273rfaOI.HtSV9jLDpTbZn782DC6/t7qT67P6FfO"),
            Triple("0123456789abcdef0123456789abcdef", "\$2a\$04\$MTc5MDczMDAwMA/abcdefu", "\$2a\$04\$MTc5MDczMDAwMA/abcdefubVmFYtUjLn5uGxj3pI5U2Vf/2L9CcLK"),
        )
        for ((password, salt, expected) in vectors) assertEquals(password, expected, Bcrypt.hash(password, salt))
    }

    @Test
    fun theTokenIsMadeAsCoolApksAppMakesIt() {
        val device = CoolApkToken.deviceCode(ByteArray(16) { (0x10 + it).toByte() }, byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f))
        assertEquals(
            "MTAxMTEyMTMxNDE1MTYxNzE4MTkxQTFCMUMxRDFFMUY7IDsgOyAwYToxYjoyYzozZDo0ZTo1ZjsgR29vZ2xlOyBHb29nbGU7IFBpeGVsIDVhOyBTUTFELjIyMDEwNS4wMDc=",
            device,
        )
        assertEquals(
            "v2JDJ5JDEwJE1UYzVNRGN6TURBd01BL2ZjNGQ4ZHVWeXhqOHBGMW5jY1ZRR0ZRZUFTeGh6TWhObC42czJ1",
            CoolApkToken.token(1790730000, device),
        )
    }

    @Test
    fun matchesTheAppPage() {
        for (url in listOf(canonical, "https://coolapk.com/apk/org.example.app", "http://www.coolapk.com/apk/org.example.app?from=share")) {
            val spec = source.match(url)
            assertEquals(url, canonical, spec?.url)
            assertEquals(url, "org.example.app", spec?.option(SourceOptions.PACKAGE))
        }
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://www.coolapk.com/feed/12345"))
        assertNull(source.match("https://www.coolapk.com/apk/"))
        assertNull(source.match("https://www.coolapk.com/apk/4242"))
        assertNull(source.match("https://coolapk.com.example.org/apk/org.example.app"))
        assertNull(source.match("https://example.com/apk/org.example.app"))
    }

    @Test
    fun readsTheRecordWithTheAppsHeaders() {
        val http = FakeHttp().resource(detailUrl, "store/coolapk-detail.json")
        val listing = listing(http)
        val headers = http.requestsTo(detailUrl).single().headers
        assertEquals("com.coolapk.market", headers["X-App-Id"])
        assertTrue(headers["User-Agent"]!!.endsWith("+CoolMarket/12.4.2-2208241-universal"))
        assertTrue(headers["X-App-Token"]!!.startsWith("v2"))
        assertTrue(headers["X-App-Device"]!!.isNotEmpty())
        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An app for trying things out", listing.description)
        val release = listing.releases.single()
        assertEquals("2.0.1", release.version)
        assertEquals(20001L, release.versionCode)
        assertEquals("20001", release.id)
        assertEquals(1790675588000L, release.publishedAtMs)
        assertEquals("Fixed a crash when opening the settings\r\nStarts faster", release.notes)
        assertEquals(NotesFormat.PLAIN, release.notesFormat)
        assertEquals(canonical, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("org.example.app_2.0.1.apk", asset.name)
        assertEquals(fileUrl, asset.url)
        assertEquals(5452595L, asset.size)
    }

    @Test
    fun anAppCoolApkDoesNotKnowIsNotFound() {
        val http = FakeHttp().text(detailUrl, """{"status":-2,"error":-2,"message":"not found","messageStatus":-2}""")
        assertFailure(SourceErrorKind.NOT_FOUND) { listing(http) }
    }

    @Test
    fun aRefusalIsANetworkProblem() {
        assertFailure(SourceErrorKind.NETWORK) { listing(FakeHttp().text(detailUrl, """{"status":-1,"message":"refused"}""")) }
        assertFailure(SourceErrorKind.NETWORK) { listing(FakeHttp().text(detailUrl, "<html></html>", status = 567)) }
    }

    @Test
    fun anAnswerForAnotherPackageIsRefused() {
        val json = Fixtures.text("store/coolapk-detail.json").replace("\"org.example.app\"", "\"org.example.other\"")
        assertFailure(SourceErrorKind.PARSE) { listing(FakeHttp().text(detailUrl, json)) }
    }

    @Test
    fun resolveReadsWhereTheDownloadAddressPoints() {
        val location = "https://dl.coolapk.com/down?pn=org.example.app&id=4242&v=NDI0Mg&type=apk&from=market-v12&vc=20001&nd=0&h=0123abcd"
        val http = FakeHttp().on(fileUrl) { HttpResponse.of(302, "", Headers.of("Location" to location), fileUrl) }
        val download = source.resolve(source.match(canonical)!!, Asset("org.example.app_2.0.1.apk", fileUrl), context(http))
        assertEquals(location, download.url)
        val request = http.requestsTo(fileUrl).single()
        assertFalse(request.followRedirects)
        assertTrue(request.headers["X-App-Token"]!!.startsWith("v2"))
        assertTrue(download.headers.isEmpty())
    }

    @Test
    fun resolveRefusesAFileOnAnotherHost() {
        val http = FakeHttp().on(fileUrl) { HttpResponse.of(302, "", Headers.of("Location" to "https://files.example.org/example.apk"), fileUrl) }
        assertFailure(SourceErrorKind.PARSE) { source.resolve(source.match(canonical)!!, Asset("org.example.app_2.0.1.apk", fileUrl), context(http)) }
        val plain = FakeHttp().on(fileUrl) { HttpResponse.of(302, "", Headers.of("Location" to "http://dl.coolapk.com/down?pn=org.example.app"), fileUrl) }
        assertFailure(SourceErrorKind.PARSE) { source.resolve(source.match(canonical)!!, Asset("org.example.app_2.0.1.apk", fileUrl), context(plain)) }
    }

    @Test
    fun resolveOnlyAsksTheStoresOwnAddress() {
        val http = FakeHttp()
        val foreign = Asset("example.apk", "https://api2.coolapk.com/v6/apk/download?pn=org.example.other&aid=4242")
        assertFailure(SourceErrorKind.PARSE) { source.resolve(source.match(canonical)!!, foreign, context(http)) }
        assertEquals(emptyList<String>(), http.requests.map { it.url })
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
