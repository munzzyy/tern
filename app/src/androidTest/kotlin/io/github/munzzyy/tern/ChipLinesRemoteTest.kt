package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.common.ChipLines
import io.github.munzzyy.tern.ui.common.ChoiceChip
import io.github.munzzyy.tern.ui.common.QuietButton
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Chips that wrap onto several lines, as the categories do, walked with the keys of a remote. */
@RunWith(AndroidJUnit4::class)
class ChipLinesRemoteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val labels = List(12) { "Category ${it + 1}" }

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    private fun chipLines() {
        compose.host(television = true) {
            val start = remember { FocusRequester() }
            val input = LocalInputModeManager.current
            // On a phone focus takes only out of touch mode and not on the first frame; Tern's screens land it the same way.
            LaunchedEffect(Unit) {
                input.requestInputMode(InputMode.Keyboard)
                repeat(30) {
                    withFrameNanos {}
                    if (start.requestFocus()) return@LaunchedEffect
                }
            }
            // What stands beside the chips and further down, like the example on the Look page, must not catch right at the end of a line.
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.width(320.dp)) {
                    QuietButton("Before", onClick = {})
                    ChipLines(labels) { index, label, stop ->
                        ChoiceChip(label, selected = false, onClick = {}, role = Role.Checkbox, modifier = (if (index == 0) Modifier.focusRequester(start) else Modifier).then(stop))
                    }
                    QuietButton("After", onClick = {})
                }
                QuietButton("Beside", onClick = {})
            }
        }
        compose.assertFocusOn(hasText("Category 1"), "the first chip takes focus")
    }

    private fun top(label: String): Float = compose.onNodeWithText(label).getUnclippedBoundsInRoot().top.value

    @Test
    fun rightFromTheEndOfALineGoesOnToTheStartOfTheNext() {
        chipLines()
        // A focused chip grows a little, so its top moves a little.
        val firstLine = labels.takeWhile { abs(top(it) - top(labels.first())) < 8f }
        assertTrue("the chips wrap onto a second line", firstLine.size < labels.size)
        compose.moveTo(hasText(firstLine.last()), KEYCODE_DPAD_RIGHT, max = firstLine.size)
        compose.press(KEYCODE_DPAD_RIGHT)
        compose.assertFocusOn(hasText(labels[firstLine.size]), "right from the end of the first line goes to the start of the second")
    }

    @Test
    fun upAndDownLeaveTheChipsWhateverLineTheyAreOn() {
        chipLines()
        compose.press(KEYCODE_DPAD_DOWN)
        compose.assertFocusOn(hasText("After"), "down from the first line leaves the chips")
        compose.press(KEYCODE_DPAD_UP)
        compose.assertFocusOn(hasText("Category", substring = true), "up comes back into the chips")
        compose.press(KEYCODE_DPAD_UP)
        compose.assertFocusOn(hasText("Before"), "up from the last line leaves the chips too")
    }
}
