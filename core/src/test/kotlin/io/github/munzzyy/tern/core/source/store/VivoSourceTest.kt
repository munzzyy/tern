package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
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
import org.junit.Assert.fail
import org.junit.Test

class VivoSourceTest {
    private val source = VivoSource()
    private val canonical = "https://detail-browser.vivo.com.cn/v115/index.html?appId=12345"
    private val detailUrl = "https://h5-api.appstore.vivo.com.cn/detailInfo?appId=12345"
    private val fileUrl = "https://appstore.vivo.com.cn/appinfo/downloadApkFile?id=12345"

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore())

    private fun listing(http: FakeHttp): SourceListing = (source.check(source.match(canonical)!!, context(http)) as CheckResult.Listing).listing

    @Test
    fun matchesTheStoreAddressesThatNameAnApp() {
        for (url in listOf(
            canonical,
            "https://h5.appstore.vivo.com.cn/#/details?appId=12345",
            "https://h5coml.vivo.com.cn/h5coml/appdetail_h5/browser_v2/index.html?appId=12345&from=share",
            "http://detail-browser.vivo.com.cn/v115/index.html?appId=12345",
        )) {
            val spec = source.match(url)
            assertEquals(url, canonical, spec?.url)
            assertNull(url, spec?.option(SourceOptions.PACKAGE))
        }
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://h5.appstore.vivo.com.cn/#/details"))
        assertNull(source.match("https://detail-browser.vivo.com.cn/v115/index.html?appId=abc"))
        assertNull(source.match("https://www.vivo.com.cn/index.html?appId=12345"))
        assertNull(source.match("https://example.com/v115/index.html?appId=12345"))
    }

    @Test
    fun readsTheDetailApi() {
        val listing = listing(FakeHttp().resource(detailUrl, "store/vivo-detail.json"))
        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An app for trying things out", listing.description)
        val release = listing.releases.single()
        assertEquals("3.1.0", release.version)
        assertEquals(310L, release.versionCode)
        assertEquals("310", release.id)
        assertEquals(1790577242000L, release.publishedAtMs)
        assertEquals(canonical, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("org.example.app_310.apk", asset.name)
        assertEquals(fileUrl, asset.url)
    }

    @Test
    fun anAppVivoDoesNotKnowIsNotFound() {
        val http = FakeHttp().resource(detailUrl, "store/vivo-missing.json")
        assertFailure(SourceErrorKind.NOT_FOUND) { listing(http) }
    }

    @Test
    fun aFailingStoreIsANetworkProblem() {
        assertFailure(SourceErrorKind.NETWORK) { listing(FakeHttp().text(detailUrl, "", status = 502)) }
    }

    @Test
    fun anAnswerForAnotherAppIsRefused() {
        val json = Fixtures.text("store/vivo-detail.json").replace("\"id\": 12345", "\"id\": 54321")
        assertFailure(SourceErrorKind.PARSE) { listing(FakeHttp().text(detailUrl, json)) }
    }

    @Test
    fun resolveFollowsTheRedirectAndAsksForHttps() {
        val http = FakeHttp().on(fileUrl) {
            HttpResponse.of(302, "", Headers.of("Location" to "http://apkwificdn2-v6dl.vivo.com.cn/appstore/developer/apk/20260928/example.apk"), fileUrl)
        }
        val download = source.resolve(source.match(canonical)!!, Asset("org.example.app_310.apk", fileUrl), context(http))
        assertEquals("https://apkwificdn2-v6dl.vivo.com.cn/appstore/developer/apk/20260928/example.apk", download.url)
        assertFalse(http.requestsTo(fileUrl).single().followRedirects)
    }

    @Test
    fun resolveRefusesAFileOnAnotherHost() {
        val http = FakeHttp().on(fileUrl) {
            HttpResponse.of(302, "", Headers.of("Location" to "http://files.example.org/example.apk"), fileUrl)
        }
        assertFailure(SourceErrorKind.PARSE) { source.resolve(source.match(canonical)!!, Asset("org.example.app_310.apk", fileUrl), context(http)) }
    }

    @Test
    fun resolveOnlyFollowsTheStoresOwnAddress() {
        val foreign = Asset("example.apk", "https://files.example.org/appinfo/downloadApkFile?id=12345")
        val http = FakeHttp()
        assertFailure(SourceErrorKind.PARSE) { source.resolve(source.match(canonical)!!, foreign, context(http)) }
        assertEquals(emptyList<String>(), http.requests.map { it.url })
    }

    @Test
    fun searchListsTheAppsFound() {
        val url = "https://h5-api.appstore.vivo.com.cn/h5appstore/search/result-list?app_version=2100&page_index=1" +
            "&apps_per_page=20&target=local&cfrom=2&key=example%20app"
        val hits = source.search("example app", context(FakeHttp().resource(url, "store/vivo-search.json")))
        assertEquals(listOf("Example App", "Example Notes"), hits.map { it.name })
        assertEquals("Example Labs", hits[0].owner)
        assertEquals("An app for trying things out", hits[0].description)
        assertEquals(canonical, hits[0].url)
        assertEquals(canonical, source.match(hits[0].url)?.url)
    }

    @Test
    fun aSearchWithoutResultsFindsNothing() {
        val url = "https://h5-api.appstore.vivo.com.cn/h5appstore/search/result-list?app_version=2100&page_index=1" +
            "&apps_per_page=20&target=local&cfrom=2&key=nothing"
        val empty = """{"code":0,"data":{"appSearchResponse":{"result":false,"value":[]}},"success":true}"""
        assertEquals(emptyList<Any>(), source.search("nothing", context(FakeHttp().text(url, empty))))
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
