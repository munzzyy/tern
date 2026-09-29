package io.github.munzzyy.jackdaw

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.munzzyy.jackdaw.ui.TAB_LABEL_TAG
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The narrowest phone we support at 200% text: no navigation label may be cut. */
@RunWith(AndroidJUnit4::class)
class NarrowLargeTextTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var previousScale = "1.0"

    @Before
    fun narrowAndLarge() {
        previousScale = device.executeShellCommand("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        device.executeShellCommand("wm size 720x1600")
        device.executeShellCommand("wm density 320")
        device.executeShellCommand("settings put system font_scale 2.0")
        device.waitForIdle()
    }

    @After
    fun restore() {
        device.executeShellCommand("settings put system font_scale $previousScale")
        device.executeShellCommand("wm size reset")
        device.executeShellCommand("wm density reset")
    }

    @Test
    fun everyTabLabelFitsItsSlot() {
        launch("default").use {
            val labels = compose.onAllNodesWithTag(TAB_LABEL_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(4, labels.size)
            for (node in labels) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val text = layouts.single().layoutInput.text
                assertFalse("\"$text\" is cut off", layouts.single().hasVisualOverflow)
            }
        }
    }
}
