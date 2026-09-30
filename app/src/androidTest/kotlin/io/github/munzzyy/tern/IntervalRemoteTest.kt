package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.settings.IntervalRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How often Tern checks, set with the keys of a remote: the buttons step the slider and the arrows never get stuck in it. */
@RunWith(AndroidJUnit4::class)
class IntervalRemoteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var minutes = 0

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    private fun interval(start: Int) {
        compose.host(television = true) {
            var shown by remember { mutableIntStateOf(start) }
            Column {
                QuietButton("Above", onClick = {})
                IntervalRow(shown) {
                    shown = it
                    minutes = it
                }
                QuietButton("Below", onClick = {})
            }
        }
        compose.press(KEYCODE_DPAD_DOWN)
        compose.moveTo(hasText("Above"), KEYCODE_DPAD_UP, max = 3)
    }

    @Test
    fun theButtonsSayWhatTheyDoAndTheArrowsPassTheSliderBy() {
        interval(20)
        compose.press(KEYCODE_DPAD_DOWN)
        val minus = hasContentDescription("Check more often")
        compose.assertFocusOn(minus, "down from above lands on the minus button")
        compose.press(KEYCODE_DPAD_RIGHT)
        compose.assertFocusOn(hasContentDescription("Check less often"), "right goes past the slider to the plus button")
        compose.press(KEYCODE_DPAD_LEFT)
        compose.assertFocusOn(minus, "and left comes back")
        compose.press(KEYCODE_DPAD_DOWN)
        compose.assertFocusOn(hasText("Below"), "down leaves the row")
        compose.press(KEYCODE_DPAD_UP)
        compose.moveTo(minus, KEYCODE_DPAD_LEFT, max = 2)

        compose.press(KEYCODE_DPAD_CENTER)
        compose.waitUntil(3_000) { minutes == 15 }
        compose.assertFocusOn(hasContentDescription("Turn background checks off"), "at 15 minutes the minus button says it turns the checks off")
        compose.press(KEYCODE_DPAD_CENTER)
        compose.waitUntil(3_000) { minutes == 0 }
        compose.onNodeWithContentDescription("Turn background checks on").assertExists()
        assertEquals(0, minutes)
    }
}
