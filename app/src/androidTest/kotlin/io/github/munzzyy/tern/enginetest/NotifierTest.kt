package io.github.munzzyy.tern.enginetest

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.SavedFile
import io.github.munzzyy.tern.engine.real.Texts
import io.github.munzzyy.tern.work.Installed
import io.github.munzzyy.tern.work.Notifier
import io.github.munzzyy.tern.work.Trouble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotifierTest {
    private fun all(names: Boolean): Map<String, Notification> {
        val notifier = Notifier(targetContext, Texts(targetContext)) { names }
        return mapOf(
            "one update" to notifier.updatesAbout(listOf(APP)),
            "two updates" to notifier.updatesAbout(listOf(APP, "Other")),
            "one installed" to notifier.installedAbout(listOf(Installed("a", APP, null))),
            "one updated to a version" to notifier.installedAbout(listOf(Installed("a", APP, "2.0"))),
            "one installed at a version" to notifier.installedAbout(listOf(Installed("a", APP, "2.0", update = false))),
            "two installed" to notifier.installedAbout(listOf(Installed("a", APP, "2.0"), Installed("b", "Other", "1.0"))),
            "failures" to notifier.failuresAbout(listOf(Trouble("a", APP, "The server did not answer."))),
            "failures of two" to notifier.failuresAbout(listOf(Trouble("a", APP, "No file."), Trouble("b", "Other", "No file."))),
            "confirm" to notifier.confirmAbout(APP),
            "transfer" to notifier.transfer(listOf(APP), 1, 2),
            "saved" to notifier.savedAbout(SavedFile("$APP-2.0.apk", "Download/Tern", "/storage/emulated/0/Download/Tern/$APP-2.0.apk", 0, 1)),
            "not saved" to notifier.notSavedAbout("$APP-2.0.apk", "The server did not answer."),
        )
    }

    private fun words(n: Notification): String =
        (n.extras.keySet().map { n.extras.get(it)?.toString().orEmpty() } + n.tickerText?.toString().orEmpty()).joinToString("\n")

    @Test
    fun whatALockScreenMayShowNamesNoApp() {
        all(names = true).forEach { (which, n) ->
            assertTrue("$which names the app where it may", APP in words(n))
            assertEquals(which, Notification.VISIBILITY_PRIVATE, n.visibility)
            val public = n.publicVersion
            assertNotNull("$which has a version for the lock screen", public)
            assertFalse("$which names the app on the lock screen: ${words(public!!)}", APP in words(public))
            assertTrue("$which says something on the lock screen", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString().isNotBlank())
        }
    }

    @Test
    fun withNamesSwitchedOffNoNotificationNamesAnApp() {
        all(names = false).forEach { (which, n) ->
            assertFalse("$which names the app: ${words(n)}", APP in words(n))
            assertNull(which, n.publicVersion)
            assertTrue(which, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().isNotBlank())
        }
    }

    @Test
    fun confirmationsWaitUnderOneSummaryThatAloneMakesASound() {
        prepareDevice()
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        val texts = Texts(targetContext)
        val notifier = Notifier(targetContext, texts) { true }
        val grouped = { manager.activeNotifications.filter { it.notification.group == Notifier.GROUP_CONFIRM } }
        val summary = { grouped().singleOrNull { it.id == Notifier.ID_CONFIRMS }?.notification }
        val apps = listOf("kestrel", "moss", "quill")
        try {
            for (app in apps) {
                notifier.confirm(app, "$APP $app", Intent(Settings.ACTION_SETTINGS))
                // Android drops updates that come faster than a few a second, and a person never confirms that fast.
                Thread.sleep(1_000)
            }
            waitUntil(10_000, "three confirmations and their summary") { grouped().size == 4 }
            val shown = summary()!!
            assertTrue((shown.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
            assertEquals("a tap on the summary leaves the confirmations", 0, shown.flags and Notification.FLAG_AUTO_CANCEL)
            assertEquals(texts.notifyConfirms(3), shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(Notification.VISIBILITY_PUBLIC, shown.visibility)
            assertFalse("the summary names an app: ${words(shown)}", APP in words(shown))
            for (child in grouped().filter { it.id != Notifier.ID_CONFIRMS }) {
                assertEquals(Notification.GROUP_ALERT_SUMMARY, child.notification.groupAlertBehavior)
                assertTrue(APP in words(child.notification))
                assertFalse("a confirmation names its app on the lock screen", APP in words(child.notification.publicVersion!!))
            }

            notifier.cancelConfirm(apps[0])
            waitUntil(10_000, "the summary to count two") { summary()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == texts.notifyConfirms(2) }
            apps.drop(1).forEach(notifier::cancelConfirm)
            waitUntil(10_000, "the summary to go with the last confirmation") { grouped().isEmpty() }
        } finally {
            manager.cancelAll()
        }
    }

    private companion object {
        const val APP = "Kestrelwort"
    }
}
