package io.github.munzzyy.tern.core.live

import io.github.munzzyy.tern.core.apk.ApkInspector
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.PoliteHttp
import io.github.munzzyy.tern.core.net.RateLimiter
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Every store and developer channel Tern still reads, asked for a real app over the network the
 * way the app asks: with the User-Agent Tern sends and nothing borrowed from another client. Each
 * one is followed to the first bytes of its file, and every host it asked is one PRIVACY.md names
 * for it. Off unless asked for: ./gradlew :core:test -Dtern.live=true --tests '*StoresLiveTest'
 */
class StoresLiveTest {
    private val raw = JvmHttp()
    private val agent = "Tern/0.1.0"
    private val http = PoliteHttp(raw, RateLimiter(), agent)
    private val registry = SourceRegistry.standard()
    private val phone = DeviceProfile.ARM64_PHONE

    @Before
    fun onlyWhenAsked() {
        assumeTrue("live tests are off", System.getProperty("tern.live") == "true")
    }

    private fun context() = CheckContext(http, InMemoryValidatorStore(), device = phone)

    /** Reads [address] as [type], and the first bytes of the file it offers unless it offers none; returns what the source listed. */
    private fun follow(address: String, type: String, packageName: String? = null): SourceListing {
        val spec = registry.match(address) ?: error("no source takes $address")
        assertEquals(type, spec.type)
        val listing = (registry.check(spec, context()) as CheckResult.Listing).listing
        val newest = listing.releases.first()
        println("$type: ${listing.name} by ${listing.author}, ${listing.releases.size} releases, newest ${newest.version} (${newest.versionCode}), files ${newest.assets.map { it.name }}")
        packageName?.let { assertEquals(it, listing.packageName) }
        if (spec.type !in SourceTypes.TRACK_ONLY) fetchStart(spec, listing)
        identityAndHosts(spec)
        return listing
    }

    private fun fetchStart(spec: SourceSpec, listing: SourceListing) {
        val release = listing.releases.first { it.assets.isNotEmpty() }
        val pick = AssetPicker.rank(release.assets, phone, AssetPolicy()).first().asset
        val download = registry.resolve(spec, pick, context())
        http.execute(HttpRequest(download.url, headers = download.headers + mapOf("Range" to "bytes=0-3", "Accept-Encoding" to "identity"))).use {
            assertTrue("${spec.type}: ${it.status} for ${download.url.take(120)}", it.isSuccess)
            val head = it.body.readNBytes(4)
            assertEquals("${spec.type}: the file is a zip", "PK\u0003\u0004", String(head, Charsets.ISO_8859_1))
            println("${spec.type}: ${pick.name} starts as a zip at ${Urls.host(it.url)}")
        }
        if (pick.kind == AssetKind.APK) {
            val info = ApkInspector.inspectRemote(http, download.url)
            println("${spec.type}: the file is ${info.manifest.packageName} ${info.manifest.versionName}")
            listing.packageName?.let { assertEquals(it, info.manifest.packageName) }
        }
    }

    /** Every request carried Tern's own User-Agent and no header that says it is someone else, and went to a host of the source. */
    private fun identityAndHosts(spec: SourceSpec) {
        val domains = registry.get(spec.type)!!.domains
        for (request in raw.requests) {
            assertEquals(request.url, agent, request.headers["User-Agent"])
            assertTrue(request.url, request.headers.keys.none { it.startsWith("X-App", ignoreCase = true) || it.startsWith("Ual-", ignoreCase = true) })
            val host = Urls.host(request.url)
            if (domains.isNotEmpty()) assertTrue("${spec.type} asked $host", domains.any { host == it || host.endsWith(".$it") })
        }
        println("${spec.type}: ${raw.requests.size} requests to ${raw.requests.map { Urls.host(it.url) }.distinct()}")
    }

    private fun search(type: String, query: String) {
        val store = registry.get(type) as Searchable
        val hits = store.search(query, context())
        println("$type search for $query: ${hits.take(3).map { it.name to it.url }}")
        assertTrue(hits.isNotEmpty())
        identityAndHosts(SourceSpec(type, hits.first().url))
    }

    @Test
    fun apkPure() {
        follow("https://apkpure.com/vlc-for-android/org.videolan.vlc", SourceTypes.APKPURE, "org.videolan.vlc")
    }

    @Test
    fun aptoide() {
        follow("https://videolabs-vlc.en.aptoide.com/app", SourceTypes.APTOIDE, "org.videolan.vlc")
    }

    @Test
    fun aptoideSearch() = search(SourceTypes.APTOIDE, "vlc")

    @Test
    fun apkCombo() {
        follow("https://apkcombo.com/vlc/org.videolan.vlc", SourceTypes.APKCOMBO, "org.videolan.vlc")
    }

    @Test
    fun apkMirrorIsFollowedWithoutAFile() {
        val listing = follow("https://www.apkmirror.com/apk/mozilla/firefox/", SourceTypes.APKMIRROR)
        assertTrue(listing.releases.first().notes != null || listing.releases.first().fileSize != null)
    }

    @Test
    fun huaweiAppGallery() {
        follow("https://appgallery.huawei.com/app/C100336075", SourceTypes.HUAWEI, "com.lifeplus.diveplus")
    }

    @Test
    fun huaweiSearch() = search(SourceTypes.HUAWEI, "wechat")

    @Test
    fun galaxyStore() {
        follow("https://galaxystore.samsung.com/detail/com.sec.android.app.sbrowser", SourceTypes.SAMSUNG, "com.sec.android.app.sbrowser")
    }

    @Test
    fun vivo() {
        follow("https://h5.appstore.vivo.com.cn/#/details?appId=40413", SourceTypes.VIVO, "com.tencent.mm")
    }

    @Test
    fun vivoSearch() = search(SourceTypes.VIVO, "wechat")

    @Test
    fun tencent() {
        follow("https://sj.qq.com/appdetail/com.tencent.mm", SourceTypes.TENCENT, "com.tencent.mm")
    }

    @Test
    fun itchIo() {
        follow("https://xlaucifer.itch.io/remember", SourceTypes.ITCHIO)
    }

    @Test
    fun telegram() {
        follow("https://telegram.org/android", SourceTypes.TELEGRAM)
    }

    @Test
    fun neutronCode() {
        follow("https://neutroncode.com/downloads/file/2-neutronmpapkarm64", SourceTypes.NEUTRONCODE)
    }
}
