package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HuaweiSourceTest {
    private val canonical = "https://appgallery.huawei.com/app/C100000001"
    private val europe = "https://store-dre.hispace.dbankcloud.com/hwmarket/api/clientApi"
    private var now = 1790730000000L

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore(), nowMs = { now })

    private fun form(request: HttpRequest): List<Pair<String, String>> =
        String(request.body!!, Charsets.UTF_8).split('&').map { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun method(request: HttpRequest) = form(request).toMap()["method"]

    /** A store whose handshakes hand out sign-1, sign-2 and so on, and that answers [answer] to every other call. */
    private class Store(val zone: String = "DE", val answer: (method: String?, sign: String?) -> String) {
        var handshakes = 0
    }

    private fun serve(http: FakeHttp, url: String, store: Store): FakeHttp = http.on(url) { request ->
        val fields = form(request).toMap()
        val body = when (fields["method"]) {
            "client.front2" -> {
                store.handshakes++
                """{"rtnCode":0,"serviceZone":"${store.zone}","sign":"sign-${store.handshakes}","tabInfo":[]}"""
            }
            else -> store.answer(fields["method"], fields["sign"])
        }
        HttpResponse.of(200, body, url = url)
    }

    private fun detailStore(zone: String = "DE") = Store(zone) { method, _ ->
        if (method == "client.appDetailById") Fixtures.text("store/huawei-detail.json") else """{"rtnCode":1}"""
    }

    private fun listing(source: HuaweiSource, http: FakeHttp): SourceListing =
        (source.check(source.match(canonical)!!, context(http)) as CheckResult.Listing).listing

    @Test
    fun matchesTheStoreAddresses() {
        val source = HuaweiSource()
        for (url in listOf(
            canonical,
            "https://appgallery.huawei.com/#/app/C100000001",
            "https://appgallery.cloud.huawei.com/appdl/C100000001",
            "https://appgallery.huawei.com/app/C100000001?sharePrepath=ag&locale=en_US&source=appshare",
            "https://www.appgallery.huawei.com/app/c100000001",
        )) {
            assertEquals(url, canonical, source.match(url)?.url)
        }
        assertEquals("https://appgallery.huawei.ru/app/C100000001", source.match("https://appgallery.huawei.ru/app/C100000001")?.url)
    }

    @Test
    fun leavesOtherAddressesAlone() {
        val source = HuaweiSource()
        assertNull(source.match("https://appgallery.huawei.com/"))
        assertNull(source.match("https://appgallery.huawei.com/app/"))
        assertNull(source.match("https://appgallery.huawei.com/app/org.example.app"))
        assertNull(source.match("https://appgallery.huawei.com/search/C100000001"))
        assertNull(source.match("https://example.com/app/C100000001"))
    }

    @Test
    fun readsTheAppAfterAHandshake() {
        val source = HuaweiSource()
        val http = serve(FakeHttp(), europe, detailStore())
        val listing = listing(source, http)
        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An app for trying things out", listing.description)
        val release = listing.releases.single()
        assertEquals("8.4.0", release.version)
        assertEquals(20260926L, release.versionCode)
        assertEquals("20260926", release.id)
        assertEquals(1790380800000L, release.publishedAtMs)
        assertEquals(canonical, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("org.example.app.apk", asset.name)
        assertEquals(
            "https://appdlc-dre.hispace.dbankcloud.com/dl/appdl/application/apk/64/0123456789abcdef0123456789abcdef/org.example.app.2609261043.apk?maple=0&trackId=0&distOpEntity=HWSW",
            asset.url,
        )
        assertEquals(4827848L, asset.size)
        assertEquals("dbef1f8845a8b0588cf67ff41f6fcdf188bd6744c64cecca93a75b25dd04b228", asset.sha256)
    }

    @Test
    fun sendsASortedFormAsTern() {
        val http = serve(FakeHttp(), europe, detailStore())
        listing(HuaweiSource(), http)
        val detail = http.requests.single { method(it) == "client.appDetailById" }
        assertEquals("POST", detail.method)
        assertEquals("application/x-www-form-urlencoded", detail.headers["Content-Type"])
        assertEquals(setOf("Content-Type", "Accept"), detail.headers.keys)
        val fields = form(detail)
        assertEquals(fields.map { it.first }.sorted(), fields.map { it.first })
        val values = fields.toMap()
        assertEquals("C100000001", values["id"])
        assertEquals("sign-1", values["sign"])
        assertEquals(now.toString(), values["ts"])
        assertTrue(values["deviceId"]!!.matches(Regex("[0-9a-f]{64}")))
        assertEquals(values["deviceId"], form(http.requests.first()).toMap()["deviceId"])
    }

    @Test
    fun saysNothingAboutTheDeviceAndDoesNotPassForTheAppGalleryApp() {
        val http = serve(FakeHttp(), europe, detailStore())
        listing(HuaweiSource(), http)
        val allowed = setOf("ver", "locale", "serviceType", "ts", "deviceId", "deviceIdType", "method", "needServiceZone", "sign", "id")
        for (request in http.requests) {
            val fields = form(request).toMap()
            assertEquals(emptySet<String>(), fields.keys - allowed)
            assertTrue(request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) })
        }
    }

    @Test
    fun keepsTheHandshakeForADay() {
        val source = HuaweiSource()
        val store = detailStore()
        val http = serve(FakeHttp(), europe, store)
        listing(source, http)
        listing(source, http)
        assertEquals(1, store.handshakes)
        now += 25 * 60 * 60 * 1000L
        listing(source, http)
        assertEquals(2, store.handshakes)
    }

    @Test
    fun theZonePicksTheHost() {
        val cases = mapOf(
            "CN" to "https://store-drcn.hispace.dbankcloud.com/hwmarket/api/clientApi",
            "IN" to "https://store-dra.hispace.dbankcloud.com/hwmarket/api/clientApi",
            "RU" to "https://store-drru.hispace.dbankcloud.ru/hwmarket/api/clientApi",
        )
        for ((zone, host) in cases) {
            val store = detailStore(zone)
            val http = serve(serve(FakeHttp(), europe, store), host, store)
            assertEquals(zone, "8.4.0", listing(HuaweiSource(), http).releases.single().version)
            assertEquals(zone, listOf(europe, host), http.requests.map { it.url })
        }
    }

    @Test
    fun aSignTheStoreNoLongerTakesIsMadeAgain() {
        val source = HuaweiSource()
        var taken = "sign-1"
        val store = Store { method, sign ->
            if (method == "client.appDetailById" && sign == taken) Fixtures.text("store/huawei-detail.json") else """{"rtnCode":0,"detailInfo":[]}"""
        }
        val http = serve(FakeHttp(), europe, store)
        listing(source, http)
        taken = "sign-3"
        assertEquals("8.4.0", listing(source, http).releases.single().version)
        // The second handshake asks the zone's host for the sign as well.
        assertEquals(3, store.handshakes)
    }

    @Test
    fun anAppTheStoreDoesNotKnowIsNotFound() {
        val store = Store { _, _ -> """{"rtnCode":0,"detailInfo":[]}""" }
        val http = serve(FakeHttp(), europe, store)
        assertFailure(SourceErrorKind.NOT_FOUND) { listing(HuaweiSource(), http) }
        assertEquals(1, store.handshakes)
    }

    @Test
    fun aStoreThatKeepsRefusingIsANetworkProblem() {
        val store = Store { _, _ -> """{"rtnCode":1,"rtnDesc":"refused"}""" }
        assertFailure(SourceErrorKind.NETWORK) { listing(HuaweiSource(), serve(FakeHttp(), europe, store)) }
        assertEquals(3, store.handshakes)
        val silent = FakeHttp().on(europe) { HttpResponse.of(200, """{"rtnCode":0,"serviceZone":"DE"}""", url = europe) }
        assertFailure(SourceErrorKind.NETWORK) { listing(HuaweiSource(), silent) }
        assertFailure(SourceErrorKind.NETWORK) { listing(HuaweiSource(), FakeHttp().text(europe, "", status = 500)) }
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        for (address in listOf("https://files.example.org/org.example.app.apk", "http://appdlc-dre.hispace.dbankcloud.com/org.example.app.apk")) {
            val detail = Fixtures.text("store/huawei-detail.json").replace(Regex("\"url\": \"[^\"]*\""), "\"url\": \"$address\"")
            val http = serve(FakeHttp(), europe, Store { _, _ -> detail })
            val release = listing(HuaweiSource(), http).releases.single()
            assertEquals("8.4.0", release.version)
            assertTrue(address, release.assets.isEmpty())
        }
    }

    @Test
    fun searchListsTheAppsFound() {
        val source = HuaweiSource()
        val store = Store { method, _ -> if (method == "client.getTabDetail") Fixtures.text("store/huawei-search.json") else """{"rtnCode":1}""" }
        val http = serve(FakeHttp(), europe, store)
        val hits = source.search("example app", context(http))
        assertEquals(listOf("Example App", "Example Notes"), hits.map { it.name })
        assertEquals(listOf(canonical, "https://appgallery.huawei.com/app/C100000002"), hits.map { it.url })
        assertEquals("An app for trying things out", hits[0].description)
        assertEquals(canonical, source.match(hits[0].url)?.url)
        val query = http.requests.single { method(it) == "client.getTabDetail" }
        assertEquals("searchApp|example app", form(query).toMap()["uri"])
        assertEquals("25", form(query).toMap()["maxResults"])
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
