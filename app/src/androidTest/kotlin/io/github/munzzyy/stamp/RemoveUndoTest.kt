package io.github.munzzyy.stamp

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.EventKind
import io.github.munzzyy.stamp.ui.apps.SNACKBAR_ACTION_TAG
import io.github.munzzyy.stamp.ui.detail.DETAIL_LIST_TAG
import io.github.munzzyy.stamp.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.stamp.ui.detail.DETAIL_REMOVE_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Removing asks nothing. The app leaves the list at once and the engine keeps it until the offer to undo has gone. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RemoveUndoTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun touch() = touchAgain()

    private fun tracked(id: String): Boolean = fake.apps.value.any { it.id == id }

    private fun removals(): Int = fake.events.value.count { it.kind == EventKind.REMOVED && it.appId == "pocketnotes" }

    private fun rowExists(name: String): Boolean = try {
        compose.shownRow(name)
        true
    } catch (_: AssertionError) {
        false
    }

    private fun removeFromTheDetail(name: String) {
        compose.shownRow(name).performClick()
        compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG))
        compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(DETAIL_REMOVE_TAG))
        compose.tagged(DETAIL_REMOVE_TAG).performClick()
    }

    @Test
    fun undoBringsTheAppBackAndNothingWasRemoved() {
        launch("default").use {
            removeFromTheDetail("Pocket Notes")
            compose.waitForText("Stopped following Pocket Notes")
            assertEquals("no question is asked", 0, compose.textCount("Remove Pocket Notes?"))
            compose.waitUntil(5_000) { !rowExists("Pocket Notes") }
            assertTrue("the engine keeps the app while Undo is on offer", tracked("pocketnotes"))

            compose.tagged(SNACKBAR_ACTION_TAG).performClick()
            compose.waitUntil(5_000) { rowExists("Pocket Notes") }
            compose.waitUntil(5_000) { compose.textCount("Stopped following Pocket Notes") == 0 }
            assertTrue(tracked("pocketnotes"))
            assertEquals(0, removals())
        }
    }

    @Test
    fun withoutUndoTheAppIsRemovedWhenTheOfferHasGone() {
        launch("default").use {
            removeFromTheDetail("Pocket Notes")
            compose.waitForText("Stopped following Pocket Notes")
            compose.waitUntil(5_000) { !rowExists("Pocket Notes") }
            assertTrue("the engine keeps the app while Undo is on offer", tracked("pocketnotes"))

            compose.waitUntil(30_000) { !tracked("pocketnotes") }
            compose.waitUntil(5_000) { compose.textCount("Stopped following Pocket Notes") == 0 }
            assertFalse(rowExists("Pocket Notes"))
            assertEquals(1, removals())
        }
    }

    @Test
    fun aSecondRemovalEndsTheOfferOfTheFirst() {
        launch("default").use {
            compose.shownRow("Pocket Notes").performCustomAccessibilityActionWithLabel("Remove")
            compose.waitForText("Stopped following Pocket Notes")
            assertTrue(tracked("pocketnotes"))
            compose.shownRow("Tagged Weather").performCustomAccessibilityActionWithLabel("Remove")
            compose.waitForText("Stopped following Tagged Weather")
            compose.waitUntil(5_000) { !tracked("pocketnotes") }
            assertTrue(tracked("tagged"))
            compose.tagged(SNACKBAR_ACTION_TAG).performClick()
            compose.waitUntil(5_000) { rowExists("Tagged Weather") }
            assertTrue(tracked("tagged"))
            assertFalse(rowExists("Pocket Notes"))
        }
    }

    @Test
    fun withoutATouchScreenUndoTakesFocusAndTheListGetsItBack() {
        org.junit.Assume.assumeTrue("needs a device without touch", withoutTouch)
        keysOnly()
        launch("default").use {
            compose.shownRow("Pocket Notes").performCustomAccessibilityActionWithLabel("Remove")
            compose.waitForText("Stopped following Pocket Notes")
            compose.assertFocusOn(hasText("Undo"), "Undo takes focus when it appears, a remote has no other way to it")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { rowExists("Pocket Notes") }
            assertTrue(tracked("pocketnotes"))
            compose.expect("focus is somewhere in the list after the snackbar has gone", 5_000) {
                compose.focusedLabel() != "nothing" && !compose.hasFocusOn(hasText("Undo"))
            }
        }
    }
}
