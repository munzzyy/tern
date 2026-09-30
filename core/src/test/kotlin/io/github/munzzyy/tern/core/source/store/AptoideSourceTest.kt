package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class AptoideSourceTest {
    private val source = AptoideSource()
    private val page = "https://example-app.en.aptoide.com/app"
    private val api = "https://ws2.aptoide.com/api/7/getApp/app_id/12345678"

    private fun spec() = SourceSpec(SourceTypes.APTOIDE, page)

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore())

    private fun check(http: FakeHttp): SourceListing = (source.check(spec(), context(http)) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp): SourceErrorKind = try {
        source.check(spec(), context(http))
        fail("expected SourceException")
        error("unreachable")
    } catch (e: SourceException) {
        e.kind
    }

    private fun withFile(path: String, alternative: String): String =
        Fixtures.text("store/aptoide_app.json")
            .replace(
                "\"path\": \"https://pool.apk.aptoide.com/apps/example-app-210-12345678-0123456789abcdef0123456789abcdef.apk\"",
                "\"path\": \"$path\"",
            )
            .replace(
                "\"path_alt\": \"https://pool.apk.aptoide.com/apps/example-app-210-12345678-0123456789abcdef0123456789abcdef.apk\"",
                "\"path_alt\": \"$alternative\"",
            )

    @Test
    fun matchesAnAppPageInAnyLanguage() {
        assertEquals(SourceSpec(SourceTypes.APTOIDE, page), source.match(page))
        assertEquals(page, source.match("https://example-app.fr.aptoide.com/versions")?.url)
        assertEquals(page, source.match("http://example-app.br.aptoide.com/?store_name=apps&app_id=12345678")?.url)
        assertEquals(page, source.match("example-app.en.aptoide.com")?.url)
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://en.aptoide.com/"))
        assertNull(source.match("https://www.aptoide.com/"))
        assertNull(source.match("https://pool.apk.aptoide.com/apps/example-app-210.apk"))
        assertNull(source.match("https://apps.store.aptoide.com/app/market/org.example.app/210/12345678/example-app"))
        assertNull(source.match("https://example-app.en.aptoide.com.example.org/app"))
        assertNull(source.match("https://example.org/app"))
    }

    @Test
    fun readsTheVersionTheStoreOffers() {
        val http = FakeHttp().resource(page, "store/aptoide_page.html").resource(api, "store/aptoide_app.json")
        val listing = check(http)

        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An invented app for tests.", listing.description)
        val release = listing.releases.single()
        assertEquals("210", release.id)
        assertEquals("2.1.0", release.version)
        assertEquals(210L, release.versionCode)
        assertEquals("- Faster start.\n- Fewer crashes.", release.notes)
        assertEquals(NotesFormat.PLAIN, release.notesFormat)
        assertEquals(Instant.parse("2026-08-21T02:54:31Z").toEpochMilli(), release.publishedAtMs)
        assertEquals(page, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("example-app-210-12345678-0123456789abcdef0123456789abcdef.apk", asset.name)
        assertEquals("https://pool.apk.aptoide.com/apps/example-app-210-12345678-0123456789abcdef0123456789abcdef.apk", asset.url)
        assertEquals(7340032L, asset.size)
        assertNull(asset.sha256)
        assertEquals(listOf(page, api), http.requests.map { it.url })
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        val alternative = "https://pool.apk.aptoide.com/apps/example-app-210-alt.apk"
        val http = FakeHttp().resource(page, "store/aptoide_page.html")
            .text(api, withFile("https://files.example.org/example-app-210.apk", alternative))
        assertEquals(alternative, check(http).releases.single().assets.single().url)

        val nowhere = FakeHttp().resource(page, "store/aptoide_page.html")
            .text(api, withFile("https://files.example.org/example-app-210.apk", "http://pool.apk.aptoide.com/apps/example-app-210.apk"))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(nowhere))
    }

    @Test
    fun aMissingAppIsNotFound() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "", status = 404)))
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "<html><body>Top apps</body></html>")))
        val gone = FakeHttp().resource(page, "store/aptoide_page.html")
            .text(api, """{"info":{"status":"FAIL"},"errors":[{"code":"APP-1"}]}""", status = 404)
        assertEquals(SourceErrorKind.NOT_FOUND, failure(gone))
        val empty = FakeHttp().resource(page, "store/aptoide_page.html").text(api, """{"info":{"status":"OK"},"nodes":{}}""")
        assertEquals(SourceErrorKind.NOT_FOUND, failure(empty))
    }

    @Test
    fun failuresOfTheStoreAreReported() {
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(page, "", status = 503)))
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().resource(page, "store/aptoide_page.html").text(api, "", status = 500)))
        assertEquals(SourceErrorKind.PARSE, failure(FakeHttp().resource(page, "store/aptoide_page.html").text(api, "{nodes")))
    }

    @Test
    fun searchNamesPagesTheSourceAccepts() {
        val searchUrl = "https://ws2.aptoide.com/api/7/apps/search?query=example%20app&limit=20"
        val http = FakeHttp().resource(searchUrl, "store/aptoide_search.json")
        val hits = source.search(" example app ", context(http))

        assertEquals(
            listOf(
                Hit("Example App", "Example Labs", null, "https://example-app.en.aptoide.com/app"),
                Hit("Example Tools", null, null, "https://example-tools.en.aptoide.com/app"),
            ),
            hits,
        )
        hits.forEach { assertEquals(it.url, source.match(it.url)?.url) }
        assertEquals("Aptoide", source.origin)
    }

    @Test
    fun aFailedSearchIsReported() {
        val searchUrl = "https://ws2.aptoide.com/api/7/apps/search?query=example&limit=20"
        try {
            source.search("example", context(FakeHttp().text(searchUrl, "", status = 500)))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NETWORK, e.kind)
        }
    }
}
