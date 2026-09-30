package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.NotesFormat
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

class RuStoreSourceTest {
    private val canonical = "https://www.rustore.ru/catalog/app/org.example.app"
    private val nonceUrl = "https://api.rustore.ru/v1/secure/nonce"
    private val infoUrl = "https://backapi.rustore.ru/applicationData/overallInfo/org.example.app"
    private val linkUrl = "https://backapi.rustore.ru/v3/showcase/apps/download-link"

    /** The nonce the fake store hands out and the signature RuStore's key makes of it. */
    private val nonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val signature = "3jbz8yY9IApY/AYDk2YYucg5FcCMEZu9HOGLhiOSmqA="

    private fun store(
        info: String = Fixtures.text("store/rustore-info.json"),
        link: String = Fixtures.text("store/rustore-download-link.json"),
        nonces: MutableList<String> = mutableListOf(),
    ): FakeHttp = FakeHttp()
        .on(nonceUrl) { HttpResponse.of(200, """{"nonce":"${nonces.removeFirstOrNull() ?: nonce}"}""", url = nonceUrl) }
        .on(infoUrl) { request -> if (request.headers["X-Client-Signature"] == signature) HttpResponse.of(200, info, url = infoUrl) else HttpResponse.of(419, "", url = infoUrl) }
        .on(linkUrl) { request -> if (request.headers["X-Client-Signature"] == signature) HttpResponse.of(200, link, url = linkUrl) else HttpResponse.of(419, "", url = linkUrl) }

    private fun context(http: FakeHttp, device: DeviceProfile? = DeviceProfile.ARM64_PHONE) = CheckContext(http, InMemoryValidatorStore(), device = device)

    private fun listing(http: FakeHttp, source: RuStoreSource = RuStoreSource(), device: DeviceProfile? = DeviceProfile.ARM64_PHONE): SourceListing =
        (source.check(source.match(canonical)!!, context(http, device)) as CheckResult.Listing).listing

    @Test
    fun matchesTheAppPage() {
        val source = RuStoreSource()
        for (url in listOf(canonical, "https://rustore.ru/catalog/app/org.example.app", "http://www.rustore.ru/catalog/app//org.example.app?utm_source=share")) {
            val spec = source.match(url)
            assertEquals(url, canonical, spec?.url)
            assertEquals(url, "org.example.app", spec?.option(SourceOptions.PACKAGE))
        }
    }

    @Test
    fun leavesOtherAddressesAlone() {
        val source = RuStoreSource()
        assertNull(source.match("https://www.rustore.ru/catalog/app/"))
        assertNull(source.match("https://www.rustore.ru/catalog/org.example.app"))
        assertNull(source.match("https://www.rustore.ru/help/app/org.example.app"))
        assertNull(source.match("https://www.rustore.ru/catalog/app/not-a-package"))
        assertNull(source.match("https://example.com/catalog/app/org.example.app"))
    }

