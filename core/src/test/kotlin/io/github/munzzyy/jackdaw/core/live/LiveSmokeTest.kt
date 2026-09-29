package io.github.munzzyy.jackdaw.core.live

import io.github.munzzyy.jackdaw.core.apk.ApkInspector
import io.github.munzzyy.jackdaw.core.apk.HttpRangeSource
import io.github.munzzyy.jackdaw.core.engine.Inspection
import io.github.munzzyy.jackdaw.core.model.AssetPolicy
import io.github.munzzyy.jackdaw.core.model.DeviceProfile
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.InMemoryValidatorStore
import io.github.munzzyy.jackdaw.core.net.PoliteHttp
import io.github.munzzyy.jackdaw.core.net.RateLimiter
import io.github.munzzyy.jackdaw.core.notes.ReleaseNotes
import io.github.munzzyy.jackdaw.core.select.AssetPicker
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceRegistry
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidSource
import io.github.munzzyy.jackdaw.core.source.forge.ForgejoSource
import io.github.munzzyy.jackdaw.core.source.forge.GitHubSource
import io.github.munzzyy.jackdaw.core.source.forge.GitLabSource
import io.github.munzzyy.jackdaw.core.verify.Checksums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Talks to the real services. Off unless asked for: ./gradlew :core:test -Djackdaw.live=true
 * It reads public data only and sends no credentials.
 */
class LiveSmokeTest {
    private val raw = JvmHttp()
    private val http = PoliteHttp(raw, RateLimiter(), "Jackdaw-live-test/0.1")
    private val registry = SourceRegistry(listOf(GitHubSource(), GitLabSource(), ForgejoSource(), FDroidSource(), FDroidRepoSource()))
    private val phone = DeviceProfile.ARM64_PHONE

    @Before
    fun onlyWhenAsked() {
        assumeTrue("live tests are off", System.getProperty("jackdaw.live") == "true")
    }

    private fun listing(result: CheckResult) = (result as CheckResult.Listing).listing

    @Test
    fun followsAGitHubProjectAndReadsItsFileWithoutDownloadingIt() {
        val spec = registry.detect("github.com/munzzyy/magpie/releases", CheckContext(http, InMemoryValidatorStore()))!!
        assertEquals("https://github.com/munzzyy/magpie", spec.url)

        val validators = InMemoryValidatorStore()
        val first = listing(registry.check(spec, CheckContext(http, validators)))
        val release = first.releases.first()
        val pick = AssetPicker.rank(release.assets, phone, AssetPolicy()).first()
        println("github: ${first.releases.size} releases, newest ${release.id}, picked ${pick.asset.name} (${pick.asset.size} bytes) because ${pick.reasons}")
        assertNotNull("GitHub gives each asset a digest", pick.asset.sha256)
        assertEquals(pick.asset.sha256, Checksums.expectedFor(release, pick.asset) { error("no fetch needed") })
        assertTrue(ReleaseNotes.parse(release.notes.orEmpty(), release.notesFormat).isNotEmpty())

        val apiCallsBefore = raw.requests.count { it.url.contains("api.github.com") }
        assertEquals(CheckResult.Unchanged, registry.check(spec, CheckContext(http, validators)))
        assertEquals("an unchanged feed costs no API call", apiCallsBefore, raw.requests.count { it.url.contains("api.github.com") })

        HttpRangeSource(http, pick.asset.url).use { source ->
            val info = ApkInspector.inspect(source)
            val inspection = Inspection.of(info, phone.sdk)
            println("inspected ${inspection.packageName} code ${inspection.versionCode} name ${inspection.versionName} signers ${inspection.signers} in ${source.requestCount} requests, ${source.bytesFetched} of ${source.size} bytes")
            assertEquals("io.github.munzzyy.magpie", inspection.packageName)
            assertEquals(1, inspection.signers.size)
            assertTrue(source.requestCount <= 8)
            assertTrue(source.bytesFetched < 400 * 1024)
            assertEquals(pick.asset.size, source.size)
        }
    }

