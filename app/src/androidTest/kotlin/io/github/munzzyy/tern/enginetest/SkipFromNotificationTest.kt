package io.github.munzzyy.tern.enginetest

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.work.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Skip on a notification about one update skips the release it named, and never one that came after. */
@RunWith(AndroidJUnit4::class)
class SkipFromNotificationTest {
    private val v1 = FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk"))))
    private val v2 = FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", asset("apk/app-v2.apk"))))
    private val v3 = FakeForge.Release("v3.0", listOf(FakeForge.File("app-v3.apk", asset("apk/app-v2.apk"))))
    private val notifications = targetContext.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        prepareDevice()
        uninstallFixture()
        assertTrue(shellInstall(asset("apk/app-v1.apk")).contains("Success"))
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
        uninstallFixture()
    }

    @Test
    fun aSkipSkipsTheReleaseItWasAboutAndTakesTheNotificationAway() = runBlocking {
        Harness("skip-offered").use { h ->
            h.forge.releases = listOf(v2, v1)
            val id = h.addFixture()
            h.engine.check(id)
            val offered = h.engine.offered(id)
            assertEquals(h.describe(id), "v2.0", offered?.releaseId)
            h.engine.notifier.updates(listOf(offered!!))
            waitUntil(10_000, "the notification about the update") { notifications.activeNotifications.any { it.id == Notifier.ID_UPDATES } }

            h.engine.skipRelease(id, "v2.0")

            assertEquals(h.describe(id), "v2.0", h.row(id).config.releases.skippedReleaseId)
            assertNotEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, h.row(id).status)
            waitUntil(10_000, "the notification to go") { notifications.activeNotifications.none { it.id == Notifier.ID_UPDATES } }
        }
    }

    @Test
    fun aSkipAboutAnOlderReleaseChangesNothingOnceANewerOneIsOffered() = runBlocking {
        Harness("skip-stale").use { h ->
            h.forge.releases = listOf(v2, v1)
            val id = h.addFixture()
            h.engine.check(id)
            assertEquals(h.describe(id), "v2.0", h.row(id).latest?.id)
            h.forge.releases = listOf(v3, v2, v1)
            h.engine.check(id)
            assertEquals(h.describe(id), "v3.0", h.row(id).latest?.id)

            h.engine.skipRelease(id, "v2.0")

            assertNull(h.describe(id), h.row(id).config.releases.skippedReleaseId)
            assertEquals(h.describe(id), "v3.0", h.row(id).latest?.id)
        }
    }
}
