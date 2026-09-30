package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.NotesFormat
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
    private val history = "https://tapi.pureapk.com/v3/get_app_his_version?package_name=org.example.app&hl=en"

    private fun spec() = source.match(page)!!

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
        assertEquals("https://apkpure.net/example-app/org.example.app", source.match("https://apkpure.net/example-app/org.example.app")?.url)
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
    fun readsEachVersionWithItsVariants() {
        val http = FakeHttp().resource(history, "store/apkpure_history.json")
        val listing = check(http)

        assertEquals("Example App", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("org.example.app", listing.packageName)
        assertEquals("An invented app for tests.", listing.description)
        assertEquals(listOf("2.1.0", "2.0.0", "1.9.0", "1.8.0"), listing.releases.map { it.version })

        val newest = listing.releases[0]
        assertEquals("2.1.0", newest.id)
        assertEquals(210L, newest.versionCode)
        assertEquals("• Faster start.<br>• Fewer crashes.", newest.notes)
        assertEquals(NotesFormat.HTML, newest.notesFormat)
        assertEquals(Instant.parse("2026-09-28T05:00:31Z").toEpochMilli(), newest.publishedAtMs)
        assertEquals(page, newest.pageUrl)
        assertEquals(
            listOf("org.example.app-210-arm64-v8a.xapk", "org.example.app-210-arm64-v8a,armeabi-v7a,x86,x86_64.apk"),
            newest.assets.map { it.name },
        )
        val bundle = newest.assets[0]
        assertEquals("https://data.winudf.com/XAPK/b3JnLmV4YW1wbGUuYXBwXzIxMF9hYQ", bundle.url)
        assertEquals(AssetKind.BUNDLE, bundle.kind)
        assertEquals(51200000L, bundle.size)
        assertEquals("abcdef01".repeat(8), bundle.sha256)
        assertEquals(AssetKind.APK, newest.assets[1].kind)

        val headers = http.requestsTo(history).single().headers
        assertEquals("projecta", headers["Ual-Access-Businessid"])
        assertEquals("""{"device_info":{"os_ver":"35"}}""", headers["Ual-Access-ProjectA"])
    }

    @Test
    fun aVersionWhoseVariantsCarryDifferentCodesTakesTheLowest() {
        val listing = check(FakeHttp().resource(history, "store/apkpure_history.json"))
        val older = listing.releases.first { it.version == "2.0.0" }
        assertEquals(200L, older.versionCode)
        assertEquals("First release with sync.", older.notes)
        assertEquals(2, older.assets.size)
    }

    @Test
    fun onAKnownDeviceVariantsForOtherProcessorsAreLeftOut() {
        val http = FakeHttp().resource(history, "store/apkpure_history.json")
        val listing = check(http, DeviceProfile(listOf("arm64-v8a"), sdk = 34, densityDpi = 420))

        assertEquals(listOf("2.1.0", "2.0.0", "1.8.0"), listing.releases.map { it.version })
        val older = listing.releases[1]
        assertEquals(listOf("org.example.app-201-arm64-v8a.xapk"), older.assets.map { it.name })
        assertEquals(201L, older.versionCode)
        assertEquals(listOf("org.example.app-180-universal.apk"), listing.releases[2].assets.map { it.name })
        assertEquals("""{"device_info":{"os_ver":"34"}}""", http.requestsTo(history).single().headers["Ual-Access-ProjectA"])
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        val listing = check(FakeHttp().resource(history, "store/apkpure_history.json"))
        val addresses = listing.releases.flatMap { release -> release.assets.map { it.url } }
        assertTrue(addresses.all { it.startsWith("https://data.winudf.com/") })
        assertTrue(listing.releases[0].assets.none { it.name.contains("210-armeabi-v7a") })
    }

    @Test
    fun anAppTheStoreDoesNotHaveIsNotFound() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(history, """{"retcode":0,"errmsg":"success","version_list":[]}""")))
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(history, "", status = 404)))
    }

    @Test
    fun failuresOfTheStoreAreReported() {
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(history, "", status = 503)))
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(history, """{"retcode":403,"errmsg":"denied"}""")))
        assertEquals(SourceErrorKind.PARSE, failure(FakeHttp().text(history, "<html>")))
    }

    @Test
    fun noVersionWithAFileForTheDeviceIsNoRelease() {
        val body = """{"retcode":0,"version_list":[{"package_name":"org.example.app","version_code":"5","version_name":"0.5",
            "native_code":["x86"],"asset":{"type":"APK","url":"https://data.winudf.com/APK/eA?token=1"}}]}"""
        val http = FakeHttp().text(history, body)
        try {
            source.check(spec(), CheckContext(http, InMemoryValidatorStore(), device = DeviceProfile.ARM64_PHONE))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NO_RELEASES, e.kind)
        }
    }

    @Test
    fun aDownloadIsResolvedToTheAddressTheStoreHandsOutNow() {
        val http = FakeHttp().resource(history, "store/apkpure_history.json")
        val context = CheckContext(http, InMemoryValidatorStore())
        val asset = check(http).releases[0].assets[0]

        val download = SourceRegistry(listOf(source)).resolve(spec(), asset, context)
        assertEquals(
            "https://data.winudf.com/XAPK/b3JnLmV4YW1wbGUuYXBwXzIxMF9hYQ?filename=Example.xapk&k=0a1b&package_name=org.example.app&token=1790729950-aa-0-bb",
            download.url,
        )
        assertTrue(download.headers.isEmpty())
    }

    @Test
    fun aFileTheStoreNoLongerOffersCannotBeResolved() {
        val http = FakeHttp().resource(history, "store/apkpure_history.json")
        val gone = Asset("org.example.app-100.apk", "https://data.winudf.com/APK/b3JnLmV4YW1wbGUuYXBwXzEwMA")
        try {
            source.resolve(spec(), gone, CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }
}
