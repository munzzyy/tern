package io.github.munzzyy.stamp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/**
 * Scrolls only as far as it takes to show what has focus, with [roomPx] left free before and
 * after it, which is where its outline grows into. Left to itself a television holds what has
 * focus at a fixed height of the screen, and a text field with room to scroll above and below it
 * then never comes to rest: the screen is drawn again for as long as the field has focus.
 */
@OptIn(ExperimentalFoundationApi::class)
class ScrollToShow(private val roomPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = scrollToShow(offset, size, containerSize, roomPx)
}

/**
 * How far to scroll so that what starts at [offset] and is [size] long lies inside a container of
 * [containerSize], with [room] around it where it fits. Nothing, when it lies inside already.
 */
fun scrollToShow(offset: Float, size: Float, containerSize: Float, room: Float): Float {
    val spare = if (room > 0 && size + 2 * room <= containerSize) room else 0f
    val start = offset - spare
    val end = offset + size + spare
    return when {
        start >= 0 && end <= containerSize -> 0f
        start < 0 && end > containerSize -> 0f
        abs(start) < abs(end - containerSize) -> start
        else -> end - containerSize
    }
}
