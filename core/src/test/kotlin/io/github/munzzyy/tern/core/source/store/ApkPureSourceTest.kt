package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class ApkPureSourceTest {
    private val source = ApkPureSource()
    private val page = "https://apkpure.com/example-app/org.example.app"
    private val versions = "$page/versions"
    private val download = "$page/download/2.1.0"

    private fun spec() = source.match(page)!!

    private fun site() = FakeHttp().resource(versions, "store/apkpure_versions.html").resource(download, "store/apkpure_download.html")

    private fun check(http: FakeHttp, device: DeviceProfile? = null): SourceListing =
        (source.check(spec(), CheckContext(http, InMemoryValidatorStore(), device = device)) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp): SourceErrorKind = try {
        source.check(spec(), CheckContext(http, InMemoryValidatorStore()))
        fail("expected SourceException")
        error("unreachable")
    } catch (e: SourceException) {
        e.kind
    }

    @Test
    fun matchesAppAddressesInTheFormsTheSiteUses() {
        assertEquals(SourceSpec(SourceTypes.APKPURE, page, mapOf(SourceOptions.PACKAGE to "org.example.app")), source.match(page))
        assertEquals(page, source.match("apkpure.com/example-app/org.example.app/download")?.url)
        assertEquals(page, source.match("https://m.apkpure.com/de/example-app/org.example.app/versions")?.url)
        assertEquals(page, source.match("https://www.apkpure.com/example-app/org.example.app")?.url)
        assertEquals(page, source.match("https://apkpure.net/example-app/org.example.app")?.url)
        assertEquals("https://apkpure.com/xy/org.example.app", source.match("https://apkpure.com/xy/org.example.app/download")?.url)
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://apkpure.com/example-app"))
        assertNull(source.match("https://apkpure.com/app/thirteen"))
        assertNull(source.match("https://apkpure.com/"))
        assertNull(source.match("https://example.org/example-app/org.example.app"))
        assertNull(source.match("https://apkpure.com.example.org/example-app/org.example.app"))
        assertNull(source.match("https://d.apkpure.com/b/APK/org.example.app"))
    }

    @Test
    fun readsEachVersionWithItsBuildsFromTheSite() {
        val listing = check(site())

        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An invented app for tests & nothing else.", listing.description)
        assertEquals("https://image.winudf.com/v2/image1/ZXhhbXBsZQ/icon.png?w=160&fakeurl=1", listing.iconUrl)
        assertEquals(listOf("2.1.0", "2.0.0", "1.9.0", "1.4.0"), listing.releases.map { it.version })

        val newest = listing.releases[0]
        assertEquals("2.1.0", newest.id)
        assertEquals(210L, newest.versionCode)
        assertEquals(Instant.parse("2026-09-28T00:00:00Z").toEpochMilli(), newest.publishedAtMs)
        assertEquals(page, newest.pageUrl)
        assertEquals(listOf("org.example.app-211-arm64-v8a.xapk", "org.example.app-210-armeabi-v7a.xapk"), newest.assets.map { it.name })
        assertEquals("https://d.apkpure.com/b/XAPK/org.example.app?versionCode=211", newest.assets[0].url)
        assertEquals(AssetKind.BUNDLE, newest.assets[0].kind)
        assertNull("one size is given for several builds, so none is taken", newest.assets[0].size)

        val older = listing.releases[1]
        assertEquals(listOf("org.example.app-200.apk"), older.assets.map { it.name })
        assertEquals("https://d.apkpure.com/b/APK/org.example.app?versionCode=200", older.assets[0].url)
        assertEquals(AssetKind.APK, older.assets[0].kind)
        assertEquals(50000000L, older.assets[0].size)
    }

    @Test
    fun aBuildOfAnotherPackageOrWithoutAnIdIsLeftOut() {
        val listing = check(site())
        assertTrue(listing.releases.none { it.version == "1.8.0" || it.version == "1.7.0" })
        assertTrue(listing.releases.flatMap { it.assets }.all { it.url.startsWith("https://d.apkpure.com/b/") && "org.example.app?" in it.url })
    }

    @Test
    fun onAKnownDeviceBuildsForOtherProcessorsAreLeftOut() {
        val listing = check(site(), DeviceProfile(listOf("arm64-v8a"), sdk = 34, densityDpi = 420))
        val newest = listing.releases[0]
        assertEquals(listOf("org.example.app-211-arm64-v8a.xapk"), newest.assets.map { it.name })
        assertEquals(211L, newest.versionCode)
    }

    @Test
    fun asksAsItselfAndOnlyForPages() {
        val http = site()
        check(http)
        assertEquals(listOf(versions, download), http.requests.map { it.url })
        assertTrue(http.requests.all { it.headers.isEmpty() && it.method == "GET" })
    }

    @Test
    fun theDownloadPageIsReadOnlyWhenTheNewestVersionHasSeveralBuilds() {
        val single = """<div class="ver_download_link" data-dt-version="3.0.0" data-dt-apkid="b/APK/b3JnLmV4YW1wbGUuYXBwXzMwMF8wMGFi" data-dt-filesize="7"></div>"""
        val http = FakeHttp().text(versions, single)
        val listing = check(http)
        assertEquals(listOf(versions), http.requests.map { it.url })
        assertEquals(300L, listing.releases.single().versionCode)
        assertEquals(7L, listing.releases.single().assets.single().size)
    }

    @Test
    fun anAppTheStoreDoesNotHaveIsNotFound() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(versions, "", status = 404)))
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(versions, "<html><body>Nothing here</body></html>")))
    }

    @Test
    fun failuresOfTheStoreAreReported() {
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(versions, "", status = 503)))
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(versions, "", status = 403)))
    }

    @Test
    fun aDownloadIsFetchedFromTheAddressItIsListedAt() {
        val http = site()
        val asset = check(http).releases[0].assets[0]
        val resolved = SourceRegistry(listOf(source)).resolve(spec(), asset, CheckContext(http, InMemoryValidatorStore()))
        assertEquals(asset.url, resolved.url)
        assertTrue(resolved.headers.isEmpty())
    }
}
