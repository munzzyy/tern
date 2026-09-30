package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.settings.IntervalRow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The buttons beside the slider for how often Tern checks, which only a device without touch has. */
@RunWith(AndroidJUnit4::class)
class IntervalRemoteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    @Test
    fun theMinusButtonSaysItTurnsTheChecksOffAtAQuarterOfAnHour() {
        compose.host(television = true) {
            var minutes by remember { mutableIntStateOf(20) }
            IntervalRow(minutes) { minutes = it }
        }
        val minus = hasContentDescription("Check more often")
        compose.onNode(minus).assertExists()
        compose.onNode(hasContentDescription("Check less often")).assertExists()
        compose.moveTo(minus, KEYCODE_DPAD_DOWN, max = 4)
        compose.press(KEYCODE_DPAD_CENTER)
        compose.assertFocusOn(hasContentDescription("Turn background checks off"), "at 15 minutes the minus button says it turns the checks off")
        compose.press(KEYCODE_DPAD_CENTER)
        compose.onNodeWithContentDescription("Turn background checks on").assertExists()
    }
}
