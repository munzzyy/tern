package io.github.munzzyy.tern

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.common.GlyphButton
import io.github.munzzyy.tern.ui.common.ScreenTop
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.More
import io.github.munzzyy.tern.ui.icons.Search
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The top of a screen at double size in a pane as narrow as the list beside an app's page. */
@RunWith(AndroidJUnit4::class)
class TopLineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun top(title: String) {
        compose.host(fontScale = 2f, television = true) {
            Box(Modifier.width(390.dp)) {
                ScreenTop(title) {
                    GlyphButton(Glyphs.Search, "Search", onClick = {})
                    GlyphButton(Glyphs.Filter, "Filter", onClick = {})
                    GlyphButton(Glyphs.Busy, "Check", onClick = {})
                    GlyphButton(Glyphs.More, "More", onClick = {})
                }
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /** Where a line ends between two letters of one word. */
    private fun splitWords(layout: TextLayoutResult): List<String> {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
            .filter { end -> end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit() }
            .map { text.substring(0, it) + "/" + text.substring(it) }
    }

    @Test
    fun aTitleThatDoesNotFitBesideTheButtonsGoesUnderThemWhole() {
        top("Applications")
        assertEquals(emptyList<String>(), splitWords(layoutOf("Applications")))
        compose.onNodeWithText("Applications").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
    }

    @Test
    fun aTitleThatFitsStaysOnTheLineOfTheButtons() {
        top("Apps")
        val title = compose.onNodeWithText("Apps").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("More").fetchSemanticsNode().boundsInRoot
        assertEquals(1, layoutOf("Apps").lineCount)
        assertTrue("Apps at $title, the buttons at $more", title.top < more.bottom && more.top < title.bottom)
    }
}