    @Test
    fun readsTheAppWithASignedSession() {
        val http = store()
        val listing = listing(http)
        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An app for trying things out", listing.description)
        val release = listing.releases.single()
        assertEquals("26.34.0", release.version)
        assertEquals(6850L, release.versionCode)
        assertEquals("6850", release.id)
        assertEquals(1790175857000L, release.publishedAtMs)
        assertEquals("Fixed a crash when opening the settings\nStarts faster", release.notes)
        assertEquals(NotesFormat.PLAIN, release.notesFormat)
        assertEquals(canonical, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("org.example.app_26.34.0.apk", asset.name)
        // The APK beside the .zip, on the host whose certificate Android trusts.
        assertEquals("https://static.rustore.ru/2026/9/29/2d/185fd5c7-0000-4000-8000-000000000001.apk", asset.url)
        assertNull(asset.size)
    }

    @Test
    fun asksAsRuStoresAppDoes() {
        val http = store()
        listing(http)
        val nonceRequest = http.requestsTo(nonceUrl).single()
        assertEquals("POST", nonceRequest.method)
        assertEquals(0, nonceRequest.body?.size)
        val deviceId = nonceRequest.headers["deviceId"]!!
        assertTrue(deviceId, deviceId.matches(Regex("[0-9a-f]{16}--1057450190")))
        assertNull(nonceRequest.headers["X-Client-Signature"])
        val info = http.requestsTo(infoUrl).single()
        assertEquals(signature, info.headers["X-Client-Signature"])
        assertEquals(deviceId, info.headers["deviceId"])
        assertEquals("mobile", info.headers["deviceType"])
        assertTrue(info.headers["User-Agent"]!!.startsWith("RuStore/"))
        val link = http.requestsTo(linkUrl).single()
        assertEquals("POST", link.method)
        assertFalse(link.followRedirects)
        assertEquals("application/json; charset=utf-8", link.headers["Content-Type"])
        val body = Json.parseObject(String(link.body!!, Charsets.UTF_8))
        assertEquals(2063000001L, body.long("appId"))
        assertEquals(true, body.bool("firstInstall"))
        assertEquals(false, body.bool("withoutSplits"))
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), body.array("supportedAbis")?.strings())
        assertEquals(36L, body.long("sdkVersion"))
        assertEquals(420L, body.long("screenDensity"))
    }

    @Test
    fun aTelevisionSaysSo() {
        val http = store()
        listing(http, device = DeviceProfile(listOf("armeabi-v7a"), sdk = 30, densityDpi = 320, television = true))
        assertEquals("TV", http.requestsTo(infoUrl).single().headers["deviceType"])
        assertEquals(30L, Json.parseObject(String(http.requestsTo(linkUrl).single().body!!, Charsets.UTF_8)).long("sdkVersion"))
    }

    @Test
    fun keepsTheSession() {
        val source = RuStoreSource()
        val http = store()
        listing(http, source)
        listing(http, source)
        assertEquals(1, http.requestsTo(nonceUrl).size)
    }

    @Test
    fun aRefusedSessionIsMadeAgain() {
        val http = store(nonces = mutableListOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertEquals("26.34.0", listing(http).releases.single().version)
        assertEquals(2, http.requestsTo(nonceUrl).size)
        assertEquals(listOf(419, 200), http.requestsTo(infoUrl).map { if (it.headers["X-Client-Signature"] == signature) 200 else 419 })
    }

    @Test
    fun aStoreThatKeepsRefusingIsANetworkProblem() {
        val bad = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertFailure(SourceErrorKind.NETWORK) { listing(store(nonces = mutableListOf(bad, bad))) }
    }

    @Test
    fun aBaseAndItsSplitsAreListedAsOneBundle() {
        val link = """{"appId":2063000001,"downloadUrls":[
            {"url":"https://static-m.rustore.ru/2026/9/16/ab/base.zip","size":3},
            {"url":"https://static-m.rustore.ru/2026/9/16/ab/config.arm64_v8a.zip","size":2},
            {"url":"https://static-m.rustore.ru/2026/9/16/ab/config.xxhdpi.zip","size":1}]}"""
        val asset = listing(store(link = link)).releases.single().assets.single()
        assertEquals("org.example.app_26.34.0.apks", asset.name)
        assertEquals(AssetKind.BUNDLE, asset.kind)
        // Each file as RuStore names it, a .zip that holds the APK, on the host whose certificate Android trusts.
        assertEquals("https://static.rustore.ru/2026/9/16/ab/base.zip", asset.url)
        assertEquals(
            listOf("https://static.rustore.ru/2026/9/16/ab/config.arm64_v8a.zip", "https://static.rustore.ru/2026/9/16/ab/config.xxhdpi.zip"),
            asset.parts,
        )
        assertEquals(6L, asset.size)
    }

    @Test
    fun aBundleWithAFileOfUnknownSizeHasNoSize() {
        val link = """{"appId":2063000001,"downloadUrls":[
            {"url":"https://static.rustore.ru/2026/9/16/ab/base.zip","size":3},
            {"url":"https://static.rustore.ru/2026/9/16/ab/config.xxhdpi.zip"}]}"""
        val asset = listing(store(link = link)).releases.single().assets.single()
        assertEquals(1, asset.parts.size)
        assertNull(asset.size)
    }

    @Test
    fun aFileNamedTwiceIsFetchedOnce() {
        val link = """{"appId":2063000001,"downloadUrls":[
            {"url":"https://static.rustore.ru/2026/9/16/ab/base.zip","size":3},
            {"url":"https://static.rustore.ru/2026/9/16/ab/base.zip","size":3}]}"""
        val asset = listing(store(link = link)).releases.single().assets.single()
        // One file left is an app that is not split.
        assertEquals("https://static.rustore.ru/2026/9/16/ab/base.apk", asset.url)
        assertTrue(asset.parts.isEmpty())
    }

    @Test
    fun aBundleWithASplitOnAnotherHostIsLeftOut() {
        val link = """{"appId":2063000001,"downloadUrls":[
            {"url":"https://static.rustore.ru/2026/9/16/ab/base.zip","size":3},
            {"url":"https://files.example.org/config.xxhdpi.zip","size":1}]}"""
        assertTrue(listing(store(link = link)).releases.single().assets.isEmpty())
    }

    @Test
    fun anAppWithoutAFileHereIsStillListed() {
        val release = listing(store(link = """{"appId":2063000001,"downloadUrls":[]}""")).releases.single()
        assertEquals("26.34.0", release.version)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        for (address in listOf("https://files.example.org/base.zip", "http://static.rustore.ru/2026/base.zip", "https://rustore.ru.example.org/base.zip")) {
            val link = """{"appId":2063000001,"downloadUrls":[{"url":"$address","size":3}]}"""
            assertTrue(address, listing(store(link = link)).releases.single().assets.isEmpty())
        }
    }

    @Test
    fun anAppRuStoreDoesNotKnowIsNotFound() {
        val http = FakeHttp()
            .on(nonceUrl) { HttpResponse.of(200, """{"nonce":"$nonce"}""", url = nonceUrl) }
            .on(infoUrl) { HttpResponse.of(404, """{"code":"ERROR","message":"Couldn't find required data","body":null}""", url = infoUrl) }
        assertFailure(SourceErrorKind.NOT_FOUND) { listing(http) }
    }

    @Test
    fun anAnswerForAnotherPackageIsRefused() {
        val info = Fixtures.text("store/rustore-info.json").replace("\"packageName\": \"org.example.app\"", "\"packageName\": \"org.example.other\"")
        assertFailure(SourceErrorKind.PARSE) { listing(store(info = info)) }
    }

    @Test
    fun searchListsTheAppsFound() {
        val url = "https://backapi.rustore.ru/applicationData/apps?query=example%20app&pageNumber=0&pageSize=20"
        val http = FakeHttp().resource(url, "store/rustore-search.json")
        val source = RuStoreSource()
        val hits = source.search("example app", context(http))
        assertEquals(listOf("Example App", "Example Notes"), hits.map { it.name })
        assertEquals(canonical, hits[0].url)
        assertEquals("An app for trying things out", hits[0].description)
        assertEquals(canonical, source.match(hits[0].url)?.url)
        val request = http.requestsTo(url).single()
        assertTrue(request.headers["deviceId"]!!.isNotEmpty())
        assertNull(request.headers["X-Client-Signature"])
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
