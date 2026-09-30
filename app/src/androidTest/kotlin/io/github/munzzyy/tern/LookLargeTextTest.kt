package io.github.munzzyy.tern

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.ui.LocalActionScope
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.look.LOOK_HUE_TAG
import io.github.munzzyy.tern.ui.look.LOOK_PREVIEW_TAG
import io.github.munzzyy.tern.ui.look.LookScreen
import io.github.munzzyy.tern.ui.look.isCut
import io.github.munzzyy.tern.ui.theme.TernTheme
import io.github.munzzyy.tern.ui.theme.dynamicColorSupported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val CHOICES = listOf(
    "Follow the system", "Light", "Dark", "Ink", "Slate", "Tide", "Moss", "Amber", "Clay", "Rose", "Plum", "Own colour",
    "Standard", "Medium", "High", "Pure black", "Comfortable", "Compact", "Round", "Soft", "Sharp", "Circle", "Squircle", "Square",
    "Icons from the source",
)

/** Every text on screen that lost its end, with the words it holds. */
fun ComposeTestRule.cutTexts(): List<String> {
    val drawsText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
    return onAllNodes(drawsText, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return@mapNotNull null
        if (layout.isCut()) "${layout.layoutInput.text.text} in ${layout.size}, ${layout.lineCount} lines" else null
    }
}

/** The Look page by itself, at the text size and in the direction a test asks for. */
@RunWith(AndroidJUnit4::class)
class LookLargeTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun fresh() = fake.loadScenario("default")

    private fun show(fontScale: Float, direction: LayoutDirection) {
        compose.setContent {
            val settings by fake.settings.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalEngine provides fake,
                LocalActionScope provides rememberCoroutineScope(),
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                TernTheme(settings) { LookScreen(onBack = {}) }
            }
        }
    }

    private fun everyChoiceCanBeReadAndReached() {
        compose.onNodeWithTag(LOOK_PREVIEW_TAG).assertIsDisplayed()
        assertFalse("a text of the example is cut", compose.drawn().cut)
        // The style of Tern's own colours shows unless Android's wallpaper colours are in use, and it has a Standard of its own.
        val styles = fake.settings.value.colorSource != ColorSource.WALLPAPER || !dynamicColorSupported
        val choices = (if (dynamicColorSupported) CHOICES + "Wallpaper" else CHOICES) + (if (styles) listOf("Vibrant", "Expressive") else emptyList())
        for (choice in choices) {
            val found = compose.onAllNodesWithText(choice)
            assertTrue("no $choice on the page", found.fetchSemanticsNodes().isNotEmpty())
            for (i in found.fetchSemanticsNodes().indices) {
                found[i].performScrollTo().assertIsDisplayed()
                assertEquals("cut while $choice is on screen", emptyList<String>(), compose.cutTexts())
            }
        }
        compose.pick("Own colour")
        compose.onNodeWithTag(LOOK_HUE_TAG).performScrollTo().assertIsDisplayed()
        compose.pick("Compact")
        assertFalse("a text of the example is cut in compact", compose.drawn().cut)
        assertEquals(emptyList<String>(), compose.cutTexts())
    }

    @Test
    fun atTwiceTheTextSizeNothingIsCutAndEverythingCanBeReached() {
        show(fontScale = 2f, LayoutDirection.Ltr)
        everyChoiceCanBeReadAndReached()
    }

    @Test
    fun inARightToLeftLanguageAtTwiceTheTextSizeToo() {
        show(fontScale = 2f, LayoutDirection.Rtl)
        everyChoiceCanBeReadAndReached()
    }

    @Test
    fun inARightToLeftLanguageThePageIsMirrored() {
        show(fontScale = 1f, LayoutDirection.Rtl)
        val width = compose.onRoot().fetchSemanticsNode().size.width
        val back = compose.onNode(hasContentDescription("Back")).fetchSemanticsNode().boundsInRoot
        assertTrue("the way back is on the right: $back of $width", back.left > width / 2)
        val first = compose.onNodeWithText("Follow the system").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Light").fetchSemanticsNode().boundsInRoot
        assertTrue("the first choice is the rightmost: $first, $second", first.right > second.right)
        val ink = compose.onNodeWithText("Ink").fetchSemanticsNode().boundsInRoot
        val slate = compose.onNodeWithText("Slate").fetchSemanticsNode().boundsInRoot
        assertTrue("swatches run from the right: $ink, $slate", ink.left > slate.left)

        compose.pick("Own colour")
        compose.settles("the source of colour is stored") { stored.colorSource == ColorSource.CUSTOM }
        val slider = compose.onNodeWithTag(LOOK_HUE_TAG).performScrollTo()
        slider.performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        compose.settles("near the left end the hue is high") { stored.customHue > 300 }
        slider.performTouchInput { click(percentOffset(0.9f, 0.5f)) }
        compose.settles("near the right end the hue is low") { stored.customHue < 60 }
        assertEquals(stored.customHue.toFloat(), slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current)
    }

    @Test
    fun leftToRightIsTheOtherWayRound() {
        show(fontScale = 1f, LayoutDirection.Ltr)
        val first = compose.onNodeWithText("Follow the system").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Light").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left < second.left)
        compose.pick("Own colour")
        val slider = compose.onNodeWithTag(LOOK_HUE_TAG).performScrollTo()
        slider.performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        compose.settles("near the left end the hue is low") { stored.customHue < 60 }
    }
}
