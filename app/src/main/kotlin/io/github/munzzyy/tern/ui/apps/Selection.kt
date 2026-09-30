package io.github.munzzyy.tern.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import io.github.munzzyy.tern.R
import androidx.compose.runtime.LaunchedEffect
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.common.Actions
import io.github.munzzyy.tern.ui.common.ChoiceDialog
import io.github.munzzyy.tern.ui.common.ConfirmDialog
import io.github.munzzyy.tern.ui.common.shareFile
import io.github.munzzyy.tern.ui.common.shareText
import io.github.munzzyy.tern.ui.detail.updateModeLabel
import io.github.munzzyy.tern.ui.common.GlyphButton
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.ScreenTop
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.icons.Close
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.More
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

const val BULK_BAR_TAG = "bulk_bar"
const val BULK_CATEGORY_FIELD_TAG = "bulk_category_field"

@Composable
fun SelectionTopBar(picked: List<AppRow>, onClose: () -> Unit, onSelectAll: () -> Unit, onAction: (BulkAction) -> Unit) {
    val count = picked.size
    ScreenTop(
        title = pluralStringResource(R.plurals.apps_selected, count, count),
        oneLine = true,
        leading = { GlyphButton(Glyphs.Close, stringResource(R.string.action_stop_selecting), onClose) },
    ) {
        QuietButton(stringResource(R.string.action_select_all), onSelectAll)
        BulkMenu(picked, onAction)
    }
}

