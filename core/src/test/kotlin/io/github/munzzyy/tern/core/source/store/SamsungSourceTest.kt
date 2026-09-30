package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.SourceSpec
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SamsungSourceTest {
    private val source = SamsungSource()
    private val canonical = "https://apps.galaxyappstore.com/detail/org.example.app"

    private fun stubUrl(pkg: String = "org.example.app", model: String = "SM-S948B", csc: String = "DBT", sdk: Int = 36, abiType: String = "64") =
        "https://vas.samsungapps.com/stub/stubDownload.as?appId=$pkg&deviceId=$model&mcc=425&mnc=01&csc=$csc&sdkVer=$sdk&abiType=$abiType&extuk=0"

    private fun context(http: FakeHttp, device: DeviceProfile? = DeviceProfile.ARM64_PHONE) = CheckContext(http, InMemoryValidatorStore(), device = device)

    private fun listing(http: FakeHttp, spec: SourceSpec = source.match(canonical)!!): SourceListing =
        (source.check(spec, context(http)) as CheckResult.Listing).listing

    @Test
    fun matchesTheStoreAddressesAndKeepsThePackage() {
        for (url in listOf(
            "https://galaxystore.samsung.com/detail/org.example.app",
            "https://www.galaxystore.samsung.com/detail/org.example.app?session_id=W_1",
            "https://apps.samsung.com/appquery/appDetail.as?appId=org.example.app",
            "https://apps.samsung.cn/appquery/appDetail.as?appId=org.example.app",
            "https://galaxyappstore.com/detail/org.example.app",
            "http://apps.galaxyappstore.com/detail/org.example.app",
            "https://galaxystore.samsung.com/prepost/000001234567?appId=org.example.app",
        )) {
            val spec = source.match(url)
            assertEquals(url, canonical, spec?.url)
            assertEquals(url, "org.example.app", spec?.option(SourceOptions.PACKAGE))
        }
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://example.com/detail/org.example.app"))
        assertNull(source.match("https://galaxystore.samsung.com.example.org/detail/org.example.app"))
        assertNull(source.match("https://apps.samsung.com/appquery/appDetail.as"))
        assertNull(source.match("https://galaxystore.samsung.com/prepost/000001234567"))
        assertNull(source.match("https://galaxystore.samsung.com/"))
    }

    @Test
    fun readsTheStubAndListsTheFileWithoutItsToken() {
        val http = FakeHttp().resource(stubUrl(), "store/samsung-stub.xml")
        val listing = listing(http)
        assertEquals("Example App", listing.name)
        assertEquals("org.example.app", listing.packageName)
        val release = listing.releases.single()
        assertEquals("2.4.1", release.version)
        assertEquals(241L, release.versionCode)
        assertEquals("241", release.id)
        assertEquals(1782977975007L, release.publishedAtMs)
        assertEquals(canonical, release.pageUrl)
        val asset = release.assets.single()
        assertEquals("org.example.app.apk", asset.name)
        assertEquals("https://cflare-dn.gw.samsungapps.com/astore_bin/atom/2026/0702/prePost_20260702073935007.apk", asset.url)
        assertEquals(12345678L, asset.size)
    }

    @Test
    fun withoutAModelOfItsOwnEveryCopyAsksForTheSameOneAndSaysOnlyTheAndroidVersionAndBitness() {
        val spec = SourceSpec(source.type, canonical, mapOf(SourceOptions.PACKAGE to "org.example.app"))
        val phone32 = DeviceProfile(listOf("armeabi-v7a", "armeabi"), sdk = 30, densityDpi = 320)
        val http = FakeHttp().resource(stubUrl(sdk = 30, abiType = "32"), "store/samsung-stub.xml")
        val result = source.check(spec, context(http, phone32)) as CheckResult.Listing
        assertEquals("2.4.1", result.listing.releases.single().version)
        assertTrue(http.requests.single().headers.isEmpty())
    }

    @Test
    fun asksForTheModelAndRegionThePersonSetForTheApp() {
        val spec = SourceSpec(source.type, canonical, mapOf(SourceOptions.PACKAGE to "org.example.app", SourceOptions.DEVICE_MODEL to "SM-A556B", SourceOptions.CSC to "EUX"))
        val http = FakeHttp().resource(stubUrl(model = "SM-A556B", csc = "EUX"), "store/samsung-stub.xml")
        assertEquals("2.4.1", listing(http, spec).releases.single().version)
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        val xml = Fixtures.text("store/samsung-stub.xml").replace("cflare-dn.gw.samsungapps.com", "files.example.org")
        val release = listing(FakeHttp().text(stubUrl(), xml)).releases.single()
        assertEquals("2.4.1", release.version)
        assertTrue(release.assets.isEmpty())
        val plain = Fixtures.text("store/samsung-stub.xml").replace("https://cflare-dn", "http://cflare-dn")
        assertTrue(listing(FakeHttp().text(stubUrl(), plain)).releases.single().assets.isEmpty())
    }

    @Test
    fun anAppTheStoreDoesNotServeIsNotFound() {
        val spec = source.match("https://galaxystore.samsung.com/detail/org.example.missing")!!
        val http = FakeHttp().resource(stubUrl(pkg = "org.example.missing"), "store/samsung-stub-missing.xml")
        assertFailure(SourceErrorKind.NOT_FOUND) { source.check(spec, context(http)) }
    }

    @Test
    fun aFailingStoreIsANetworkProblem() {
        val http = FakeHttp().text(stubUrl(), "", status = 503)
        assertFailure(SourceErrorKind.NETWORK) { source.check(source.match(canonical)!!, context(http)) }
    }

    @Test
    fun anAnswerForAnotherPackageIsRefused() {
        val xml = Fixtures.text("store/samsung-stub.xml").replace("<appId>org.example.app</appId>", "<appId>org.example.other</appId>")
        assertFailure(SourceErrorKind.PARSE) { listing(FakeHttp().text(stubUrl(), xml)) }
    }

    @Test
    fun resolveAsksForAFreshAddress() {
        val http = FakeHttp().resource(stubUrl(), "store/samsung-stub.xml")
        val spec = source.match(canonical)!!
        val asset = listing(http, spec).releases.single().assets.single()
        val download = source.resolve(spec, asset, context(http))
        assertTrue(download.url, download.url.startsWith("${asset.url}?ctnt_id=000001234567"))
        assertTrue(download.url.contains("verify="))
        assertEquals(2, http.requestsTo(stubUrl()).size)
    }

    @Test
    fun resolveRefusesAFileOnAnotherHost() {
        val spec = source.match(canonical)!!
        val asset = listing(FakeHttp().resource(stubUrl(), "store/samsung-stub.xml"), spec).releases.single().assets.single()
        val xml = Fixtures.text("store/samsung-stub.xml").replace("cflare-dn.gw.samsungapps.com", "files.example.org")
        assertFailure(SourceErrorKind.PARSE) { source.resolve(spec, asset, context(FakeHttp().text(stubUrl(), xml))) }
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
