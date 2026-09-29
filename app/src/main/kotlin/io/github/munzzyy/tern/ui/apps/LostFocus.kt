package io.github.munzzyy.tern.ui.apps

import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * Tells a thing that vanished under the focus from one the user walked away from. Compose takes
 * the focus off a thing a moment before it lets go of it, so what counts is having had focus
 * until just now.
 */
class LostFocus {
    private var focused = false
    private var lostAtMs: Long? = null

    fun seen(hasFocus: Boolean, nowMs: Long) {
        if (focused && !hasFocus) lostAtMs = nowMs
        focused = hasFocus
    }

    fun hadFocusUntilNow(nowMs: Long): Boolean = focused || lostAtMs?.let { nowMs - it in 0..JUST_NOW_MS } == true

    private companion object {
        const val JUST_NOW_MS = 250L
    }
}

/** Calls [onGone] when what it is put on leaves the screen while it has focus. Put it before the clickable. */
fun Modifier.whenGoneWithFocus(onGone: () -> Unit): Modifier = composed {
    val gone by rememberUpdatedState(onGone)
    val watch = remember { LostFocus() }
    DisposableEffect(Unit) {
        onDispose { if (watch.hadFocusUntilNow(SystemClock.uptimeMillis())) gone() }
    }
    onFocusChanged { watch.seen(it.hasFocus, SystemClock.uptimeMillis()) }
}
