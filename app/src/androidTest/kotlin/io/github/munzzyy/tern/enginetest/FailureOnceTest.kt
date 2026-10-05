package io.github.munzzyy.tern.enginetest

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.work.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A check that fails the same way at every run is logged once and notified once, while its row keeps showing it. */
@RunWith(AndroidJUnit4::class)
class FailureOnceTest {
    private val notifications = targetContext.getSystemService(NotificationManager::class.java)

    private fun failuresShown() = notifications.activeNotifications.any { it.id == Notifier.ID_FAILURES }

    @Before
    fun setUp() = prepareDevice()

    @After
    fun tearDown() = notifications.cancelAll()

    @Test
    fun aFailureThatRepeatsIsLoggedAndNotifiedOnce() = runBlocking {
        Harness("failure-once").use { h ->
            h.engine.saveSettings(h.engine.settings.value.copy(notifyFailures = true))
            val id = h.addFixture(id = "gone", project = "${FakeForge.BASE}/example/gone")
            val asked = { h.forge.requests.count { "/repos/example/gone/" in it.url } }

            h.engine.runScheduledCheck()
            waitUntil(10_000, "the failure to be notified") { failuresShown() }
            assertEquals(h.describe(id), ProblemKind.NOT_FOUND, h.state(id).checkProblem?.kind)
            notifications.cancel(Notifier.ID_FAILURES)
            waitUntil(10_000, "the notification to go") { !failuresShown() }
            val before = asked()

            h.engine.runScheduledCheck()
            Thread.sleep(2_000)

            assertTrue("the second run did not check the app", asked() > before)
            assertEquals(h.describe(id), ProblemKind.NOT_FOUND, h.row(id).problem?.kind)
            assertFalse("the same failure was notified again", failuresShown())
            assertEquals(h.describe(id), 1, h.eventsFor(id).count { it.kind == EventKind.CHECK_FAILED })

            val other = h.addFixture(id = "gone-too", project = "${FakeForge.BASE}/example/gone-too")
            h.engine.runScheduledCheck()
            waitUntil(10_000, "a new failure to be notified") { failuresShown() }
            assertEquals(h.describe(id), 1, h.eventsFor(id).count { it.kind == EventKind.CHECK_FAILED })
            assertEquals(h.describe(other), 1, h.eventsFor(other).count { it.kind == EventKind.CHECK_FAILED })
        }
    }
}
