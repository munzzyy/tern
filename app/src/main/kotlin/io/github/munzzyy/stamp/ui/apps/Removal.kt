package io.github.munzzyy.stamp.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.AppRow
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.LocalSnackbar
import io.github.munzzyy.stamp.ui.common.LocalNoTouch
import io.github.munzzyy.stamp.ui.common.focusLook
import io.github.munzzyy.stamp.ui.common.focusWhenShown
import io.github.munzzyy.stamp.ui.common.rememberActions
import io.github.munzzyy.stamp.ui.theme.LocalLook
import io.github.munzzyy.stamp.ui.theme.LocalOutlines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val SNACKBAR_ACTION_TAG = "snackbar_action"

/** Apps taken off the list that can still be taken back. While one is here the list leaves it out and the engine still has it. */
class Removals {
    private val _hidden = MutableStateFlow(emptySet<String>())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    /**
     * Hides the app and waits for [offerUndo]. Only when that says no is [remove] called. When
     * the wait is cut short, as it is when the app is closed, nothing is removed.
     */
    suspend fun remove(appId: String, offerUndo: suspend () -> Boolean, remove: suspend () -> Unit) {
        _hidden.update { it + appId }
        try {
            if (!offerUndo()) remove()
        } finally {
            _hidden.update { it - appId }
        }
    }
}

class RemovalsHolder : ViewModel() {
    val removals = Removals()
}

/** One for the whole window, so the list and the detail screen see the same apps as gone. */
@Composable
fun rememberRemovals(): Removals = viewModel(key = "removals") { RemovalsHolder() }.removals

/** Takes an app off the list and offers Undo for as long as the snackbar shows. An earlier offer ends when a new one starts. */
@Composable
fun rememberRemove(): (AppRow) -> Unit {
    val engine = LocalEngine.current
    val removals = rememberRemovals()
    val snackbar = LocalSnackbar.current
    val actions = rememberActions()
    val resources = LocalContext.current.resources
    return remember(engine, removals, snackbar, actions, resources) {
        { row ->
            actions.run {
                removals.remove(
                    appId = row.id,
                    offerUndo = { offerUndo(snackbar, resources.getString(R.string.removed_notice, row.config.name), resources.getString(R.string.removed_undo)) },
                    remove = { engine.remove(row.id) },
                )
            }
        }
    }
}

private suspend fun offerUndo(snackbar: SnackbarHostState, message: String, undo: String): Boolean {
    snackbar.currentSnackbarData?.dismiss()
    return snackbar.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
}

private const val STACK_FONT_SCALE = 1.5f

/**
 * Draws what [state] holds. Without a touch screen the action takes focus when it appears, since
 * a remote has no other way to reach it, and [onActionGone] is called when the action leaves
 * with the focus on it, so the screen can place focus again.
 */
@Composable
fun StampSnackbarHost(state: SnackbarHostState = LocalSnackbar.current, onActionGone: () -> Unit = {}) {
    val look = LocalLook.current
    SnackbarHost(state) { data ->
        val label = data.visuals.actionLabel
        Snackbar(
            modifier = Modifier.padding(horizontal = look.screenPadding, vertical = look.gapSmall),
            shape = MaterialTheme.shapes.medium,
            actionOnNewLine = LocalDensity.current.fontScale >= STACK_FONT_SCALE,
            action = if (label == null) {
                null
            } else {
                { SnackbarAction(label, data::performAction, onActionGone) }
            },
        ) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SnackbarAction(label: String, onClick: () -> Unit, onGone: () -> Unit) {
    val look = LocalLook.current
    val shape = LocalOutlines.current.button
    val ink = MaterialTheme.colorScheme.inversePrimary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .testTag(SNACKBAR_ACTION_TAG)
            .then(if (LocalNoTouch.current) Modifier.focusWhenShown() else Modifier)
            .whenGoneWithFocus(onGone)
            .focusLook(shape, ring = ink)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = look.buttonHeight)
            .padding(horizontal = look.gap),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}
