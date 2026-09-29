package io.github.munzzyy.stamp.core.source.fdroid

import io.github.munzzyy.stamp.core.model.DeviceProfile
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.Headers
import io.github.munzzyy.stamp.core.net.HttpResponse
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceOptions
import io.github.munzzyy.stamp.core.testing.FakeHttp
import io.github.munzzyy.stamp.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FDroidRepoSourceTest {
    private val source = FDroidRepoSource()
    private val repoUrl = "https://example.com/fdroid/repo"
    private val entryJarUrl = "$repoUrl/entry.jar"
    private val indexUrl = "$repoUrl/index-v2.json"
    private val fingerprint = Fixtures.text("fdroid/repo/fingerprint.txt").trim()

    private fun entryJarBytes() = Fixtures.bytes("fdroid/repo/entry.jar")
    private fun indexBytes() = Fixtures.bytes("fdroid/repo/index-v2.json")

    private fun goodHttp(): FakeHttp = FakeHttp()
        .bytes(entryJarUrl, entryJarBytes())
        .bytes(indexUrl, indexBytes())

    @Test
    fun matchesRepoUrlAndFingerprintQueryParam() {
        val spec = source.match("$repoUrl?fingerprint=${fingerprint.uppercase()}")
        assertEquals(repoUrl, spec?.url)
        assertEquals(fingerprint, spec?.option(SourceOptions.FINGERPRINT))
    }

    @Test
    fun matchesFdroidrepoScheme() {
        val spec = source.match("fdroidrepos://example.com/fdroid/repo")
        assertEquals(repoUrl, spec?.url)
    }

    @Test
    fun doesNotMatchUrlWithoutRepoPath() {
        assertNull(source.match("https://example.com/some/page"))
    }

    @Test
    fun firstContactLearnsFingerprint() {
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two"))
        val result = source.check(spec, CheckContext(goodHttp(), InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(fingerprint, listing.learnedOptions[SourceOptions.FINGERPRINT])
        assertEquals(1, listing.releases.size)
        assertEquals("0.5", listing.releases[0].version)
    }

    @Test
    fun matchingPinnedFingerprintSucceedsWithNoLearnedOptions() {
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two", SourceOptions.FINGERPRINT to fingerprint))
        val result = source.check(spec, CheckContext(goodHttp(), InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertTrue(listing.learnedOptions.isEmpty())
    }

    @Test
    fun wrongPinnedFingerprintThrowsAuth() {
        val spec = SourceSpec(
            source.type,
            repoUrl,
            mapOf(SourceOptions.PACKAGE to "org.example.two", SourceOptions.FINGERPRINT to "0".repeat(64)),
        )
        try {
            source.check(spec, CheckContext(goodHttp(), InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.AUTH, e.kind)
        }
    }

    @Test
    fun jarSignedByDifferentKeyThrowsAuthWhenFingerprintPinned() {
        val http = FakeHttp().bytes(entryJarUrl, Fixtures.bytes("fdroid/repo/entry-rekeyed.jar")).bytes(indexUrl, indexBytes())
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two", SourceOptions.FINGERPRINT to fingerprint))
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.AUTH, e.kind)
        }
    }

    @Test
    fun tamperedEntryJsonThrowsAuth() {
        val http = FakeHttp().bytes(entryJarUrl, Fixtures.bytes("fdroid/repo/entry-tampered.jar")).bytes(indexUrl, indexBytes())
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two"))
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.AUTH, e.kind)
        }
    }

    @Test
    fun indexHashMismatchThrowsParse() {
        val corrupted = indexBytes().copyOf()
        corrupted[corrupted.size - 2] = (corrupted[corrupted.size - 2] + 1).toByte()
        val http = FakeHttp().bytes(entryJarUrl, entryJarBytes()).bytes(indexUrl, corrupted)
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two"))
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun packageMissingThrowsNotFound() {
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.three"))
        try {
            source.check(spec, CheckContext(goodHttp(), InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun missingPackageOptionIsUnsupported() {
        val spec = SourceSpec(source.type, repoUrl)
        try {
            source.check(spec, CheckContext(goodHttp(), InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun returnsAllVersionsWithoutDeviceFilter() {
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.one"))
        val result = source.check(spec, CheckContext(goodHttp(), InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(4, listing.releases.size)
        assertEquals(30L, listing.releases[0].versionCode)
        assertTrue(listing.releases.any { it.prerelease })
    }

    @Test
    fun filtersBySplitNativecodeAndMinSdkForDevice() {
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.one"))
        val context = CheckContext(goodHttp(), InMemoryValidatorStore(), device = DeviceProfile.X86_64_EMULATOR)
        val result = source.check(spec, context)
        val listing = (result as CheckResult.Listing).listing
        assertEquals(2, listing.releases.size)
        assertEquals(20L, listing.releases[0].versionCode)
        assertEquals(10L, listing.releases[1].versionCode)
        assertTrue(listing.releases[1].assets[0].name.contains("arm64"))
    }

    @Test
    fun notModifiedOnEntryJarReturnsUnchanged() {
        val http = FakeHttp().on(entryJarUrl) { HttpResponse.of(304, "", Headers.EMPTY, entryJarUrl) }
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two"))
        val result = source.check(spec, CheckContext(http, InMemoryValidatorStore()))
        assertTrue(result is CheckResult.Unchanged)
    }

    @Test
    fun fallsBackToIndexV1WhenEntryJarMissing() {
        val v1Url = "$repoUrl/index-v1.jar"
        val http = FakeHttp()
            .on(entryJarUrl) { HttpResponse.of(404, "", Headers.EMPTY, entryJarUrl) }
            .bytes(v1Url, Fixtures.bytes("fdroid/repo-v1/index-v1.jar"))
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.one"))
        val result = source.check(spec, CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(1, listing.releases.size)
        assertEquals("1.0", listing.releases[0].version)
        assertEquals(fingerprint, listing.learnedOptions[SourceOptions.FINGERPRINT])
    }

    @Test
    fun theOlderIndexIsReadByPackageAndAnotherPackageIsLeftAlone() {
        val v1Url = "$repoUrl/index-v1.jar"
        val http = FakeHttp()
            .on(entryJarUrl) { HttpResponse.of(404, "", Headers.EMPTY, entryJarUrl) }
            .bytes(v1Url, Fixtures.bytes("fdroid/repo-v1/index-v1.jar"))
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.two"))
        val listing = (source.check(spec, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing
        assertEquals(listOf("9.0"), listing.releases.map { it.version })
        assertEquals(listOf("org.example.two_90.apk"), listing.releases.flatMap { it.assets }.map { it.name })
    }

    @Test
    fun anIndexUnderASha1SignatureIsRefusedAndSaysWhy() {
        val v1Url = "$repoUrl/index-v1.jar"
        val http = FakeHttp()
            .on(entryJarUrl) { HttpResponse.of(404, "", Headers.EMPTY, entryJarUrl) }
            .bytes(v1Url, Fixtures.bytes("fdroid/repo-v1/index-v1-sha1.jar"))
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.one"))
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
            fail("an index signed with SHA-1 was accepted")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
            assertTrue(e.message.orEmpty().contains("SHA-1"))
        }
    }

    @Test
    fun onlySha256AndStrongerCountAsADigest() {
        assertTrue(strongDigest(listOf("SHA-256-Digest")))
        assertTrue(strongDigest(listOf("SHA1-Digest", "SHA-256-Digest")))
        assertTrue(strongDigest(listOf("SHA-512-Digest")))
        assertTrue(strongDigest(emptyList()))
        assertTrue(strongDigest(listOf("Name")))
        assertEquals(false, strongDigest(listOf("SHA1-Digest")))
        assertEquals(false, strongDigest(listOf("SHA-1-Digest", "MD5-Digest")))
    }

    @Test
    fun noEntryJarOrIndexV1JarThrowsNotFound() {
        val v1Url = "$repoUrl/index-v1.jar"
        val http = FakeHttp()
            .on(entryJarUrl) { HttpResponse.of(404, "", Headers.EMPTY, entryJarUrl) }
            .on(v1Url) { HttpResponse.of(404, "", Headers.EMPTY, v1Url) }
        val spec = SourceSpec(source.type, repoUrl, mapOf(SourceOptions.PACKAGE to "org.example.one"))
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }
}
