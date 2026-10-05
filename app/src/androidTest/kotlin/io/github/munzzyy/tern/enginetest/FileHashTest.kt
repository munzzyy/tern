package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.verify.Fingerprints
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.real.Device
import io.github.munzzyy.tern.engine.real.Evaluator
import io.github.munzzyy.tern.engine.real.Texts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileHashTest {
    @After
    fun tearDown() {
        Prompt.dismiss()
        uninstallFixture()
    }

    @Test
    fun thePublishersDigestIsOfferedBeforeDownload() {
        val evaluator = Evaluator(Texts(targetContext), Device(targetContext).profile) { 0L }
        val config = AppConfig("a", SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), "App")
        val digest = "AB".repeat(32)
        val published = Asset("app.apk", "https://github.com/example/app/releases/download/v1/app.apk", 10, sha256 = digest)
        val release = Release("v1", "1", assets = listOf(published))
        assertEquals(digest.lowercase(), evaluator.verification(config, AppState(), release, published, null, null).fileSha256)
        val bare = published.copy(sha256 = "not a digest")
        assertNull(evaluator.verification(config, AppState(), release.copy(assets = listOf(bare)), bare, null, null).fileSha256)
    }

    @Test
    fun theChecksumFileToBeReadIsNamedBeforeDownload() {
        val texts = Texts(targetContext)
        val evaluator = Evaluator(texts, Device(targetContext).profile) { 0L }
        val config = AppConfig("a", SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), "App")
        val apk = Asset("app.apk", "https://github.com/example/app/releases/download/v1/app.apk", 10)
        fun release(vararg sums: String) = Release("v1", "1", assets = listOf(apk) + sums.map { Asset(it, "https://github.com/example/app/releases/download/v1/$it") })
        assertEquals(texts.checksumFile("app.apk.sha256.txt"), evaluator.expectedSourceLocally(config, release("SHA256SUMS", "app.apk.sha256.txt"), apk))
        assertEquals(texts.checksumFile("SHA256SUMS-all.txt"), evaluator.expectedSourceLocally(config, release("SHA256SUMS-all.txt"), apk))
        assertNull(evaluator.expectedSourceLocally(config, release("checksums-sha512.txt"), apk))
    }

    @Test
    fun theMeasuredHashReplacesItOnceTheFileIsDownloaded() = runBlocking {
        prepareDevice()
        uninstallFixture()
        Harness("filehash").use { h ->
            val bytes = asset("apk/app-v1.apk")
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", bytes))))
            val id = h.addFixture()
            h.engine.check(id)
            assertNull(h.row(id).verification?.fileSha256)
            h.engine.install(id)
            waitUntil(30_000, "the install to wait for the user") { h.row(id).progress?.phase == Phase.WAITING_FOR_USER }
            assertEquals(Fingerprints.sha256(bytes), h.row(id).verification?.fileSha256)
            h.engine.cancel(id)
        }
    }
}
