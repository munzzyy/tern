package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An app's page, walked from top to bottom with the keys of a remote. */
@RunWith(AndroidJUnit4::class)
class AppPageRemoteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val row = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    @Test
    fun downWalksThePageToItsLastSettingThroughTheCategoriesAndTheNote() {
        launch("default").use { scenario ->
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.expect("the page takes focus when it opens") { !compose.hasFocusOn(row) && compose.focusedLabel() != "nothing" }
            val chip = hasText("Tools") or hasText("Security") or hasText("Writing") or hasText("Outdoors")
            compose.moveTo(chip, KEYCODE_DPAD_DOWN)
            compose.moveTo(hasText("New…"), KEYCODE_DPAD_RIGHT, max = 6)
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(hasText("Add a note"), "down from the categories goes on to the note")
            compose.moveTo(hasText("Show filters", substring = true), KEYCODE_DPAD_DOWN, max = 60)
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
        }
    }
}
