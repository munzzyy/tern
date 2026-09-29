package io.github.munzzyy.jackdaw

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.munzzyy.jackdaw.ui.add.ADD_FIND_TAG
import io.github.munzzyy.jackdaw.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.jackdaw.ui.firstrun.FIRST_RUN_ADD_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** At 200% text every screen's main action must still be on screen without scrolling. */
@RunWith(AndroidJUnit4::class)
class LargeTextTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var previous = "1.0"

    @Before
    fun bigText() {
        previous = device.executeShellCommand("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        device.executeShellCommand("settings put system font_scale 2.0")
        device.waitForIdle()
        val deadline = System.currentTimeMillis() + 10_000
        while (appContext.resources.configuration.fontScale < 1.99f && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    @After
    fun restore() {
        device.executeShellCommand("settings put system font_scale $previous")
    }

    @Test
    fun theTestRunsAtDoubleSize() {
        launch("default").use {
            assertEquals(2.0f, appContext.resources.configuration.fontScale, 0.01f)
        }
    }

    @Test
    fun appsShowsUpdateAll() {
        launch("default").use { compose.onNodeWithText("Update all").assertIsDisplayed() }
    }

    @Test
    fun detailShowsItsPrimaryAction() {
        launch("default").use {
            compose.shownRow("Harbor Terminal").performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Update"))
            compose.tagged(DETAIL_PRIMARY_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun addShowsFind() {
        launch("default").use {
            compose.onNodeWithText("Add").performClick()
            compose.tagged(ADD_FIND_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun activityShowsItsFilterAndClear() {
        launch("default").use {
            compose.onNodeWithText("Activity").performClick()
            compose.onNodeWithText("Problems only").assertIsDisplayed()
            compose.onNodeWithContentDescription("Clear activity").assertIsDisplayed()
        }
    }

    @Test
    fun settingsShowsItsFirstSetting() {
        launch("default").use {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("How often").assertIsDisplayed()
        }
    }

    @Test
    fun importShowsPickAFile() {
        launch("default").use {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Import apps").performScrollTo().performClick()
            compose.onNodeWithText("Pick a file").assertIsDisplayed()
        }
    }

    @Test
    fun firstRunShowsAddYourFirstApp() {
        launch("firstrun").use { compose.tagged(FIRST_RUN_ADD_TAG).assertIsDisplayed() }
    }
}
