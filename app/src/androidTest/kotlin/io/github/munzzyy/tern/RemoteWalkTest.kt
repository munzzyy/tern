package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.model.UpdateMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteWalkTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val row = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    @Test
    fun downFromTheTopBarGoesIntoTheList() {
        launch("default").use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.moveTo(
                hasContentDescription("Search your apps") or hasContentDescription("Filter the list") or hasContentDescription("Check all apps now") or hasContentDescription("More options"),
                KEYCODE_DPAD_UP,
            )
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(hasSetTextAction() or hasText("Confirm"), "down from the top bar lands on what is right under it, the banner or the search field")
        }
    }

    @Test
    fun anEmptyListKeepsUpAndDownOffTheRail() {
        launch("empty").use {
            val addFirst = hasText("Add an app")
            compose.assertFocusOn(addFirst, "an empty list opens on its first action")
            compose.press(KEYCODE_DPAD_UP)
            compose.assertFocusOn(hasContentDescription("Check all apps now"), "up from the list reaches its top bar")
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(addFirst, "down from the top bar comes back into the list")
        }
    }

    @Test
    fun detailWalkChangesUpdateModeAndBackReturnsToTheRow() {
        launch("default").use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(row, "down moves to the second app")
            val name = compose.focusedLabel().substringBefore('.')
            val id = fake.apps.value.first { it.config.name == name }.id
            compose.press(KEYCODE_DPAD_CENTER)
            compose.expect("the detail takes focus when it opens") { !compose.hasFocusOn(row) && !compose.hasFocusOn(isTab) && compose.focusedLabel() != "nothing" }

            val mode = hasText("When an update is found", substring = true)
            compose.moveTo(mode, KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(radio and isSelected(), "the dialog opens on the choice already made")
            compose.moveTo(radio and hasText("Only when I check"), KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(mode, "closing the dialog gives focus back to the setting that opened it")
            compose.waitUntil(3_000) { fake.apps.value.first { it.id == id }.config.updates == UpdateMode.MANUAL }

            device.pressBack()
            compose.assertFocusOn(row and hasContentDescription("$name.", substring = true), "back returns to the app that was opened")
        }
    }

    @Test
    fun anActivityEntryWithoutAnAppCanBeReachedWithTheRemote() {
        assumeTrue("needs a device without touch", withoutTouch)
        launch("default").use {
            compose.openTab("Activity")
            val imported = hasContentDescription("Imported 14 apps", substring = true)
            compose.moveTo(imported, KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(imported, "the entry about the import takes focus")
        }
    }

    @Test
    fun settingsImportAndActivityOpenOnTheirFirstControl() {
        launch("default").use { scenario ->
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.openTab("Settings")
            compose.assertFocusOn(hasContentDescription("Check more often"), "Settings opens on its first setting, the button that steps how often")
            val import = hasText("Import apps", substring = true)
            compose.moveTo(import, KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(hasText("Pick a file"), "Import opens on its first action")
            device.pressBack()
            compose.assertFocusOn(import, "back from Import returns to the row that opened it")

            compose.openTab("Activity")
            compose.assertFocusOn(hasText("Problems only"), "Activity opens on its filter")
            device.pressBack()
            compose.assertFocusOn(row, "back from a tab lands on the list, still inside the app")
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
            assertEquals(1, compose.onAllNodes(isTab and isSelected() and hasText("Apps")).fetchSemanticsNodes().size)
        }
    }
}
