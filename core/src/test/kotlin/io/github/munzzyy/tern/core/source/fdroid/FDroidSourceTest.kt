package io.github.munzzyy.tern.core.source.fdroid

import io.github.munzzyy.tern.core.model.NotesFormat
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

    private val api = "https://f-droid.org/api/v1/packages/org.example.app"
    private val metadata = "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.example.app.yml"
    private val versions = """
        {"packageName": "org.example.app", "suggestedVersionCode": 10,
         "packages": [{"versionName": "1.1-beta", "versionCode": 11}, {"versionName": "1.0", "versionCode": 10}, {"versionName": "0.9", "versionCode": 9}]}
    """.trimIndent()

    private fun data(changelog: String) = """
        Categories:
          - Tools
        License: GPL-3.0-only
        AuthorName: Example Labs
        SourceCode: https://github.com/example/app
        Changelog: $changelog
    """.trimIndent()

    private fun listingOf(http: FakeHttp, url: String = "https://f-droid.org/packages/org.example.app") =
        (source.check(source.match(url)!!, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing

    @Test
    fun readsTheAuthorAndTheChangelogFileFromFDroidsData() {
        val changes = "# Changes\n\n## 1.1\n- Starts faster\n"
        val http = FakeHttp().text(api, versions)
            .text(metadata, data("https://github.com/example/app/blob/HEAD/CHANGELOG.md"))
            .text("https://github.com/example/app/raw/HEAD/CHANGELOG.md", changes)
        val listing = listingOf(http)
        assertEquals("Example Labs", listing.author)
        // The newest release and the one F-Droid suggests carry it, as either may be the one offered.
        assertEquals(listOf(changes, changes, null), listing.releases.map { it.notes })
        assertEquals(NotesFormat.MARKDOWN, listing.releases[0].notesFormat)
    }

    @Test
    fun aChangelogKeptElsewhereIsItsAddress() {
        val http = FakeHttp().text(api, versions).text(metadata, data("https://example.org/app/changes"))
        val listing = listingOf(http)
        assertEquals("https://example.org/app/changes", listing.releases[0].notes)
        assertEquals(listOf(api, metadata), http.requests.map { it.url })
    }

    @Test
    fun aLongChangelogIsCutWhereObtainiumCutsIt() {
        val http = FakeHttp().text(api, versions)
            .text(metadata, data("https://gitlab.com/example/app/-/blob/main/CHANGELOG.md"))
            .text("https://gitlab.com/example/app/-/raw/main/CHANGELOG.md", "x".repeat(2047) + "😀" + "y".repeat(5000))
        val notes = listingOf(http).releases[0].notes!!
        // The cut does not part the two halves of the last character.
        assertEquals("x".repeat(2047) + "…", notes)
    }

    @Test
    fun dataThatCannotBeHadLeavesTheVersionsAsTheyAre() {
        val listing = listingOf(FakeHttp().text(api, versions).text(metadata, "Not Found", status = 404))
        assertNull(listing.author)
        assertEquals(3, listing.releases.size)
        assertTrue(listing.releases.all { it.notes == null })
        // A changelog file that cannot be read leaves its address.
        val missing = FakeHttp().text(api, versions).text(metadata, data("https://github.com/example/app/blob/main/CHANGES.md"))
        assertEquals("https://github.com/example/app/blob/main/CHANGES.md", listingOf(missing).releases[0].notes)
    }

    @Test
    fun izzyOnDroidIsNotLookedUpInFDroidsData() {
        val izzyApi = "https://apt.izzysoft.de/fdroid/api/v1/packages/org.example.app"
        val http = FakeHttp().text(izzyApi, versions)
        listingOf(http, "https://apt.izzysoft.de/fdroid/index/apk/org.example.app")
        assertEquals(listOf(izzyApi), http.requests.map { it.url })
    }

    @Test
    fun onlyAFileOnGitHubOrGitLabIsReadForTheChangelog() {
        assertEquals("https://github.com/example/app/raw/HEAD/CHANGELOG.md", FDroidSource.rawFile("https://github.com/example/app/blob/HEAD/CHANGELOG.md"))
        assertEquals("https://gitlab.com/group/app/-/raw/main/docs/CHANGES.md", FDroidSource.rawFile("https://gitlab.com/group/app/-/blob/main/docs/CHANGES.md"))
        assertEquals("https://github.com/blob/app/raw/main/CHANGELOG.md", FDroidSource.rawFile("http://www.github.com/blob/app/blob/main/CHANGELOG.md"))
        assertNull(FDroidSource.rawFile("https://github.com/example/app/releases"))
        assertNull(FDroidSource.rawFile("https://github.com/example/app/blob/main"))
        assertNull(FDroidSource.rawFile("https://git.example.org/example/app/blob/main/CHANGELOG.md"))
        assertNull(FDroidSource.rawFile("https://github.com.example.org/example/app/blob/main/CHANGELOG.md"))
    }

    @Test
    fun readsAFieldOfTheDataWithOrWithoutQuotes() {
        val lines = listOf("AuthorName: 'Example Labs: the ''team'''", "Changelog: \"https://example.org/changes\"", "Empty: ", "WebSite: https://example.org")
        assertEquals("Example Labs: the 'team'", FDroidSource.field(lines, "AuthorName"))
        assertEquals("https://example.org/changes", FDroidSource.field(lines, "Changelog"))
        assertNull(FDroidSource.field(lines, "Empty"))
        assertNull(FDroidSource.field(lines, "AuthorEmail"))
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
