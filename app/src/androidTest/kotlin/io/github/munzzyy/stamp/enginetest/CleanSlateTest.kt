package io.github.munzzyy.stamp.enginetest

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.Phase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanSlateTest {
    @After
    fun tearDown() {
        prepareDevice()
        uninstallFixture()
    }

    @Test
    fun whatARunThatStoppedHalfwayLeftBehindIsGoneBeforeTheNextOne() = runBlocking {
        prepareDevice()
        uninstallFixture()
        val installer = targetContext.packageManager.packageInstaller
        val notifications = targetContext.getSystemService(NotificationManager::class.java)
        Harness("halfway").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(30_000, "the install to wait for the user") { h.row(id).progress?.phase == Phase.WAITING_FOR_USER }
            waitUntil(10_000, "the notification to be posted") { notifications.activeNotifications.isNotEmpty() }
        }
        assertTrue("the run left no session to clean up", installer.mySessions.isNotEmpty())
        assertTrue("the run left no notification to clean up", notifications.activeNotifications.isNotEmpty())

        prepareDevice()

        assertEquals(emptyList<Int>(), installer.mySessions.map { it.sessionId })
        assertEquals(emptyList<Int>(), notifications.activeNotifications.map { it.id })
    }
}
