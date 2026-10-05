package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.install.Downloader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    @Test
    fun aFetchUnderWayKeepsItsAppsFilesThroughTheSweep() = runBlocking {
        val url = "${FakeForge.FILES}v1.0/app-v1.apk"
        val forge = FakeForge().also { it.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk"))))) }
        val reached = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val http = Routes(forge).on(url) { request ->
            reached.countDown()
            letGo.await(30, TimeUnit.SECONDS)
            forge.execute(request)
        }
        Harness("sweep-held", http = http).use { h ->
            val id = h.addFixture()
            h.engine.check(id)
            val unlisted = File(h.engine.downloader.folder(id), Downloader.baseOf(Downloader.key("v0.8", url)) + ".bin").apply {
                parentFile?.mkdirs()
                writeText("old")
            }
            val dropped = File(h.engine.downloader.folder("gone"), "leftover.bin").apply {
                parentFile?.mkdirs()
                writeText("old")
            }
            val fetching = async(Dispatchers.IO) { h.engine.downloader.fetch(id, Downloader.key("v0.9", url), url, null) { _, _ -> } }
            try {
                assertTrue("the fetch never reached the server", reached.await(10, TimeUnit.SECONDS))
                h.engine.check(null)
                waitUntil(10_000, "the sweep after the check") { !dropped.exists() }
            } finally {
                letGo.countDown()
            }
            // A fetch cannot end while a sweep runs, so the sweep is over once it has.
            val got = fetching.await()
            assertTrue(unlisted.isFile)
            assertTrue(got.file.isFile)
        }
    }
}
