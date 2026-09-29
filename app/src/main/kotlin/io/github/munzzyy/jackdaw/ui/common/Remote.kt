package io.github.munzzyy.jackdaw.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** True on a device without a touch screen, where a remote or a keyboard is all the user has. */
val LocalNoTouch = staticCompositionLocalOf { false }

fun lacksTouch(touchFeature: Boolean, touchscreen: Int, uiMode: Int): Boolean =
    !touchFeature ||
        touchscreen == Configuration.TOUCHSCREEN_NOTOUCH ||
        (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

fun Context.lacksTouch(): Boolean = lacksTouch(
    touchFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
    touchscreen = resources.configuration.touchscreen,
    uiMode = resources.configuration.uiMode,
)

@Composable
fun drivenByKeys(): Boolean = LocalNoTouch.current || LocalInputModeManager.current.inputMode == InputMode.Keyboard

/**
 * A pointer on a device without a touch screen, an air mouse for one, leaves the window in touch
 * mode, where only text fields take focus. Focus placed then lands on the first text field of the
 * screen, however far down it is. Leaving touch mode first lets it land where it should.
 */
private fun InputModeManager.leaveTouchMode() {
    if (inputMode != InputMode.Keyboard) requestInputMode(InputMode.Keyboard)
}

/** Up and down leave the field; without a touch screen a stop sits in front of it, since focusing the field itself opens the keyboard. */
fun Modifier.textFieldKeys(): Modifier = composed {
    if (!LocalNoTouch.current) return@composed verticalKeysLeave()
    val field = remember { FocusRequester() }
    val reveal = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var onStop by remember { mutableStateOf(false) }
    var inside by remember { mutableStateOf(false) }
    bringIntoViewRequester(reveal)
        .onFocusChanged {
            if (it.isFocused && !onStop) scope.launch { reveal.bringIntoView() }
            onStop = it.isFocused
            inside = it.hasFocus
        }
        .onKeyEvent { event ->
            if (onStop && event.type == KeyEventType.KeyDown && event.key in OPEN_KEYS) {
                field.requestFocus()
                keyboard?.show()
                true
            } else {
                false
            }
        }
        .focusRing(RoundedCornerShape(4.dp))
        .semantics { focused = inside }
        .focusTarget()
        .focusRequester(field)
        .verticalKeysLeave()
}

private val OPEN_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Where focus lands when a screen opens under keys: the control that opened the screen above it, else [first], else [backup]. */
@Stable
class ScreenFocus internal constructor(internal var last: String?) {
    internal val first = FocusRequester()
    internal val backup = FocusRequester()
    internal val slots = mutableMapOf<String, FocusRequester>()

    internal suspend fun land() {
        for (frame in 0 until LAND_FRAMES) {
            withFrameNanos {}
            val waitForSlot = frame < SLOT_WAIT_FRAMES
            for (candidate in landingOrder(last, slots, first, backup, waitForSlot)) {
                if (candidate.tryFocus()) return
            }
        }
    }

    private companion object {
        const val LAND_FRAMES = 90
        const val SLOT_WAIT_FRAMES = 6
    }
}

/** The requesters to try in order; while [waitForSlot] the remembered control may simply not be laid out yet. */
fun <T> landingOrder(last: String?, slots: Map<String, T>, first: T, backup: T, waitForSlot: Boolean): List<T> {
    val remembered = last?.let(slots::get)
    return when {
        remembered != null -> listOf(remembered, first, backup)
        waitForSlot && last != null -> emptyList()
        else -> listOf(first, backup)
    }
}

/** [active] false holds focus where it is, as the list does while a detail beside it has focus; a new [again] lands once more. */
@Composable
fun rememberScreenFocus(active: Boolean = true, again: Int = 0): ScreenFocus {
    val screen = rememberSaveable(saver = Saver<ScreenFocus, String>(save = { it.last.orEmpty() }, restore = { ScreenFocus(it.ifEmpty { null }) })) {
        ScreenFocus(null)
    }
    val keys = drivenByKeys()
    val input = LocalInputModeManager.current
    LaunchedEffect(screen, active, again) {
        if (active && keys) {
            input.leaveTouchMode()
            screen.land()
        }
    }
    return screen
}

fun Modifier.firstFocus(screen: ScreenFocus): Modifier = focusRequester(screen.first)

fun Modifier.backupFocus(screen: ScreenFocus): Modifier = focusRequester(screen.backup)

/** A control focus comes back to when the user returns from the screen it opened. Put it before clickable. */
fun Modifier.returnFocus(screen: ScreenFocus, key: String): Modifier = composed {
    val requester = remember { FocusRequester() }
    DisposableEffect(screen, key) {
        screen.slots[key] = requester
        onDispose { if (screen.slots[key] === requester) screen.slots.remove(key) }
    }
    onFocusChanged { if (it.hasFocus) screen.last = key }.focusRequester(requester)
}

/** Takes focus when it appears under keys, like a dialog's safest action; [revealTop] scrolls a result's start back into view. */
fun Modifier.focusWhenShown(revealTop: Boolean = false): Modifier = composed {
    val requester = remember { FocusRequester() }
    val reveal = remember { BringIntoViewRequester() }
    val keys = drivenByKeys()
    val input = LocalInputModeManager.current
    LaunchedEffect(requester) {
        if (!keys) return@LaunchedEffect
        input.leaveTouchMode()
        for (frame in 0 until 30) {
            withFrameNanos {}
            if (requester.tryFocus()) {
                if (revealTop) {
                    withFrameNanos {}
                    reveal.bringIntoView(Rect(0f, 0f, 1f, 1f))
                }
                return@LaunchedEffect
            }
        }
    }
    bringIntoViewRequester(reveal).focusRequester(requester)
}

private fun FocusRequester.tryFocus(): Boolean = try {
    requestFocus()
} catch (_: IllegalStateException) {
    false
}

/** Up and down stay inside this pane, so they never land on the navigation rail beside it; left and right still leave. */
fun Modifier.verticalFocusStaysInside(): Modifier =
    focusProperties {
        onExit = {
            if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
        }
    }.focusGroup()
