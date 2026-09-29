package io.github.munzzyy.tern.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import io.github.munzzyy.tern.ui.theme.LocalLook

/**
 * How far a list has to move so that what took focus can be seen, with [room] left before and
 * after it for what comes next. Nothing moves while all of that is in view. [offset] is where the
 * thing starts inside the list's window, which is [containerSize] long.
 */
fun revealDistance(offset: Float, size: Float, containerSize: Float, room: Float): Float {
    val kept = room.coerceIn(0f, ((containerSize - size) / 2).coerceAtLeast(0f))
    val start = offset - kept
    val end = offset + size + kept
    return when {
        start >= 0 && end <= containerSize -> 0f
        start < 0 && end > containerSize -> 0f
        start < 0 -> start
        else -> end - containerSize
    }
}

private class RoomyReveal(private val room: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        revealDistance(offset, size, containerSize, room)
}

/**
 * On a television Compose moves whatever takes focus to a fixed place in a list, which scrolls
 * the top of a screen away the moment it opens. Inside [content] a list moves only as far as it
 * must, and keeps most of a row in view on either side of what has focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RevealWithRoom(content: @Composable () -> Unit) {
    val room = with(LocalDensity.current) { (LocalLook.current.rowHeight * 3 / 4).toPx() }
    val spec = remember(room) { RoomyReveal(room) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
