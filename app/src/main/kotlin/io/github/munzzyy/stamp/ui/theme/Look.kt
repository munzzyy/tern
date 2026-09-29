package io.github.munzzyy.stamp.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.munzzyy.stamp.engine.Density as Roominess

/**
 * Every size and space a screen may use. A screen types no number of its own: it reads
 * `LocalLook.current`. The values follow the Density setting and whether the device is a
 * television.
 */
@Immutable
data class Look(
    /** True on a television, where everything is a step larger and the shell keeps the overscan margin clear. */
    val television: Boolean,
    /** True when the Density setting is Compact. */
    val compact: Boolean,
    /** Kept clear by the shell at the left and right of the whole window: the overscan margin, 0 on anything but a television. */
    val edgeHorizontal: Dp,
    /** Kept clear by the shell at the top and bottom of the whole window: the overscan margin, 0 on anything but a television. */
    val edgeVertical: Dp,
    /** Between a screen's side and content that is not a row: text, cards, buttons. */
    val screenPadding: Dp,
    /** The widest a column of settings or text grows on a large screen. */
    val contentMaxWidth: Dp,
    /** True when the top of a screen shows its title on a line of its own, under the back arrow and the actions. */
    val topTakesTwoLines: Boolean,
    /** Least height of an app row: icon, name and one status line. */
    val rowHeight: Dp,
    /** Least height of a settings row and of any other row that is pressed. Never under [touchTarget]. */
    val settingHeight: Dp,
    /** The smallest a thing that is pressed may be, in both directions. */
    val touchTarget: Dp,
    /** Inside a row, before its first and after its last element. */
    val rowPaddingHorizontal: Dp,
    /** Inside a row, above and below its text. */
    val rowPaddingVertical: Dp,
    /** Between two things that belong together, such as an icon and the text next to it. */
    val gap: Dp,
    /** Between a glyph and its word, and between two lines of one block of text. */
    val gapSmall: Dp,
    /** Between two sections or two cards. */
    val gapSection: Dp,
    /** Inside a card, on every side. */
    val cardPadding: Dp,
    /** Side of an app icon in a list. */
    val iconList: Dp,
    /** Side of the app icon at the top of a detail screen. */
    val iconHeader: Dp,
    /** Side of a glyph in a row, a button or a top bar. */
    val glyph: Dp,
    /** Side of a glyph inside a chip. */
    val glyphSmall: Dp,
    /** Least height of a status chip. */
    val chipHeight: Dp,
    /** Least height of a button. What is pressed around it is never under [touchTarget]. */
    val buttonHeight: Dp,
    /** Least height of a chip that is pressed, such as one choice among a few. What is pressed around it is never under [touchTarget]. */
    val choiceHeight: Dp,
    /** Left free to the left and right of anything that takes focus, so it can grow without touching its neighbour. Above and below, half of it. */
    val focusRoom: Dp,
    /** Width of the outline around what has focus. */
    val focusOutline: Dp,
)

fun lookFor(density: Roominess, television: Boolean): Look {
    val compact = density == Roominess.COMPACT
    val step = if (television) 1 else 0
    fun size(comfortable: Int, tight: Int, larger: Int): Dp = ((if (compact) tight else comfortable) + step * larger).dp
    return Look(
        television = television,
        compact = compact,
        edgeHorizontal = if (television) 48.dp else 0.dp,
        edgeVertical = if (television) 27.dp else 0.dp,
        screenPadding = size(16, 12, 0),
        contentMaxWidth = 840.dp,
        topTakesTwoLines = !compact,
        rowHeight = size(72, 60, 8),
        settingHeight = size(56, 48, 8),
        touchTarget = 48.dp,
        rowPaddingHorizontal = size(16, 12, 4),
        rowPaddingVertical = size(10, 4, 2),
        gap = size(16, 12, 4),
        gapSmall = size(8, 6, 2),
        gapSection = size(24, 16, 4),
        cardPadding = size(16, 12, 4),
        iconList = size(40, 36, 8),
        iconHeader = size(64, 56, 8),
        glyph = size(24, 24, 4),
        glyphSmall = size(18, 16, 2),
        chipHeight = size(28, 24, 4),
        buttonHeight = size(44, 40, 8),
        choiceHeight = size(40, 36, 8),
        focusRoom = if (television) 8.dp else 4.dp,
        focusOutline = 3.dp,
    )
}

val LocalLook = staticCompositionLocalOf { lookFor(Roominess.COMFORTABLE, television = false) }
