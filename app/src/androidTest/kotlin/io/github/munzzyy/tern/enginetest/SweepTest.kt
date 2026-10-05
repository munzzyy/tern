package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.install.Downloader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What a check of the whole list clears out of the downloads, and what it leaves. */
@RunWith(AndroidJUnit4::class)
class SweepTest {
    @Test
    fun aWholeListCheckClearsWhatNoListedReleaseNamesAndKeepsWhatOneDoes() = runBlocking {
        Harness("sweep").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            h.engine.check(id)
            val release = h.row(id).latest ?: error("No release after the check: ${h.describe(id)}")
            val url = release.assets.single().url
            val key = Downloader.key(release.id, url)
            h.engine.downloader.fetch(id, key, url, null) { _, _ -> }
            val kept = h.engine.downloader.kept(id, key) ?: error("The downloaded file was not kept")
            val unlisted = File(h.engine.downloader.folder(id), Downloader.baseOf(Downloader.key("v0.9", url)) + ".bin").apply { writeText("old") }
            val dropped = File(h.engine.downloader.folder("gone"), "leftover.bin").apply {
                parentFile?.mkdirs()
                writeText("old")
            }

            h.engine.check(null)
            waitUntil(10_000, "the sweep after the check") { !unlisted.exists() && !dropped.exists() }
            assertTrue(kept.isFile)
        }
    }
}
