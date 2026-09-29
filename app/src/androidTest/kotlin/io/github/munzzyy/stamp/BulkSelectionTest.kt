package io.github.munzzyy.stamp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.munzzyy.stamp.engine.Phase
import io.github.munzzyy.stamp.ui.apps.BULK_BAR_TAG
import io.github.munzzyy.stamp.ui.apps.BULK_CATEGORY_FIELD_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun bar(label: String) = compose.onNode(hasText(label) and hasAncestorTag(BULK_BAR_TAG))

    private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(tag))

    private fun app(id: String) = fake.apps.value.single { it.id == id }

    @Test
    fun aLongPressStartsSelectingAndACategoryIsFiledForEveryPickedApp() {
        launch("default").use {
            compose.shownRow("Pocket Notes").performTouchInput { longClick() }
            compose.waitForText("1 selected")
            compose.shownRow("Kestrel Mail").performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            bar("Add to category").performClick()
            compose.onNodeWithText("Add 2 apps to a category").assertIsDisplayed()
            compose.tagged(BULK_CATEGORY_FIELD_TAG).performTextReplacement("  Reading ")
            compose.onNode(hasText("Add to category") and !hasAncestorTag(BULK_BAR_TAG) and androidx.compose.ui.test.hasClickAction()).performClick()
            compose.waitUntil(5_000) { app("kestrelmail").config.categories.contains("Reading") }
            assertEquals(listOf("Writing", "Reading"), app("pocketnotes").config.categories)
            assertEquals(listOf("Reading"), app("kestrelmail").config.categories)
            assertEquals(0, compose.textCount("2 selected"))
            assertTrue(fake.apps.value.filter { it.id !in setOf("pocketnotes", "kestrelmail") }.none { "Reading" in it.config.categories })
        }
    }

    @Test
    fun updateNamesHowManyItTouchesAndLeavesTheRestAlone() {
        launch("default").use {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Select").performClick()
            compose.shownRow("Trail Map").performClick()
            compose.shownRow("Pocket Notes").performClick()
            bar("Update").performClick()
            compose.onNodeWithText("Update 1 app?").assertIsDisplayed()
            compose.onNodeWithText("1 of the picked apps has no update to install", substring = true).assertIsDisplayed()
            compose.onNode(hasText("Update") and !hasAncestorTag(BULK_BAR_TAG) and androidx.compose.ui.test.hasClickAction()).performClick()
            compose.waitUntil(5_000) { app("trailmap").progress != null }
            assertEquals(null, app("pocketnotes").progress)
            assertTrue(app("trailmap").progress?.phase in Phase.entries)
        }
    }

    @Test
    fun removeAsksWithTheCountAndBackLeavesSelecting() {
        launch("default").use {
            compose.shownRow("Loom Reader").performTouchInput { longClick() }
            compose.shownRow("Quiet Clock").performClick()
            bar("Remove").performClick()
            compose.onNodeWithText("Stop tracking 2 apps?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(2, fake.apps.value.count { it.id == "loomreader" || it.id == "quietclock" })

            bar("Remove").performClick()
            compose.onNode(hasText("Remove") and !hasAncestorTag(BULK_BAR_TAG) and androidx.compose.ui.test.hasClickAction()).performClick()
            compose.waitUntil(5_000) { fake.apps.value.none { it.id == "loomreader" || it.id == "quietclock" } }

            compose.shownRow("Pocket Notes").performTouchInput { longClick() }
            compose.waitForText("1 selected")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(5_000) { compose.textCount("1 selected") == 0 }
            compose.onNode(hasContentDescription("Check all", substring = true)).assertIsDisplayed()
        }
    }

    @Test
    fun withNothingPickedTheBarSaysWhatToDo() {
        launch("default").use {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Select").performClick()
            compose.onNodeWithText("Tap apps to pick them.").assertIsDisplayed()
            compose.onNodeWithText("0 selected").assertIsDisplayed()
            assertEquals(0, compose.onAllNodes(hasText("Update") and hasAncestorTag(BULK_BAR_TAG)).fetchSemanticsNodes().size)
            compose.shownRow("Pocket Notes").performClick()
            bar("Update").assertIsNotEnabled()
        }
    }
}