    @Test
    fun readsFDroidAndInspectsItsFile() {
        val spec = registry.match("https://f-droid.org/en/packages/org.fdroid.fdroid/")!!
        val found = listing(registry.check(spec, CheckContext(http, InMemoryValidatorStore(), device = phone)))
        val stable = found.releases.first { !it.prerelease }
        println("f-droid: ${found.releases.size} versions, suggested ${stable.version} (${stable.versionCode})")
        val info = ApkInspector.inspectRemote(http, stable.assets.single().url)
        assertEquals("org.fdroid.fdroid", info.manifest.packageName)
        assertEquals(stable.versionCode, info.manifest.versionCode)
    }

    @Test
    fun verifiesTheSignedIndexOfTheMainFDroidRepository() {
        val known = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
        val spec = SourceSpec("fdroid-repo", "https://f-droid.org/repo", mapOf(SourceOptions.PACKAGE to "org.fdroid.fdroid", SourceOptions.FINGERPRINT to known))
        val before = raw.bytesReceived
        val started = System.nanoTime()
        val found = listing(registry.check(spec, CheckContext(http, InMemoryValidatorStore(), device = phone)))
        val newest = found.releases.first()
        println("f-droid index: ${found.name}, ${found.releases.size} versions, newest ${newest.version}, sha256 ${newest.assets.single().sha256}, signer ${newest.assets.single().signers}, ${(raw.bytesReceived - before) / 1024} KiB in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals("org.fdroid.fdroid", found.packageName)
        assertEquals(64, newest.assets.single().sha256!!.length)
        assertTrue(newest.assets.single().signers.isNotEmpty())

        val wrong = spec.copy(options = spec.options + (SourceOptions.FINGERPRINT to "0".repeat(64)))
        val refused = runCatching { registry.check(wrong, CheckContext(http, InMemoryValidatorStore())) }.exceptionOrNull()
        assertNotNull("a wrong fingerprint must be refused", refused)
        println("wrong fingerprint refused: ${refused!!.message}")
    }

    @Test
    fun recognisesADownloadLinkThatDoesNotNameItsFile() {
        val standard = SourceRegistry.standard()
        val address = "https://telegram.org/dl/android/apk"
        val before = raw.bytesReceived
        val spec = standard.detect(address, CheckContext(http, InMemoryValidatorStore()))!!
        assertEquals("direct", spec.type)
        val found = listing(standard.check(spec, CheckContext(http, InMemoryValidatorStore())))
        val asset = found.releases.single().assets.single()
        println("direct link: ${asset.name}, ${asset.size} bytes, kind ${asset.kind}, release id ${found.releases.single().id.take(40)}, detection and check cost ${raw.bytesReceived - before} bytes")
        assertTrue("detection must not download the file", raw.bytesReceived - before < 64 * 1024)
        val info = ApkInspector.inspectRemote(http, asset.url)
        println("direct link holds ${info.manifest.packageName} code ${info.manifest.versionCode} name ${info.manifest.versionName}, abis ${info.manifest.nativeLibraryAbis}")
        assertEquals("org.telegram.messenger.web", info.manifest.packageName)
    }

    @Test
    fun readsForgejoAndGitLab() {
        val forgejo = listing(registry.check(registry.match("https://codeberg.org/forgejo/forgejo")!!, CheckContext(http, InMemoryValidatorStore())))
        println("codeberg: ${forgejo.releases.size} releases, newest ${forgejo.releases.first().id}, ${forgejo.releases.first().assets.size} files, published ${forgejo.releases.first().publishedAtMs}")
        assertNotNull(forgejo.releases.first().publishedAtMs)

        val gitlab = listing(registry.check(registry.match("https://gitlab.com/fdroid/fdroidclient")!!, CheckContext(http, InMemoryValidatorStore())))
        println("gitlab: ${gitlab.releases.size} releases, newest ${gitlab.releases.first().id}, ${gitlab.releases.first().assets.size} files")
        assertTrue(gitlab.releases.isNotEmpty())
    }
}
