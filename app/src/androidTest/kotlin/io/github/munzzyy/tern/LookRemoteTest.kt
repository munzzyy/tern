package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.engine.Contrast
import io.github.munzzyy.tern.engine.Corners
import io.github.munzzyy.tern.engine.ThemeMode
import io.github.munzzyy.tern.ui.look.LOOK_HUE_TAG
import io.github.munzzyy.tern.ui.look.LOOK_PREVIEW_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Look page with the keys of a remote and nothing else. */
@RunWith(AndroidJUnit4::class)
class LookRemoteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val row = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
    private val lookRow = hasText("Look") and hasText("Theme, colours", substring = true)
    private val slider = hasTestTag(LOOK_HUE_TAG)
    private val swatchNames = setOf("Wallpaper", "Ink", "Slate", "Tide", "Moss", "Amber", "Clay", "Rose", "Plum", "Own colour")
    private val swatch = SemanticsMatcher("is a swatch") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text in swatchNames
    }

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    private fun openLookWithKeys() {
        compose.assertFocusOn(row, "the list opens with focus on its first app")
        compose.openTab("Settings")
        compose.moveTo(lookRow, KEYCODE_DPAD_DOWN)
        compose.press(KEYCODE_DPAD_CENTER)
        compose.waitFor(hasTestTag(LOOK_PREVIEW_TAG))
        compose.assertFocusOn(hasText("Follow the system"), "the Look page opens on its first choice")
    }

    @Test
    fun aChoiceMovesWithLeftAndRightAndIsTakenWithTheMiddleKey() {
        launch("default").use {
            openLookWithKeys()
            compose.press(KEYCODE_DPAD_RIGHT)
            compose.assertFocusOn(hasText("Light"), "right moves to the next choice")
            compose.press(KEYCODE_DPAD_RIGHT)
            compose.assertFocusOn(hasText("Dark"), "and to the one after it")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.settles("the middle key takes the choice") { stored.theme == ThemeMode.DARK }
            compose.assertFocusOn(hasText("Dark") and isSelected(), "focus stays on what was taken")
            compose.press(KEYCODE_DPAD_LEFT)
            compose.assertFocusOn(hasText("Light"), "left moves back")
        }
    }

    @Test
    fun theSwatchesMoveWithLeftAndRight() {
        launch("default").use {
            openLookWithKeys()
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(swatch, "down from the theme reaches the swatches")
            val seen = mutableListOf(compose.focusedLabel())
            repeat(2) {
                compose.press(KEYCODE_DPAD_RIGHT)
                compose.assertFocusOn(swatch, "right stays among the swatches")
                seen += compose.focusedLabel()
            }
            assertEquals("right reaches a new swatch every time: $seen", 3, seen.distinct().size)
            compose.press(KEYCODE_DPAD_LEFT)
            assertEquals(seen[1], compose.focusedLabel())

            compose.moveTo(hasText("Slate"), KEYCODE_DPAD_RIGHT)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.settles("the middle key takes the palette") { stored.colorSource == ColorSource.PALETTE && stored.palette.name == "SLATE" }
        }
    }

    @Test
    fun theHueMovesInStepsOfFiveDegrees() {
        launch("default").use {
            openLookWithKeys()
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(swatch, "down from the theme reaches the swatches")
            compose.moveTo(hasText("Own colour") or hasText("Plum") or hasText("Rose"), KEYCODE_DPAD_DOWN)
            compose.moveTo(hasText("Own colour"), KEYCODE_DPAD_RIGHT)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.settles("the own colour is taken") { stored.colorSource == ColorSource.CUSTOM }
            compose.waitFor(slider)
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(slider, "down from the swatches reaches the slider")

            val start = stored.customHue
            compose.press(KEYCODE_DPAD_RIGHT)
            compose.settles("right adds five degrees") { stored.customHue == start + 5 }
            compose.assertFocusOn(slider, "the slider keeps focus while it moves")
            compose.press(KEYCODE_DPAD_LEFT, KEYCODE_DPAD_LEFT)
            compose.settles("left takes five degrees off") { stored.customHue == start - 5 }
            compose.assertFocusOn(slider, "the slider keeps focus while it moves")

            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(hasText("Colour code"), "down leaves the slider for the colour code under it")
            compose.press(KEYCODE_DPAD_UP)
            compose.assertFocusOn(slider, "and up comes back to it")
            compose.press(KEYCODE_DPAD_UP)
            compose.assertFocusOn(swatch, "up leaves the slider")
        }
    }

    @Test
    fun focusWalksThePageToItsEndAndBackAndNeverGetsLost() {
        launch("default").use {
            openLookWithKeys()
            val stops = mutableListOf<String>()
            val last = hasText("Icons from the source")
            repeat(40) {
                if (compose.hasFocusOn(last)) return@repeat
                compose.press(KEYCODE_DPAD_DOWN)
                val now = compose.focusedLabel()
                assertNotEquals("focus was lost after ${stops.lastOrNull()}", "nothing", now)
                stops += now
            }
            compose.assertFocusOn(last, "down reaches the last setting, by way of $stops")
            val sections = listOf(
                listOf("Standard", "Medium", "High"),
                listOf("Pure black"),
                listOf("Comfortable", "Compact"),
                listOf("Round", "Soft", "Sharp"),
                listOf("Circle", "Squircle", "Square"),
            )
            for (section in sections) {
                assertTrue("down passes one of $section: $stops", stops.any { stop -> section.any(stop::contains) })
            }
            compose.press(KEYCODE_DPAD_CENTER)
            compose.settles("the last setting can be switched") { !stored.sourceIcons }
            compose.press(KEYCODE_DPAD_DOWN)
            val wide = with(compose.density) { compose.onRoot().fetchSemanticsNode().size.width.toDp() } >= 600.dp
            if (wide) {
                compose.assertFocusOn(last, "beside a rail, down at the end stays where it is")
            } else {
                compose.assertFocusOn(isTab, "over a bottom bar, down at the end reaches the bar")
            }

            compose.moveTo(hasText("Round") or hasText("Soft") or hasText("Sharp"), KEYCODE_DPAD_UP)
            compose.moveTo(hasText("Sharp"), KEYCODE_DPAD_RIGHT)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.settles("corners can be set on the way") { stored.corners == Corners.SHARP }
            compose.moveTo(hasText("Standard") or hasText("Medium") or hasText("High"), KEYCODE_DPAD_UP)
            compose.moveTo(hasText("High"), KEYCODE_DPAD_RIGHT)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.settles("and the contrast") { stored.contrast == Contrast.HIGH }

            compose.moveTo(hasText("Follow the system") or hasText("Light") or hasText("Dark"), KEYCODE_DPAD_UP)
            compose.moveTo(hasContentDescription("Back"), KEYCODE_DPAD_UP)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(lookRow, "the way back returns to the row that opened the page")
        }
    }

    @Test
    fun backReturnsToTheRowThatOpenedThePage() {
        launch("default").use {
            openLookWithKeys()
            device.pressBack()
            compose.assertFocusOn(lookRow, "back from the Look page returns to its row in Settings")
        }
    }
}