/** The bar's actions again at the top, so a D-pad reaches them without walking the whole list. */
@Composable
private fun BulkMenu(picked: List<AppRow>, onAction: (BulkAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val online = LocalOnline.current
    Box {
        GlyphButton(Glyphs.More, stringResource(R.string.action_more), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.focusHighlight()) {
            for ((action, label) in listOf(
                BulkAction.CATEGORY to R.string.action_add_to_category,
                BulkAction.CHECK to R.string.action_check_now,
                BulkAction.UPDATE to R.string.action_update,
                BulkAction.FAVORITE to if (favoriteAfter(picked)) R.string.action_favorite else R.string.action_unfavorite,
                BulkAction.MODE to R.string.action_set_mode,
                BulkAction.SHARE_ADDRESSES to R.string.action_share_addresses,
                BulkAction.SHARE_EXPORT to R.string.action_share_export,
                BulkAction.UNINSTALL to R.string.action_uninstall,
                BulkAction.REMOVE to R.string.action_remove,
            )) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    enabled = available(action, picked, online),
                    onClick = {
                        open = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

fun available(action: BulkAction, picked: List<AppRow>, online: Boolean): Boolean = when (action) {
    BulkAction.CHECK -> online && picked.isNotEmpty()
    BulkAction.UPDATE -> online && touched(action, picked).isNotEmpty()
    BulkAction.UNINSTALL -> touched(action, picked).isNotEmpty()
    else -> picked.isNotEmpty()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkBar(picked: List<AppRow>, onAction: (BulkAction) -> Unit) {
    val look = LocalLook.current
    val online = LocalOnline.current
    val offline = stringResource(R.string.offline_reason)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag(BULK_BAR_TAG)) {
        if (picked.isEmpty()) {
            Text(
                stringResource(R.string.apps_select_hint),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = look.screenPadding, vertical = look.gap),
            )
            return@Surface
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(look.focusRoom),
            modifier = Modifier.padding(horizontal = look.focusRoom * 2, vertical = look.gapSmall / 2),
        ) {
            QuietButton(stringResource(R.string.action_add_to_category), onClick = { onAction(BulkAction.CATEGORY) })
            val netState = if (online) Modifier else Modifier.semantics { stateDescription = offline }
            QuietButton(stringResource(R.string.action_check_now), onClick = { onAction(BulkAction.CHECK) }, modifier = netState, enabled = available(BulkAction.CHECK, picked, online))
            QuietButton(stringResource(R.string.action_update), onClick = { onAction(BulkAction.UPDATE) }, modifier = netState, enabled = available(BulkAction.UPDATE, picked, online))
            QuietButton(stringResource(R.string.action_remove), onClick = { onAction(BulkAction.REMOVE) }, ink = MaterialTheme.colorScheme.error)
            BulkMenu(picked, onAction)
        }
    }
}

/** The confirmation for [action], naming how many apps it touches; [onDone] runs once the user agrees. */
@Composable
fun BulkDialog(action: BulkAction, picked: List<AppRow>, categories: List<String>, engine: Engine, actions: Actions, onDismiss: () -> Unit, onDone: () -> Unit) {
    val touched = touched(action, picked)
    val n = touched.size
    when (action) {
        BulkAction.CATEGORY -> {
            val filed = LocalContext.current.resources
            CategoryDialog(n, categories, onDismiss) { name ->
                val chosen = canonicalCategory(name, categories)
                actions.run {
                    for (row in touched) engine.configure(row.id) { withCategory(it, chosen) }
                    actions.say(filed.getQuantityString(R.plurals.bulk_category_done, n, n, chosen))
                }
                onDone()
            }
        }
        BulkAction.CHECK -> ConfirmDialog(
            title = pluralStringResource(R.plurals.bulk_check_title, n, n),
            text = stringResource(R.string.bulk_check_text),
            confirm = stringResource(R.string.action_check_now),
            onConfirm = {
                actions.run { coroutineScope { touched.map { async { engine.check(it.id) } }.awaitAll() } }
                onDone()
            },
            onDismiss = onDismiss,
        )
        BulkAction.UPDATE -> {
            val left = picked.size - n
            val text = stringResource(R.string.bulk_update_text) +
                if (left > 0) " " + pluralStringResource(R.plurals.bulk_update_left, left, left) else ""
            ConfirmDialog(
                title = pluralStringResource(R.plurals.bulk_update_title, n, n),
                text = text,
                confirm = stringResource(R.string.action_update),
                onConfirm = {
                    touched.forEach { engine.install(it.id) }
                    onDone()
                },
                onDismiss = onDismiss,
            )
        }
        BulkAction.REMOVE -> ConfirmDialog(
            title = pluralStringResource(R.plurals.bulk_remove_title, n, n),
            text = stringResource(R.string.bulk_remove_text),
            confirm = stringResource(R.string.action_remove),
            onConfirm = {
                actions.run { for (row in touched) engine.remove(row.id) }
                onDone()
            },
            onDismiss = onDismiss,
        )
        BulkAction.FAVORITE -> {
            val on = favoriteAfter(touched)
            val words = pluralStringResource(if (on) R.plurals.bulk_favorite_done else R.plurals.bulk_unfavorite_done, n, n)
            LaunchedEffect(Unit) {
                actions.run {
                    for (row in touched) engine.configure(row.id) { it.copy(favorite = on) }
                    actions.say(words)
                }
                onDismiss()
                onDone()
            }
        }
        BulkAction.MODE -> ChoiceDialog(
            title = pluralStringResource(R.plurals.bulk_mode_title, n, n),
            options = UpdateMode.entries,
            selected = touched.map { it.config.updates }.distinct().singleOrNull() ?: UpdateMode.NOTIFY,
            label = { updateModeLabel(it) },
            onDismiss = onDismiss,
        ) { mode ->
            actions.run { for (row in touched) engine.configure(row.id) { it.copy(updates = mode) } }
            onDismiss()
            onDone()
        }
        BulkAction.SHARE_ADDRESSES -> {
            val context = LocalContext.current
            val title = stringResource(R.string.action_share_addresses)
            LaunchedEffect(Unit) {
                shareText(context, title, addressList(touched))
                onDismiss()
                onDone()
            }
        }
        BulkAction.SHARE_EXPORT -> {
            val context = LocalContext.current
            val title = stringResource(R.string.action_share_export)
            ChoiceDialog(
                title = title,
                options = ExportFormat.entries,
                selected = ExportFormat.TERN,
                label = { stringResource(if (it == ExportFormat.TERN) R.string.export_format_tern else R.string.export_format_obtainium) },
                onDismiss = onDismiss,
            ) { format ->
                actions.run {
                    val uri = engine.shareableExport(touched.map { it.id }, format)
                    shareFile(context, title, uri)
                }
                onDismiss()
                onDone()
            }
        }
        BulkAction.UNINSTALL -> ConfirmDialog(
            title = pluralStringResource(R.plurals.bulk_uninstall_title, n, n),
            text = stringResource(R.string.bulk_uninstall_text),
            confirm = stringResource(R.string.action_uninstall),
            onConfirm = {
                for (row in touched) engine.uninstall(row.id)
                onDone()
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun CategoryDialog(count: Int, categories: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val look = LocalLook.current
    var text by rememberSaveable { mutableStateOf("") }
    val clean = cleanCategory(text)
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.bulk_category_title, count, count)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_CATEGORY) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bulk_category_field)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().textFieldKeys().testTag(BULK_CATEGORY_FIELD_TAG),
                )
                if (categories.isNotEmpty()) {
                    Text(stringResource(R.string.bulk_category_existing), style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.selectableGroup()) {
                        for (category in categories) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(look.gap),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
                                    .focusLook()
                                    .clip(MaterialTheme.shapes.medium)
                                    .selectable(selected = clean == category, role = Role.RadioButton, onClick = { text = category })
                                    .heightIn(min = look.touchTarget)
                                    .padding(horizontal = look.gapSmall),
                            ) {
                                RadioButton(selected = clean == category, onClick = null)
                                Text(category, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clean?.let(onConfirm); onDismiss() }, enabled = clean != null) {
                Text(stringResource(R.string.action_add_to_category))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) } },
    )
}
