package io.github.munzzyy.jackdaw.core.source

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.NotesFormat
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.Headers
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import io.github.munzzyy.jackdaw.core.net.InMemoryValidatorStore
import io.github.munzzyy.jackdaw.core.net.RateLimitedException
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidSource
import io.github.munzzyy.jackdaw.core.source.web.DirectSource
import io.github.munzzyy.jackdaw.core.source.web.HtmlSource
import io.github.munzzyy.jackdaw.core.testing.FakeHttp
import io.github.munzzyy.jackdaw.core.testing.Fixtures
import io.github.munzzyy.jackdaw.core.verify.Checksums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RepoReviewTest {
    private val repoUrl = "https://example.com/fdroid/repo"
    private val entryUrl = "$repoUrl/entry.jar"
    private val indexUrl = "$repoUrl/index-v2.json"
    private val repo = FDroidRepoSource()
    private val two = SourceSpec(repo.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two"))

    private fun jar(name: String) = Fixtures.bytes("fdroid/repo/$name")

    /** Answers 304 to any conditional request, the way a real server does once it has handed out an ETag. */
    private fun FakeHttp.conditional(url: String, body: ByteArray, etag: String) = on(url) { request ->
        val sent = request.headers.entries.firstOrNull { it.key.equals("If-None-Match", ignoreCase = true) }?.value
        if (sent == etag) HttpResponse.of(304, "", url = url) else HttpResponse.of(200, body, Headers.of("ETag" to etag), url)
    }

    private fun listing(result: CheckResult) = (result as CheckResult.Listing).listing

    @Test
    fun aFailedIndexDownloadIsRetriedNotRemembered() {
        val validators = InMemoryValidatorStore()
        val broken = FakeHttp().conditional(entryUrl, jar("entry.jar"), "\"e1\"").on(indexUrl) { HttpResponse.of(500, "", url = indexUrl) }
        assertThrows(SourceException::class.java) { repo.check(two, CheckContext(broken, validators)) }

        val healthy = FakeHttp().conditional(entryUrl, jar("entry.jar"), "\"e1\"").bytes(indexUrl, Fixtures.bytes("fdroid/repo/index-v2.json"))
        assertEquals("0.5", listing(repo.check(two, CheckContext(healthy, validators))).releases.single().version)

        assertEquals(CheckResult.Unchanged, repo.check(two, CheckContext(healthy, validators)))
    }

    @Test
    fun anIndexOlderThanTheLastOneSeenIsRefused() {
        val validators = InMemoryValidatorStore()
        val index = Fixtures.bytes("fdroid/repo/index-v2.json")
        repo.check(two, CheckContext(FakeHttp().bytes(entryUrl, jar("entry-newer.jar")).bytes(indexUrl, index), validators))

        val replayed = FakeHttp().bytes(entryUrl, jar("entry.jar")).bytes(indexUrl, index)
        val refused = assertThrows(SourceException::class.java) { repo.check(two, CheckContext(replayed, validators)) }
        assertEquals(SourceErrorKind.AUTH, refused.kind)
        assertTrue(replayed.requestsTo(indexUrl).isEmpty())

        val same = FakeHttp().bytes(entryUrl, jar("entry-newer.jar")).bytes(indexUrl, index)
        assertEquals(1, listing(repo.check(two, CheckContext(same, validators))).releases.size)
    }

    @Test
    fun anIndexNameCannotLeaveTheRepository() {
        val http = FakeHttp().bytes(entryUrl, jar("entry-escaping.jar"))
        val refused = assertThrows(SourceException::class.java) { repo.check(two, CheckContext(http, InMemoryValidatorStore())) }
        assertEquals(SourceErrorKind.PARSE, refused.kind)
        assertEquals(listOf(entryUrl), http.requests.map { it.url })
    }

    @Test
    fun carriesWhatIsNewAndWhenItWasAdded() {
        val http = FakeHttp().bytes(entryUrl, jar("entry.jar")).bytes(indexUrl, Fixtures.bytes("fdroid/repo/index-v2.json"))
        val release = listing(repo.check(two, CheckContext(http, InMemoryValidatorStore()))).releases.single()
        assertEquals("Fixed the crash on start.", release.notes)
        assertEquals(NotesFormat.PLAIN, release.notesFormat)
        assertEquals(1697000000000L, release.publishedAtMs)
    }

    @Test
    fun aRepositoryAddressNeverCarriesCredentials() {
        assertNull(repo.match("https://user:secret@example.com/fdroid/repo"))
        assertEquals(repoUrl, repo.match("https://EXAMPLE.com/fdroid/repo/")?.url)
    }

    @Test
    fun networkAndParseFailuresArriveAsSourceErrors() {
        val spec = FDroidSource().match("https://f-droid.org/packages/org.example.app")!!
        val api = "https://f-droid.org/api/v1/packages/org.example.app"

        val garbled = assertThrows(SourceException::class.java) {
            FDroidSource().check(spec, CheckContext(FakeHttp().text(api, "<html>busy</html>"), InMemoryValidatorStore()))
        }
        assertEquals(SourceErrorKind.PARSE, garbled.kind)

        val offline = assertThrows(SourceException::class.java) {
            FDroidSource().check(spec, CheckContext(FakeHttp().on(api) { throw IOException("unreachable") }, InMemoryValidatorStore()))
        }
        assertEquals(SourceErrorKind.NETWORK, offline.kind)

        val limited = assertThrows(SourceException::class.java) {
            FDroidSource().check(spec, CheckContext(FakeHttp().on(api) { throw RateLimitedException("f-droid.org", 99L) }, InMemoryValidatorStore()))
        }
        assertEquals(SourceErrorKind.RATE_LIMITED, limited.kind)
        assertEquals(99L, limited.retryAtMs)

        val page = "https://example.org/get"
        val htmlOffline = assertThrows(SourceException::class.java) {
            HtmlSource().check(SourceSpec(SourceTypes.HTML, page), CheckContext(FakeHttp().on(page) { throw IOException("unreachable") }, InMemoryValidatorStore()))
        }
        assertEquals(SourceErrorKind.NETWORK, htmlOffline.kind)
    }

    @Test
    fun aGarbledAnswerIsNotRememberedAsCurrent() {
        val spec = FDroidSource().match("https://f-droid.org/packages/org.example.app")!!
        val api = "https://f-droid.org/api/v1/packages/org.example.app"
        val validators = InMemoryValidatorStore()
        assertThrows(SourceException::class.java) {
            FDroidSource().check(spec, CheckContext(FakeHttp().conditional(api, "not json".toByteArray(), "\"a\""), validators))
        }
        val good = """{"packageName":"org.example.app","suggestedVersionCode":3,"packages":[{"versionName":"1.3","versionCode":3}]}"""
        val second = FDroidSource().check(spec, CheckContext(FakeHttp().conditional(api, good.toByteArray(), "\"a\""), validators))
        assertEquals("1.3", listing(second).releases.single().version)
    }

    @Test
    fun aLinkThatNeverChangesIsFollowedByWhatItPointsAt() {
        val page = "https://example.org/get"
        val file = "https://example.org/files/latest.apk"
        val html = """<html><head><title>Example App &amp; Tools</title></head><body><a href="/files/latest.apk">Download</a></body></html>"""
        val validators = InMemoryValidatorStore()
        val spec = SourceSpec(SourceTypes.HTML, page)

        fun site(etag: String) = FakeHttp()
            .conditional(page, html.toByteArray(), "\"page\"")
            .on(file) { HttpResponse.of(200, "", Headers.of("ETag" to etag, "Content-Length" to "4096"), file) }

        val first = listing(HtmlSource().check(spec, CheckContext(site("\"build-1\""), validators)))
        assertEquals("Example App & Tools", first.name)
        assertEquals(file, first.releases.single().assets.single().url)
        assertEquals(4096L, first.releases.single().assets.single().size)

        val again = listing(HtmlSource().check(spec, CheckContext(site("\"build-1\""), validators)))
        assertEquals(first.releases.single().id, again.releases.single().id)

        val replaced = listing(HtmlSource().check(spec, CheckContext(site("\"build-2\""), validators)))
        assertNotEquals(first.releases.single().id, replaced.releases.single().id)
    }

    @Test
    fun aPageWithNothingToInstallIsAskedAgain() {
        val page = "https://example.org/get"
        val validators = InMemoryValidatorStore()
        val spec = SourceSpec(SourceTypes.HTML, page)
        val empty = FakeHttp().conditional(page, "<a href=\"/about\">About</a>".toByteArray(), "\"p\"")
        assertEquals(SourceErrorKind.NO_RELEASES, assertThrows(SourceException::class.java) { HtmlSource().check(spec, CheckContext(empty, validators)) }.kind)
        val filled = FakeHttp().conditional(page, "<a href=\"/app-2.1.0.apk\">Get</a>".toByteArray(), "\"p\"")
        assertEquals("2.1.0", listing(HtmlSource().check(spec, CheckContext(filled, validators))).releases.single().version)
    }

    @Test(timeout = 4000)
    fun aPageOfUnclosedLinksDoesNotStall() {
        val page = "https://example.org/get"
        val html = buildString {
            append("<a href=\"/app-1.0.apk\">real</a>")
            repeat(150_000) { append("<a href=/f").append(it).append(".txt>x ") }
        }
        val result = HtmlSource().check(SourceSpec(SourceTypes.HTML, page), CheckContext(FakeHttp().text(page, html), InMemoryValidatorStore()))
        assertEquals("1.0", listing(result).releases.single().version)
    }

    @Test
    fun aDirectLinkIsStoredInItsCanonicalForm() {
        assertEquals("https://example.org/a/app.apk", DirectSource().match("HTTPS://Example.org:443/a/app.apk")?.url)
        assertNull(DirectSource().match("https://user:pw@example.org/app.apk"))
        assertNull(DirectSource().match("https://example.org/app.txt"))
    }

    @Test
    fun aSumsFileMayListFilesWithTheirFolder() {
        val asset = Asset("app-release.apk", "https://example.org/app-release.apk")
        val sums = Asset("SHA256SUMS", "https://example.org/SHA256SUMS")
        val release = Release(id = "v1", version = "v1", assets = listOf(asset, sums))
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        assertEquals(a, Checksums.expectedFor(release, asset) { "$b  ./dist/other.apk\n$a  ./dist/app-release.apk\n" })
        assertEquals(a, Checksums.expectedFor(release, asset) { "$a *build/outputs/app-release.apk\n" })
        assertNull(Checksums.expectedFor(release, asset) { "$b  ./dist/other-app-release.apk\n" })
    }
}
