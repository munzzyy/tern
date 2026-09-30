package io.github.munzzyy.tern

import android.content.Intent
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.AppGrouping
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The list grouped by category, walked with the keys of a remote. */
@RunWith(AndroidJUnit4::class)
class GroupedListRemoteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val row = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
    private val header = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasClickAction()
    private val notes = row and hasContentDescription("Pocket Notes.", substring = true)

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    /** Pocket Notes is filed under Outdoors and Writing, so the list draws it twice. */
    private fun launchGrouped(folded: Boolean): ActivityScenario<MainActivity> {
        launch("default").close()
        runBlocking {
            fake.saveSettings(fake.settings.value.copy(listGrouping = AppGrouping.CATEGORY, updatesFirst = false, collapseGroups = folded))
            fake.configure("pocketnotes") { it.copy(categories = listOf("Outdoors", "Writing")) }
        }
        noteSeen(true)
        return ActivityScenario.launch(Intent(appContext, MainActivity::class.java))
    }

    private fun focusedBounds(): Rect? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun headerTop(title: String): Float =
        compose.onNode(header and hasText(title, substring = true)).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun aGroupedListOpensOnItsFirstAppAndBackReturnsToTheRowThatWasOpened() {
        launchGrouped(folded = false).use {
            compose.assertFocusOn(notes, "the grouped list opens on the first app of its first group")
            val first = focusedBounds()
            var tries = 0
            while (!(compose.hasFocusOn(notes) && focusedBounds() != first)) {
                if (++tries > 40) fail("Down never reached Pocket Notes under Writing; focus is on ${compose.focusedLabel()}")
                compose.press(KEYCODE_DPAD_DOWN)
            }
            assertTrue("the second Pocket Notes is the one under Writing", focusedBounds()!!.top > headerTop("Writing"))
            compose.press(KEYCODE_DPAD_CENTER)
            compose.expect("the detail takes focus when it opens") { !compose.hasFocusOn(row) && compose.focusedLabel() != "nothing" }

            device.pressBack()
            compose.expect("back returns to Pocket Notes under Writing, not to its first place") {
                compose.hasFocusOn(notes) && (focusedBounds()?.top ?: 0f) > headerTop("Writing")
            }
        }
    }

    @Test
    fun aListWithItsGroupsFoldedOpensOnTheFirstGroupAndSelectAllTakesOnlyWhatIsShown() {
        launchGrouped(folded = true).use {
            val outdoors = header and hasText("Outdoors", substring = true)
            compose.assertFocusOn(outdoors, "with every group folded the list opens on the first group")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(notes)
            compose.assertFocusOn(outdoors, "opening a group leaves focus on its header")

            compose.onNodeWithContentDescription("More options").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Select").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Select all").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForText("selected")
            assertEquals("only the two apps under Outdoors are picked, none from the folded groups", 1, compose.textCount("2 selected"))
        }
    }
}
