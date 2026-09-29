package io.github.munzzyy.jackdaw.ui.apps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.LocalOnline
import io.github.munzzyy.jackdaw.ui.common.ConfirmDialog
import io.github.munzzyy.jackdaw.ui.common.StatusPill
import io.github.munzzyy.jackdaw.ui.common.focusRing
import io.github.munzzyy.jackdaw.ui.common.confirmInstall
import io.github.munzzyy.jackdaw.ui.common.rememberActions
import io.github.munzzyy.jackdaw.ui.icons.AppIcon
import io.github.munzzyy.jackdaw.ui.text.RowAction
import io.github.munzzyy.jackdaw.ui.text.canSkip
import io.github.munzzyy.jackdaw.ui.text.inlineAction
import io.github.munzzyy.jackdaw.ui.text.isWaitingForUser
import io.github.munzzyy.jackdaw.ui.text.statusLabel
import io.github.munzzyy.jackdaw.ui.text.versionChange

/** At this font scale the inline button moves under the text so the name keeps its width. */
private const val STACK_FONT_SCALE = 1.5f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRowItem(row: AppRow, selected: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val action = inlineAction(row)
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val noLauncher = stringResource(R.string.open_no_launcher)

    val runAction: (RowAction) -> Unit = { a ->
        when (a) {
            RowAction.UPDATE, RowAction.INSTALL -> engine.install(row.id)
            RowAction.CONFIRM -> confirmInstall(engine, row.id, actions)
            else -> Unit
        }
    }
    val openApp: () -> Unit = {
        if (!engine.open(row.id)) actions.say(noLauncher)
    }
    val checkNow: () -> Unit = { actions.run { engine.check(row.id) } }
    val skip: () -> Unit = { actions.run { engine.dismissRelease(row.id) } }

    val labelDetails = stringResource(R.string.action_details)
    val labelOpen = stringResource(R.string.action_open)
    val labelCancel = stringResource(R.string.action_cancel)
    val labelCheck = stringResource(R.string.action_check_now)
    val labelSkip = stringResource(R.string.action_skip_version)
    val labelRemove = stringResource(R.string.action_remove)
    val labelAction = action?.let { stringResource(it.text) }
    val description = rowDescription(row)

    val rowSemantics = Modifier.clearAndSetSemantics {
        contentDescription = description
        this.selected = selected
        onClick(labelDetails) { onOpen(); true }
        customActions = buildList {
            if (action != null && labelAction != null) add(CustomAccessibilityAction(labelAction) { runAction(action); true })
            if (isWaitingForUser(row)) add(CustomAccessibilityAction(labelCancel) { engine.cancel(row.id); true })
            if (row.installed != null) add(CustomAccessibilityAction(labelOpen) { openApp(); true })
            add(CustomAccessibilityAction(labelDetails) { onOpen(); true })
            add(CustomAccessibilityAction(labelCheck) { checkNow(); true })
            if (canSkip(row)) add(CustomAccessibilityAction(labelSkip) { skip(); true })
            add(CustomAccessibilityAction(labelRemove) { confirmRemove = true; true })
        }
    }
    val actionButton: @Composable () -> Unit = {
        if (action != null && labelAction != null) {
            FilledTonalButton(onClick = { runAction(action) }) { Text(labelAction) }
        }
    }
    val overflow: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (row.installed != null) {
                    DropdownMenuItem(text = { Text(labelOpen) }, onClick = { menu = false; openApp() })
                }
                DropdownMenuItem(text = { Text(labelDetails) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text(labelCheck) }, onClick = { menu = false; checkNow() })
                if (canSkip(row)) {
                    DropdownMenuItem(text = { Text(labelSkip) }, onClick = { menu = false; skip() })
                }
                DropdownMenuItem(
                    text = { Text(labelRemove, color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmRemove = true },
                )
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(rowSemantics)
            .padding(end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 72.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 72.dp)
                    .focusRing(RectangleShape)
                    .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.action_more))
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                AppIcon(row)
                RowText(
                    row,
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 8.dp),
                )
            }
            if (!stacked) actionButton()
            overflow()
        }
        if (stacked && action != null) {
            Row(Modifier.padding(start = 72.dp, bottom = 8.dp)) { actionButton() }
        }
    }
    if (confirmRemove) {
        RemoveDialog(row, onDismiss = { confirmRemove = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowText(row: AppRow, modifier: Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        Text(
            row.config.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(statusLabel(row, LocalOnline.current))
            val secondary = row.progress?.let { progressText(it) } ?: versionText(versionChange(row))
            secondary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        row.progress?.let { p ->
            val fraction = p.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            } else if (p.phase != Phase.WAITING_FOR_USER) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun RemoveDialog(row: AppRow, onDismiss: () -> Unit, onRemoved: () -> Unit = {}) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    ConfirmDialog(
        title = stringResource(R.string.remove_title, row.config.name),
        text = stringResource(if (row.installed != null) R.string.remove_text_installed else R.string.remove_text),
        confirm = stringResource(R.string.action_remove),
        onConfirm = {
            actions.run {
                engine.remove(row.id)
                onRemoved()
            }
        },
        onDismiss = onDismiss,
    )
}
