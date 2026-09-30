package io.github.munzzyy.tern.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.confirmInstall
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.rememberHaptics
import io.github.munzzyy.tern.ui.icons.Bin
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.text.RowAction
import io.github.munzzyy.tern.ui.text.inlineAction
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlinx.coroutines.launch

/**
 * A row that can be swiped: towards the end it runs the row's own action (update, install or
 * confirm), towards the start it removes the app, which the snackbar can still take back. Either
 * way the row springs back; nothing happens that the row's buttons could not do too.
 */
@Composable
fun SwipeRow(row: AppRow, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val action = inlineAction(row)
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = action != null,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            if (value != SwipeToDismissBoxValue.Settled) haptics.threshold()
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> when (action) {
                    RowAction.UPDATE, RowAction.INSTALL -> engine.install(row.id)
                    RowAction.CONFIRM -> confirmInstall(engine, row.id, actions)
                    else -> Unit
                }
                SwipeToDismissBoxValue.EndToStart -> onRemove()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { state.reset() }
        },
        backgroundContent = { SwipeBackground(state.dismissDirection, action) },
    ) { content() }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, action: RowAction?) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val (color, ink) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> scheme.primaryContainer to scheme.onPrimaryContainer
        SwipeToDismissBoxValue.EndToStart -> scheme.errorContainer to scheme.onErrorContainer
        SwipeToDismissBoxValue.Settled -> Color.Transparent to Color.Transparent
    }
    val start = direction == SwipeToDismissBoxValue.StartToEnd
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall, if (start) Alignment.Start else Alignment.End),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .clip(MaterialTheme.shapes.large)
            .background(color)
            .padding(horizontal = look.rowPaddingHorizontal),
    ) {
        if (direction == SwipeToDismissBoxValue.Settled) return@Row
        val words = if (start) action?.let { stringResource(it.text) } else stringResource(R.string.action_remove)
        Icon(if (start) Glyphs.Update else Glyphs.Bin, contentDescription = null, tint = ink, modifier = Modifier.size(look.glyph))
        if (words != null) Text(words, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}
