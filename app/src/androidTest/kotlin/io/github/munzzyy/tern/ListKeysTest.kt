package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_MOVE_END
import android.view.KeyEvent.KEYCODE_MOVE_HOME
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import android.view.KeyEvent.KEYCODE_PAGE_UP
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_U
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.apps.APP_SEARCH_TAG
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The list's own keys under a keyboard or a remote: page keys, Home and End, and a letter that starts a search. */
@RunWith(AndroidJUnit4::class)
class ListKeysTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val row = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
    private val searchField = hasTestTag(APP_SEARCH_TAG)

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    private fun shown(label: String): Boolean = try {
        compose.onNode(hasContentDescription(label)).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun lowestRow(): String {
        val rows = compose.onAllNodes(row).fetchSemanticsNodes()
        return rows.maxBy { it.boundsInRoot.top }.config[SemanticsProperties.ContentDescription].joinToString()
    }

    @Test
    fun pageDownLeavesTheFirstScreenAndPageUpComesBack() {
        launch("thirty").use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            val first = compose.focusedLabel()
            compose.press(KEYCODE_PAGE_DOWN)
            compose.expect("page down gives focus to a row further down") { compose.hasFocusOn(row) && compose.focusedLabel() != first }
            compose.expect("the first app has scrolled away") { !shown(first) }
            compose.press(KEYCODE_PAGE_UP)
            compose.expect("page up gives focus back to the first app") { compose.focusedLabel() == first }
        }
    }

    @Test
    fun endFocusesTheLastAppAndHomeTheFirst() {
        launch("thirty").use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            val first = compose.focusedLabel()
            compose.press(KEYCODE_MOVE_END)
            compose.expect("End gives focus to the last app") { compose.hasFocusOn(row) && compose.focusedLabel() == lowestRow() && !shown(first) }
            compose.press(KEYCODE_MOVE_HOME)
            compose.expect("Home gives focus back to the first app") { compose.focusedLabel() == first }
        }
    }

    @Test
    fun aLetterTypedOnTheListStartsASearchWithIt() {
        launch("thirty").use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.press(KEYCODE_S)
            compose.waitFor(searchField and hasText("s"))
            if (!withoutTouch) {
                compose.assertFocusOn(searchField, "the search field takes focus")
                compose.press(KEYCODE_U)
                compose.waitFor(searchField and hasText("su"))
                compose.press(KEYCODE_MOVE_HOME, KEYCODE_PAGE_DOWN, KEYCODE_MOVE_END)
                compose.assertFocusOn(searchField, "keys pressed in the field stay in the field")
            }
        }
    }
}
