package io.github.munzzyy.tern.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.munzzyy.tern.engine.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    private val phone = lookFor(Density.COMFORTABLE, television = false)
    private val tight = lookFor(Density.COMPACT, television = false)
    private val television = lookFor(Density.COMFORTABLE, television = true)
    private val tightTelevision = lookFor(Density.COMPACT, television = true)
    private val all = listOf(phone, tight, television, tightTelevision)

    /** Every size that gets smaller with Compact and larger on a television. */
    private fun sizes(look: Look): Map<String, Dp> = mapOf(
        "rowHeight" to look.rowHeight,
        "settingHeight" to look.settingHeight,
        "rowPaddingHorizontal" to look.rowPaddingHorizontal,
        "rowPaddingVertical" to look.rowPaddingVertical,
        "gap" to look.gap,
        "gapSmall" to look.gapSmall,
        "gapSection" to look.gapSection,
        "cardPadding" to look.cardPadding,
        "iconList" to look.iconList,
        "iconHeader" to look.iconHeader,
        "glyphSmall" to look.glyphSmall,
        "chipHeight" to look.chipHeight,
        "buttonHeight" to look.buttonHeight,
        "choiceHeight" to look.choiceHeight,
    )

    @Test
    fun compactIsSmallerEverywhereItMayBe() {
        for ((name, size) in sizes(phone)) assertTrue("$name: ${sizes(tight)[name]} against $size", sizes(tight).getValue(name) < size)
        for ((name, size) in sizes(television)) assertTrue(name, sizes(tightTelevision).getValue(name) < size)
        assertTrue(tight.screenPadding < phone.screenPadding)
    }

    @Test
    fun nothingThatIsPressedGoesUnderFortyEightDp() {
        for (look in all) {
            assertEquals(48.dp, look.touchTarget)
            assertTrue("settingHeight ${look.settingHeight}", look.settingHeight >= look.touchTarget)
            assertTrue("rowHeight ${look.rowHeight}", look.rowHeight >= look.touchTarget)
        }
    }

    @Test
    fun aTelevisionIsAStepLargerAndKeepsTheOverscanMarginClear() {
        for ((name, size) in sizes(phone)) assertTrue(name, sizes(television).getValue(name) > size)
        for ((name, size) in sizes(tight)) assertTrue(name, sizes(tightTelevision).getValue(name) > size)
        assertTrue(television.glyph > phone.glyph)
        for (look in listOf(television, tightTelevision)) {
            assertTrue(look.television)
            assertEquals(48.dp, look.edgeHorizontal)
            assertEquals(27.dp, look.edgeVertical)
        }
        for (look in listOf(phone, tight)) {
            assertFalse(look.television)
            assertEquals(0.dp, look.edgeHorizontal)
            assertEquals(0.dp, look.edgeVertical)
        }
    }

    @Test
    fun compactPutsTheTopOfAScreenOnOneLine() {
        assertTrue(phone.topTakesTwoLines)
        assertTrue(television.topTakesTwoLines)
        assertFalse(tight.topTakesTwoLines)
        assertFalse(tightTelevision.topTakesTwoLines)
        assertTrue(tight.compact && tightTelevision.compact)
        assertFalse(phone.compact || television.compact)
    }

    @Test
    fun aRowLeavesTheRoomItsFocusNeeds() {
        for (look in all) {
            assertTrue(look.focusRoom > 0.dp)
            assertTrue("a row's padding holds the room for focus", look.rowPaddingHorizontal >= look.focusRoom)
            assertTrue(look.screenPadding >= look.focusRoom)
            assertTrue(look.focusOutline <= look.focusRoom)
        }
        assertTrue(television.focusRoom > phone.focusRoom)
    }

    @Test
    fun aRowHoldsItsIconAndAChipHoldsItsGlyph() {
        for (look in all) {
            assertTrue(look.rowHeight >= look.iconList + look.rowPaddingVertical * 2)
            assertTrue(look.chipHeight > look.glyphSmall)
            assertTrue(look.buttonHeight > look.glyphSmall)
            assertTrue(look.iconHeader > look.iconList)
            assertTrue(look.gapSmall < look.gap && look.gap < look.gapSection)
        }
    }
}
