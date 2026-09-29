package io.github.munzzyy.tern

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.fake.FakeEngine
import io.github.munzzyy.tern.ui.common.PERMIT_DIALOG_TAG
import io.github.munzzyy.tern.ui.common.PreferenceWishStore
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.tern.ui.settings.PERMIT_ROW_TAG
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The permission to install apps on a device whose settings page an app cannot open, and its row in Settings. */
@RunWith(AndroidJUnit4::class)
class ManualInstallPathTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val manual = "this device has no page Tern can open for you"
    private val places = listOf(
        "Android TV and Google TV: Settings, Apps, Security and restrictions, Unknown sources",
        "Fire TV: Settings, My Fire TV, Developer options, Install unknown apps",
    )

    private fun waiting() = fake.apps.value.first { it.status == AppStatus.UPDATE_AVAILABLE && it.progress == null && it.problem == null }

    private fun started(id: String) = fake.apps.value.first { it.id == id }.let { it.progress != null || it.status == AppStatus.UP_TO_DATE }

    private fun askByInstalling(): String {
        val row = waiting()
        compose.shownRow(row.config.name).performClick()
        compose.tagged(DETAIL_PRIMARY_TAG).performClick()
        compose.waitFor(hasTestTag(PERMIT_DIALOG_TAG))
        return row.id
    }

    @After
    fun tearDown() {
        fake.installsAllowed = true
        fake.installSettings = true
    }

    @Test
    fun whereTheSettingsCannotBeOpenedTheQuestionSaysWhereToLook() {
        launch(FakeEngine.BARE).use {
            val id = askByInstalling()
            compose.onNodeWithText("Allow Tern to install apps").assertIsDisplayed()
            compose.onNodeWithText(manual, substring = true).assertIsDisplayed()
            for (place in places) compose.onNodeWithText(place).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("I have done it").assertIsDisplayed()
            compose.onNodeWithText("Not now").assertIsDisplayed()
            assertEquals(0, compose.textCount("Open settings"))
            assertEquals(0, compose.textCount("On the next screen", substring = true))
            assertNull("the install started before the answer", fake.apps.value.first { it.id == id }.progress)
        }
    }

    @Test
    fun iHaveDoneItStartsWhatWasWanted() {
        launch(FakeEngine.BARE).use {
            val id = askByInstalling()
            compose.onNodeWithText("I have done it").performClick()
            compose.waitUntil(10_000) { started(id) }
            assertEquals(0, compose.onAllNodes(hasTestTag(PERMIT_DIALOG_TAG)).fetchSemanticsNodes().size)
            assertNull("what was wanted is still written down", PreferenceWishStore(appContext).read())
        }
    }

    @Test
    fun notNowStartsNothing() {
        launch(FakeEngine.BARE).use {
            val id = askByInstalling()
            compose.onNodeWithText("Not now").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PERMIT_DIALOG_TAG)).fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            assertNull(fake.apps.value.first { it.id == id }.progress)
            assertEquals(AppStatus.UPDATE_AVAILABLE, fake.apps.value.first { it.id == id }.status)
        }
    }

    @Test
    fun whereTheSettingsCanBeOpenedTheQuestionIsTheOneItWas() {
        launch("default").use {
            fake.installsAllowed = false
            askByInstalling()
            compose.onNodeWithText("Open settings").assertIsDisplayed()
            compose.onNodeWithText("On the next screen, turn on the switch for Tern", substring = true).assertIsDisplayed()
            assertEquals(0, compose.textCount(manual, substring = true))
            assertEquals(0, compose.textCount("I have done it"))
            for (place in places) assertEquals(0, compose.textCount(place))
            compose.onNodeWithText("Not now").performClick()
        }
    }

    @Test
    fun theQuestionSaysThatAndroidWillCloseTheAppOnTheManualPathToo() {
        launch(FakeEngine.BARE).use {
            askByInstalling()
            val said = compose.textCount("closes Tern when you turn the switch on", substring = true)
            assertEquals(if (Build.VERSION.SDK_INT in 30..32) 1 else 0, said)
            compose.onNodeWithText("Not now").performClick()
        }
    }

    @Test
    fun theRowInSettingsSaysThatThePermissionIsMissingAndAsksForIt() {
        launch(FakeEngine.BARE).use {
            val busy = fake.apps.value.filter { it.progress != null }.map { it.id }
            compose.onNodeWithText("Settings").performClick()
            compose.tagged(PERMIT_ROW_TAG).performScrollTo()
            compose.onNode(hasTestTag(PERMIT_ROW_TAG) and hasText("Permission to install apps") and hasText("Not given yet.", substring = true)).assertIsDisplayed()

            compose.tagged(PERMIT_ROW_TAG).performClick()
            compose.waitFor(hasTestTag(PERMIT_DIALOG_TAG))
            compose.onNodeWithText(manual, substring = true).assertIsDisplayed()
            fake.installsAllowed = true
            compose.onNodeWithText("I have done it").performClick()
            compose.waitFor(hasTestTag(PERMIT_ROW_TAG) and hasText("Given.", substring = true))
            assertEquals(0, compose.onAllNodes(hasTestTag(PERMIT_DIALOG_TAG)).fetchSemanticsNodes().size)
            assertEquals("asking from Settings starts no install", busy, fake.apps.value.filter { it.progress != null }.map { it.id })
        }
    }

    @Test
    fun theRowInSettingsOpensAndroidsPageWhereThereIsOne() {
        launch("default").use {
            compose.onNodeWithText("Settings").performClick()
            compose.tagged(PERMIT_ROW_TAG).performScrollTo()
            compose.onNode(hasTestTag(PERMIT_ROW_TAG) and hasText("Given.", substring = true)).assertIsDisplayed()
            compose.tagged(PERMIT_ROW_TAG).performClick()
            assertNotNull("Android's own settings did not open", device.wait(Until.findObject(By.pkg(SETTINGS)), 10_000))
            device.pressBack()
            compose.waitFor(hasTestTag(PERMIT_ROW_TAG))
            assertEquals(0, compose.onAllNodes(hasTestTag(PERMIT_DIALOG_TAG)).fetchSemanticsNodes().size)
        }
    }

    private companion object {
        /** A television keeps its settings in a package of its own. */
        val SETTINGS: Pattern = Pattern.compile("com\\.android\\.(tv\\.)?settings")
    }
}
