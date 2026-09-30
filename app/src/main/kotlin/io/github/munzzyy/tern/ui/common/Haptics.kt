package io.github.munzzyy.tern.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.ui.LocalEngine

/** A light tap under the finger when a gesture does something, unless the setting turned it off. */
class Haptics(private val feedback: HapticFeedback, private val on: Boolean) {
    /** A swipe went far enough to act. */
    fun threshold() = play(HapticFeedbackType.GestureThresholdActivate)

    /** A long press picked something. */
    fun longPress() = play(HapticFeedbackType.LongPress)

    /** Something the person asked for was done. */
    fun confirm() = play(HapticFeedbackType.Confirm)

    private fun play(type: HapticFeedbackType) {
        if (on) feedback.performHapticFeedback(type)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val settings by LocalEngine.current.settings.collectAsStateWithLifecycle()
    val on = settings.haptics
    return remember(feedback, on) { Haptics(feedback, on) }
}
