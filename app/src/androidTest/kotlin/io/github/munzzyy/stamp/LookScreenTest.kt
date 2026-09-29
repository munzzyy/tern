package io.github.munzzyy.stamp

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.ColorSource
import io.github.munzzyy.stamp.engine.Contrast
import io.github.munzzyy.stamp.engine.Corners
import io.github.munzzyy.stamp.engine.Density
import io.github.munzzyy.stamp.engine.IconShape
import io.github.munzzyy.stamp.engine.Palette
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.engine.ThemeMode
import io.github.munzzyy.stamp.ui.look.Drawn
import io.github.munzzyy.stamp.ui.look.LOOK_HUE_TAG
import io.github.munzzyy.stamp.ui.look.LOOK_PREVIEW_TAG
import io.github.munzzyy.stamp.ui.look.LookDrawn
import io.github.munzzyy.stamp.ui.theme.BLACK
import io.github.munzzyy.stamp.ui.theme.CUSTOM_STRENGTH
import io.github.munzzyy.stamp.ui.theme.Role
import io.github.munzzyy.stamp.ui.theme.Squircle
import io.github.munzzyy.stamp.ui.theme.cornerSizes
import io.github.munzzyy.stamp.ui.theme.lookFor
import io.github.munzzyy.stamp.ui.theme.roles
import io.github.munzzyy.stamp.ui.theme.seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

fun ComposeTestRule.openLook() {
    onNodeWithText("Settings").performClick()
    onNodeWithText("Look").performScrollTo().performClick()
    waitFor(hasTestTag(LOOK_PREVIEW_TAG))
}

fun ComposeTestRule.drawn(): Drawn = onNodeWithTag(LOOK_PREVIEW_TAG).fetchSemanticsNode().config[LookDrawn]

val stored: Settings get() = fake.settings.value

fun ComposeTestRule.pick(choice: String) {
    onNodeWithText(choice).performScrollTo().performClick()
    waitFor(hasText(choice) and isSelected(), 3_000)
}

fun ComposeTestRule.settles(why: String, condition: () -> Boolean) {
    try {
        waitUntil(3_000, condition)
    } catch (_: Throwable) {
        throw AssertionError("$why. Stored: $stored. Drawn: ${drawn()}")
    }
}

@RunWith(AndroidJUnit4::class)
class LookScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun accent(settings: Settings): Int {
        val seed = settings.palette.seed
        return roles(seed.hue, seed.strength, dark = false, settings.contrast)[Role.PRIMARY]
    }

    @Test
    fun settingsOpenTheLookPageAndBackReturns() {
        launch("default").use {
            compose.openLook()
            compose.onNodeWithTag(LOOK_PREVIEW_TAG).assertIsDisplayed()
            compose.onNodeWithText("Theme").assertIsDisplayed()
            device.pressBack()
            compose.waitForText("Import apps")
            assertEquals(0, compose.onAllNodes(hasTestTag(LOOK_PREVIEW_TAG)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun everyChoiceIsStoredAndRedrawsTheExampleAtOnce() {
        launch("default").use {
            compose.openLook()
            compose.pick("Light")
            compose.settles("the theme is stored") { stored.theme == ThemeMode.LIGHT }

            compose.pick("Moss")
            compose.settles("the palette is stored") { stored.palette == Palette.MOSS && stored.colorSource == ColorSource.PALETTE }
            compose.settles("the example is drawn in Moss") { compose.drawn().primary == accent(stored) }
            val moss = compose.drawn()

            compose.pick("Clay")
            compose.settles("the palette is stored") { stored.palette == Palette.CLAY }
            compose.settles("the example is drawn in Clay") { compose.drawn().primary == accent(stored) }
            assertNotEquals(moss.primary, compose.drawn().primary)

            compose.pick("High")
            compose.settles("the contrast is stored") { stored.contrast == Contrast.HIGH }
            compose.settles("the example is drawn at high contrast") { compose.drawn().primary == accent(stored) }
            assertNotEquals(accent(stored.copy(contrast = Contrast.STANDARD)), compose.drawn().primary)

            val comfortable = compose.drawn().rowHeight
            compose.pick("Compact")
            compose.settles("the density is stored") { stored.density == Density.COMPACT }
            compose.settles("the example has the height of a compact row") {
                compose.drawn().rowHeight == lookFor(Density.COMPACT, withoutTouch).rowHeight.value
            }
            assertTrue(compose.drawn().rowHeight < comfortable)

            compose.pick("Sharp")
            compose.settles("the corners are stored") { stored.corners == Corners.SHARP }
            compose.settles("the example has sharp corners") { compose.drawn().corner == cornerSizes(Corners.SHARP)[2].toFloat() }

            compose.pick("Squircle")
            compose.settles("the icon shape is stored") { stored.iconShape == IconShape.SQUIRCLE }
            compose.settles("the example cuts its icon to a squircle") { compose.drawn().iconOutline == Squircle.toString() }

            compose.pick("Dark")
            compose.settles("the theme is stored") { stored.theme == ThemeMode.DARK }
            val dark = compose.drawn().surface
            compose.onNodeWithText("Pure black").performScrollTo().performClick()
            compose.settles("pure black is stored") { stored.pureBlack }
            compose.settles("the example stands on black") { compose.drawn().surface == BLACK }
            assertNotEquals(BLACK, dark)

            compose.onNodeWithText("Icons from the source").performScrollTo().performClick()
            compose.settles("the switch for icons is stored") { !stored.sourceIcons }
        }
    }

    @Test
    fun theOwnColourHasASliderThatStoresWhereTheFingerLifts() {
        launch("default").use {
            compose.openLook()
            compose.pick("Light")
            assertEquals(0, compose.onAllNodes(hasTestTag(LOOK_HUE_TAG)).fetchSemanticsNodes().size)
            compose.pick("Own colour")
            compose.settles("the source of colour is stored") { stored.colorSource == ColorSource.CUSTOM }
            compose.settles("the example is drawn in the hue the settings hold") {
                compose.drawn().primary == roles(stored.customHue, CUSTOM_STRENGTH, false, stored.contrast)[Role.PRIMARY]
            }
            val before = stored.customHue
            val slider = compose.onNodeWithTag(LOOK_HUE_TAG).performScrollTo()
            slider.performTouchInput { swipeRight(startX = centerX - width / 4, endX = centerX + width / 4) }
            compose.settles("the hue the finger left is stored") { stored.customHue != before }
            compose.settles("the example follows the hue") {
                compose.drawn().primary == roles(stored.customHue, CUSTOM_STRENGTH, false, stored.contrast)[Role.PRIMARY]
            }
            val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(stored.customHue.toFloat(), range.current, 0.5f)
            assertEquals(0f..359f, range.range)

            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
            compose.settles("TalkBack can set the hue") { stored.customHue == 40 }
            assertEquals("40 degrees", slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        }
    }

    @Test
    fun whatIsChosenIsMarkedAsChosenForTalkBack() {
        launch("default").use {
            compose.openLook()
            compose.pick("Tide")
            compose.onNodeWithText("Tide").assertIsSelected()
            assertEquals(1, compose.onAllNodes(swatch and isSelected()).fetchSemanticsNodes().size)
            compose.pick("Rose")
            assertEquals(listOf("Rose"), compose.onAllNodes(swatch and isSelected()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].single().text })
        }
    }

    private val swatch = SemanticsMatcher("is a swatch") { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
        text in setOf("Wallpaper", "Ink", "Slate", "Tide", "Moss", "Amber", "Clay", "Rose", "Plum", "Own colour")
    }
}
