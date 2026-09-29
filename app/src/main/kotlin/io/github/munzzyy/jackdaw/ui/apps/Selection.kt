package io.github.munzzyy.jackdaw.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.ui.LocalOnline
import io.github.munzzyy.jackdaw.ui.common.Actions
import io.github.munzzyy.jackdaw.ui.common.ConfirmDialog
import io.github.munzzyy.jackdaw.ui.common.focusHighlight
import io.github.munzzyy.jackdaw.ui.common.focusWhenShown
import io.github.munzzyy.jackdaw.ui.common.textFieldKeys
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

const val BULK_BAR_TAG = "bulk_bar"
const val BULK_CATEGORY_FIELD_TAG = "bulk_category_field"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(picked: List<AppRow>, onClose: () -> Unit, onSelectAll: () -> Unit, onAction: (BulkAction) -> Unit) {
    val count = picked.size
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_stop_selecting)) }
        },
        title = { Text(pluralStringResource(R.plurals.apps_selected, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.action_select_all)) }
            BulkMenu(picked, onAction)
        },
    )
}

/** The bar's actions again at the top, so a D-pad reaches them without walking the whole list. */
@Composable
private fun BulkMenu(picked: List<AppRow>, onAction: (BulkAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val online = LocalOnline.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.focusHighlight()) {
            for ((action, label) in listOf(
                BulkAction.CATEGORY to R.string.action_add_to_category,
                BulkAction.CHECK to R.string.action_check_now,
                BulkAction.UPDATE to R.string.action_update,
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
    else -> picked.isNotEmpty()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkBar(picked: List<AppRow>, onAction: (BulkAction) -> Unit) {
    val online = LocalOnline.current
    val offline = stringResource(R.string.offline_reason)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(BULK_BAR_TAG)) {
        if (picked.isEmpty()) {
            Text(stringResource(R.string.apps_select_hint), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            return@Surface
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = { onAction(BulkAction.CATEGORY) }) { Text(stringResource(R.string.action_add_to_category)) }
            val netState = if (online) Modifier else Modifier.semantics { stateDescription = offline }
            TextButton(onClick = { onAction(BulkAction.CHECK) }, enabled = available(BulkAction.CHECK, picked, online), modifier = netState) {
                Text(stringResource(R.string.action_check_now))
            }
            TextButton(onClick = { onAction(BulkAction.UPDATE) }, enabled = available(BulkAction.UPDATE, picked, online), modifier = netState) {
                Text(stringResource(R.string.action_update))
            }
            TextButton(onClick = { onAction(BulkAction.REMOVE) }) {
                Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
            }
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
                    for (row in touched) engine.save(withCategory(row.config, chosen))
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
    }
}

@Composable
private fun CategoryDialog(count: Int, categories: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val clean = cleanCategory(text)
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.bulk_category_title, count, count)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = clean == category, role = Role.RadioButton, onClick = { text = category }),
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
