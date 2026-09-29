package io.github.munzzyy.tern.core.source.fdroid

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FDroidSourceTest {
    private val source = FDroidSource()

    @Test
    fun matchesFDroidPackageUrl() {
        val spec = source.match("https://f-droid.org/packages/org.example.app")
        assertEquals("https://f-droid.org/packages/org.example.app", spec?.url)
        assertEquals("org.example.app", spec?.option(io.github.munzzyy.tern.core.source.SourceOptions.PACKAGE))
    }

    @Test
    fun matchesFDroidPackageUrlWithLanguagePrefix() {
        val spec = source.match("https://f-droid.org/en/packages/org.example.app")
        assertEquals("https://f-droid.org/packages/org.example.app", spec?.url)
    }

    @Test
    fun matchesIzzyOnDroidAptHost() {
        val spec = source.match("https://apt.izzysoft.de/fdroid/index/apk/org.example.app")
        assertEquals("https://apt.izzysoft.de/fdroid/index/apk/org.example.app", spec?.url)
    }

    @Test
    fun matchesIzzyOnDroidAndroidHost() {
        val spec = source.match("https://android.izzysoft.de/repo/apk/org.example.app")
        assertEquals("https://apt.izzysoft.de/fdroid/index/apk/org.example.app", spec?.url)
    }

    @Test
    fun rejectsInvalidPackageName() {
        assertNull(source.match("https://f-droid.org/packages/nodothere"))
        assertNull(source.match("https://f-droid.org/packages/1.invalid"))
    }

    @Test
    fun doesNotMatchOtherHosts() {
        assertNull(source.match("https://example.com/packages/org.example.app"))
    }

    @Test
    fun parsesPackagesApiSortingNewestFirstAndFlaggingPrerelease() {
        val pkg = "org.example.app"
        val url = "https://f-droid.org/packages/$pkg"
        val apiUrl = "https://f-droid.org/api/v1/packages/$pkg"
        val body = """
            {"packageName": "$pkg", "suggestedVersionCode": 10,
             "packages": [
               {"versionName": "0.9", "versionCode": 9},
               {"versionName": "1.1-beta", "versionCode": 11},
               {"versionName": "1.0", "versionCode": 10}
             ]}
        """.trimIndent()
        val http = FakeHttp().text(apiUrl, body)
        val result = source.check(source.match(url)!!, CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(3, listing.releases.size)
        assertEquals("11", listing.releases[0].id)
        assertTrue(listing.releases[0].prerelease)
        assertEquals("10", listing.releases[1].id)
        assertTrue(!listing.releases[1].prerelease)
        assertEquals("https://f-droid.org/repo/${pkg}_11.apk", listing.releases[0].assets[0].url)
    }

    @Test
    fun notFoundThrowsSourceException() {
        val pkg = "org.example.missing"
        val url = "https://f-droid.org/packages/$pkg"
        val apiUrl = "https://f-droid.org/api/v1/packages/$pkg"
        val http = FakeHttp().text(apiUrl, "{}", status = 404)
        try {
            source.check(source.match(url)!!, CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun aPackageOptionThatIsNotAPackageNameNeverReachesAnAddress() {
        val http = FakeHttp()
        for (bad in listOf("../../repo/index", "org.example.app/../x", "org.example.app?x=1", "org.example.app#x", "org example", "")) {
            val spec = SourceSpec(source.type, "https://f-droid.org/packages/org.example.app", mapOf(SourceOptions.PACKAGE to bad))
            try {
                source.check(spec, CheckContext(http, InMemoryValidatorStore()))
                fail("checked with the package option \"$bad\"")
            } catch (e: SourceException) {
                assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
            }
        }
        assertEquals(emptyList<String>(), http.requests.map { it.url })
    }

    @Test
    fun anAnswerForAnotherPackageIsRefused() {
        val api = "https://f-droid.org/api/v1/packages/org.example.app"
        val http = FakeHttp().on(api) {
            HttpResponse.of(200, """{"packageName":"org.example.other","suggestedVersionCode":3,"packages":[{"versionName":"1.0","versionCode":3}]}""", Headers.EMPTY, api)
        }
        val spec = SourceSpec(source.type, "https://f-droid.org/packages/org.example.app", mapOf(SourceOptions.PACKAGE to "org.example.app"))
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
            fail("took an answer about another package")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }
}
