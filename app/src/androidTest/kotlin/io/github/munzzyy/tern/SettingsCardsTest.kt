package io.github.munzzyy.tern

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.OrbotState
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.tern.ui.settings.ORBOT_TAG
import io.github.munzzyy.tern.ui.settings.ORBOT_URL
import io.github.munzzyy.tern.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The row about Orbot, which is there while the proxy is set to Orbot. */
@RunWith(AndroidJUnit4::class)
class OrbotRowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val inRow = hasAnyAncestor(hasTestTag(ORBOT_TAG))
    private val note = "While Orbot is chosen and not running, Tern reaches nothing. It never goes round the proxy."

    private fun names() = fake.apps.value.map { it.config.name }

    private fun chooseOrbot() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Proxy").performScrollTo().performClick()
        compose.onNodeWithText("Orbot").performClick()
        compose.waitUntil(3_000) { fake.settings.value.proxy == ProxyMode.ORBOT }
        compose.waitFor(hasTestTag(ORBOT_TAG))
        compose.tagged(ORBOT_TAG).performScrollTo()
    }

    private fun says(sentence: String) {
        compose.waitFor(hasText(sentence) and inRow)
        compose.onNode(hasText(sentence) and inRow).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(note) and inRow).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRowIsThereOnlyWhileTheProxyIsOrbot() {
        launch("default").use {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Proxy").performScrollTo()
            assertEquals(0, compose.onAllNodes(hasTestTag(ORBOT_TAG)).fetchSemanticsNodes().size)
            compose.waitForIdle()
            assertEquals("nobody asks Orbot while it is not chosen", 0, fake.orbotAsked)
            assertEquals(OrbotState.UNKNOWN, fake.orbot.value)
        }
    }

    @Test
    fun orbotIsAskedOnceWhenTheRowComesOnScreenAndItsAnswerIsSaid() {
        launch("default").use {
            fake.orbotAnswer = OrbotState.ON
            chooseOrbot()
            says("Orbot is running. Everything Tern sends goes through Tor.")
            assertEquals(OrbotState.ON, fake.orbot.value)
            compose.onNodeWithText("Access tokens").performScrollTo()
            compose.tagged(ORBOT_TAG).performScrollTo()
            compose.waitForIdle()
            assertEquals("Orbot is asked once when the row comes on screen", 1, fake.orbotAsked)
            assertEquals(0, compose.onAllNodes(hasText("Open Orbot") or hasText("Get Orbot") or hasText("Ask again")).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun orbotThatIsOffIsOpenedAndAskedAgainOnReturn() {
        launch("default").use { scenario ->
            fake.orbotAnswer = OrbotState.OFF
            chooseOrbot()
            says("Orbot is installed and not connected.")
            compose.onNode(hasText("Open Orbot") and inRow).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("the button opens Orbot", 1, fake.orbotOpened)
            says("Orbot is installed and not connected.")

            val asked = fake.orbotAsked
            fake.orbotAnswer = OrbotState.ON
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            says("Orbot is running. Everything Tern sends goes through Tor.")
            assertEquals("Orbot is asked again when Tern comes back", asked + 1, fake.orbotAsked)
        }
    }

    @Test
    fun orbotThatHasNotAnsweredCanBeAskedAgain() {
        launch("default").use {
            fake.orbotAnswer = OrbotState.UNKNOWN
            chooseOrbot()
            says("Orbot has not answered yet.")
            fake.orbotAnswer = OrbotState.STARTING
            compose.onNode(hasText("Ask again") and inRow).performScrollTo().performClick()
            says("Orbot is starting.")
        }
    }

    @Test
    fun orbotThatIsMissingIsLookedAtLikeAnyLinkAndNothingIsAdded() {
        launch("default").use {
            val before = names()
            fake.orbotAnswer = OrbotState.NOT_INSTALLED
            chooseOrbot()
            says("Orbot is not on this device.")
            compose.onNode(hasText("Get Orbot") and inRow).performScrollTo().performClick()
            compose.waitFor(hasTestTag(ADD_FIELD_TAG))
            compose.tagged(ADD_FIELD_TAG).assertTextContains(ORBOT_URL)
            compose.waitForIdle()
            assertEquals(before, names())

            device.pressBack()
            compose.waitFor(hasTestTag(ORBOT_TAG))
        }
    }
}

/** Settings by itself, as a television and as a phone draw it. */
@RunWith(AndroidJUnit4::class)
class SettingsRowsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val notificationRows = listOf("Notifications", "When updates are found", "When an update is installed", "When a check fails", "Name the apps in notifications", "Android notification settings")
    private val sections = listOf("Background checks", "Defaults for new apps", "Installing", "Access tokens", "Network", "Appearance", "Import and export", "About")

    private fun show(television: Boolean) {
        fake.loadScenario("default")
        compose.host(television = television) { SettingsScreen(onImport = {}, onLook = {}) }
        compose.waitForText("How often")
    }

    @Test
    fun aTelevisionShowsNoRowsAboutNotifications() {
        show(television = true)
        for (row in notificationRows) assertEquals(row, 0, compose.textCount(row))
        for (section in sections) compose.onNodeWithText(section).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPhoneShowsThemInTheOrderTheSectionsAlwaysHad() {
        show(television = false)
        for (row in notificationRows) compose.onNodeWithText(row).performScrollTo().assertIsDisplayed()
        val order = listOf("Background checks", "Defaults for new apps", "Notifications", "Installing", "Access tokens", "Network", "Appearance", "Import and export", "About")
        val tops = order.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals("the sections stand in this order: $order", tops.sorted(), tops)
        assertEquals(order.size, tops.distinct().size)
    }

    @Test
    fun namesCanBeKeptOutOfNotifications() {
        show(television = false)
        assertTrue(fake.settings.value.notifyNames)
        compose.onNodeWithText("A notification can be read on the lock screen.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Name the apps in notifications").performScrollTo().performClick()
        compose.waitUntil(3_000) { !fake.settings.value.notifyNames }
    }

    @Test
    fun aSettingThisAndroidDoesNotHaveIsNotShown() {
        show(television = false)
        val shown = compose.textCount("Be the only updater")
        assertEquals(if (Build.VERSION.SDK_INT >= 34) 1 else 0, shown)
        assertEquals(0, compose.textCount("Needs Android 14 or later."))
        val language = compose.textCount("Language")
        assertTrue(language == if (Build.VERSION.SDK_INT >= 33) 1 else 0)
    }
}
