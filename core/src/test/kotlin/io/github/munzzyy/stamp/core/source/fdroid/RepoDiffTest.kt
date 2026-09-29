package io.github.munzzyy.stamp.core.source.fdroid

import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceListing
import io.github.munzzyy.stamp.core.source.SourceOptions
import io.github.munzzyy.stamp.core.testing.FakeHttp
import io.github.munzzyy.stamp.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoDiffTest {
    private val repoUrl = "https://example.com/fdroid/repo"
    private val entryUrl = "$repoUrl/entry.jar"
    private val indexUrl = "$repoUrl/index-v2.json"
    private val nextIndexUrl = "$repoUrl/index-v2-next.json"
    private val diffUrl = "$repoUrl/diff/1700000000000.json"

    private fun spec(pkg: String) = SourceSpec("fdroid-repo", repoUrl, mapOf(SourceOptions.PACKAGE to pkg))

    private fun fixture(name: String) = Fixtures.bytes("fdroid/repo/$name")

    private fun first(entry: String = "entry.jar") = FakeHttp().bytes(entryUrl, fixture(entry)).bytes(indexUrl, fixture("index-v2.json"))

    private fun next(entry: String = "entry-next.jar", diff: String = "diff/1700000000000.json", diffAt: String = diffUrl) = FakeHttp()
        .bytes(entryUrl, fixture(entry))
        .bytes(nextIndexUrl, fixture("index-v2-next.json"))
        .bytes(diffAt, fixture(diff))

    private fun listing(result: CheckResult): SourceListing = (result as CheckResult.Listing).listing

    @Test
    fun aChangedRepositoryCostsOnlyItsDiff() {
        val source = FDroidRepoSource()
        val validators = InMemoryValidatorStore()
        assertEquals(listOf("0.5"), listing(source.check(spec("org.example.two"), CheckContext(first(), validators))).releases.map { it.version })

        val http = next()
        val viaDiff = listing(source.check(spec("org.example.two"), CheckContext(http, validators)))
        assertEquals(listOf(entryUrl, diffUrl), http.requests.map { it.url })

        val fresh = listing(FDroidRepoSource().check(spec("org.example.two"), CheckContext(next(), InMemoryValidatorStore())))
        assertEquals(fresh.releases, viaDiff.releases)
        assertEquals(listOf("0.6"), viaDiff.releases.map { it.version })
        assertEquals("7".repeat(64), viaDiff.releases.single().assets.single().sha256)
        assertEquals("A second example app, now faster", viaDiff.description)
        assertEquals(fresh.name, viaDiff.name)
    }

    @Test
    fun aDiffThatLeavesTheAppAloneMeansUnchanged() {
        val source = FDroidRepoSource()
        val validators = InMemoryValidatorStore()
        source.check(spec("org.example.one"), CheckContext(first(), validators))

        val http = next()
        assertEquals(CheckResult.Unchanged, source.check(spec("org.example.one"), CheckContext(http, validators)))
        assertEquals(listOf(entryUrl, diffUrl), http.requests.map { it.url })

        val later = next()
        assertEquals(CheckResult.Unchanged, source.check(spec("org.example.one"), CheckContext(later, validators)))
        assertEquals("the new timestamp was remembered, so nothing but the entry is asked for", listOf(entryUrl), later.requests.map { it.url })
    }

    @Test
    fun aDiffThatDoesNotMatchItsSignedHashIsRefusedAndForgotten() {
        val source = FDroidRepoSource()
        val validators = InMemoryValidatorStore()
        source.check(spec("org.example.two"), CheckContext(first(), validators))

        val refused = assertThrows(SourceException::class.java) {
            source.check(spec("org.example.two"), CheckContext(next(entry = "entry-next-wrong-diff-hash.jar"), validators))
        }
        assertEquals(SourceErrorKind.PARSE, refused.kind)

        val http = next()
        assertEquals(listOf("0.6"), listing(source.check(spec("org.example.two"), CheckContext(http, validators))).releases.map { it.version })
        assertEquals(listOf(entryUrl, diffUrl), http.requests.map { it.url })
    }

    @Test
    fun anAppTheRepositoryDroppedIsReportedAsGone() {
        val source = FDroidRepoSource()
        val validators = InMemoryValidatorStore()
        source.check(spec("org.example.two"), CheckContext(first(), validators))
        val gone = assertThrows(SourceException::class.java) {
            source.check(spec("org.example.two"), CheckContext(next(entry = "entry-next-removed.jar", diff = "diff/removed.json", diffAt = "$repoUrl/diff/removed.json"), validators))
        }
        assertEquals(SourceErrorKind.NOT_FOUND, gone.kind)
    }

    @Test
    fun withoutADiffForWhatWeHoldTheWholeIndexIsRead() {
        val source = FDroidRepoSource()
        val validators = InMemoryValidatorStore()
        val older = FakeHttp().bytes(entryUrl, fixture("entry-older.jar")).bytes(indexUrl, fixture("index-v2.json"))
        source.check(spec("org.example.two"), CheckContext(older, validators))

        val http = next()
        assertEquals(listOf("0.6"), listing(source.check(spec("org.example.two"), CheckContext(http, validators))).releases.map { it.version })
        assertEquals(listOf(entryUrl, nextIndexUrl), http.requests.map { it.url })
    }

    @Test
    fun severalAppsOfOneRepositoryShareOneDownload() {
        val source = FDroidRepoSource { setOf("org.example.one", "org.example.two") }
        val http = first()
        val device = io.github.munzzyy.stamp.core.model.DeviceProfile.ARM64_PHONE.copy(sdk = 120)
        assertEquals(1, listing(source.check(spec("org.example.two"), CheckContext(http, InMemoryValidatorStore(), device = device))).releases.size)
        assertTrue(listing(source.check(spec("org.example.one"), CheckContext(http, InMemoryValidatorStore(), device = device))).releases.isNotEmpty())
        assertEquals(1, http.requestsTo(indexUrl).size)
        assertEquals(2, http.requestsTo(entryUrl).size)
    }

    @Test
    fun theSameDiffIsNotFetchedOncePerApp() {
        val source = FDroidRepoSource { setOf("org.example.one", "org.example.two") }
        val one = InMemoryValidatorStore()
        val two = InMemoryValidatorStore()
        val start = first()
        source.check(spec("org.example.one"), CheckContext(start, one))
        source.check(spec("org.example.two"), CheckContext(start, two))

        val http = next()
        assertEquals(listOf("0.6"), listing(source.check(spec("org.example.two"), CheckContext(http, two))).releases.map { it.version })
        assertEquals(CheckResult.Unchanged, source.check(spec("org.example.one"), CheckContext(http, one)))
        assertEquals(1, http.requestsTo(diffUrl).size)
    }

    @Test
    fun anEntryThatChangedWithoutANewIndexCostsNothingMore() {
        val source = FDroidRepoSource()
        val validators = InMemoryValidatorStore()
        source.check(spec("org.example.two"), CheckContext(first(), validators))
        val http = first("entry-manifest-last.jar")
        assertEquals(CheckResult.Unchanged, source.check(spec("org.example.two"), CheckContext(http, validators)))
        assertEquals(listOf(entryUrl), http.requests.map { it.url })
    }
}
