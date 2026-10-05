package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.fake.FakeLinks
import io.github.munzzyy.tern.fake.FakeSuggestions
import io.github.munzzyy.tern.ui.add.ADD_CONFIRM_TAG
import io.github.munzzyy.tern.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.tern.ui.add.ADD_FIND_TAG
import io.github.munzzyy.tern.ui.add.ADD_INSTALL_TAG
import io.github.munzzyy.tern.ui.firstrun.FIRST_RUN_ADD_TAG
import io.github.munzzyy.tern.ui.suggest.STARTER_ROW_TAG
import io.github.munzzyy.tern.ui.suggest.firstStarter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteAddTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val field = hasTestTag(ADD_FIELD_TAG)
    private val row = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    @Test
    fun addAndInstallWithArrowKeysOnly() {
        launch("default").use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.openTab("Add")
            compose.assertFocusOn(field, "the Add screen opens with focus on its field")
            if (withoutTouch) {
                assertFalse("passing focus to the field must not open the keyboard", keyboardShown())
                compose.press(KEYCODE_DPAD_CENTER)
                compose.waitUntil(3_000) { keyboardShown() }
            }
            device.executeShellCommand("input text ${FakeLinks.NEW_APP}")
            compose.waitUntil(3_000) { compose.onAllNodes(field and hasText(FakeLinks.NEW_APP)).fetchSemanticsNodes().isNotEmpty() }
            if (keyboardShown()) {
                device.pressBack()
                compose.waitUntil(3_000) { !keyboardShown() }
                compose.assertFocusOn(field, "BACK closes the keyboard and leaves focus on the field")
            }

            compose.press(KEYCODE_DPAD_DOWN)
            compose.moveTo(hasTestTag(ADD_FIND_TAG), KEYCODE_DPAD_RIGHT)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitForText("Sparrow")
            compose.expect("focus moves into the preview once it is shown") { focusBelow(ADD_FIND_TAG) }

            compose.moveTo(hasTestTag(ADD_CONFIRM_TAG), KEYCODE_DPAD_DOWN)
            compose.moveTo(hasTestTag(ADD_INSTALL_TAG), KEYCODE_DPAD_RIGHT)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { fake.apps.value.any { it.id == "sparrow" } }
            compose.expect("the detail that opens takes focus") {
                compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() && !compose.hasFocusOn(isTab)
            }
        }
    }

    private val firstStarterRow = hasTestTag(STARTER_ROW_TAG) and hasText(firstStarter(FakeSuggestions.all, withoutTouch)!!.name)

    @Test
    fun wellKnownAppsOnAnEmptyListLandOnTheFirstOfThem() {
        launch("empty").use {
            compose.assertFocusOn(hasText("Add an app"), "an empty list opens on its first action")
            compose.moveTo(hasText("Well known apps"), KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(firstStarterRow, "the Add screen opens on the first well known app, not on its field")
        }
    }

    @Test
    fun wellKnownAppsOnTheFirstRunLandOnTheFirstOfThem() {
        launch("firstrun").use {
            compose.waitFor(hasTestTag(FIRST_RUN_ADD_TAG))
            compose.moveTo(hasText("Well known apps"), KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(firstStarterRow, "the Add screen opens on the first well known app, not on its field")
        }
    }

    @Test
    fun theAddTabItselfStillOpensOnItsField() {
        launch("empty").use {
            compose.assertFocusOn(hasText("Add an app"), "an empty list opens on its first action")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(field, "Add an app opens on the field")
        }
    }

    private fun focusBelow(tag: String): Boolean {
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().singleOrNull() ?: return false
        val above = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().singleOrNull() ?: return false
        return focused.boundsInRoot.top >= above.boundsInRoot.bottom
    }
}
