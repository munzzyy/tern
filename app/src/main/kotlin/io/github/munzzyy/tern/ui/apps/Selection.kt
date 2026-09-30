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
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import androidx.compose.runtime.LaunchedEffect
import io.github.munzzyy.tern.core.interop.ConfigLink
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.common.Actions
import io.github.munzzyy.tern.ui.common.ChoiceDialog
import io.github.munzzyy.tern.ui.common.ColorDot
import io.github.munzzyy.tern.ui.common.ConfirmDialog
import io.github.munzzyy.tern.ui.common.SaveFileDialog
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
import io.github.munzzyy.tern.ui.settings.allCategories
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.categoryColor
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
                BulkAction.CATEGORY to R.string.action_change_categories,
                BulkAction.CHECK to R.string.action_check_now,
                BulkAction.UPDATE to R.string.action_update,
                BulkAction.INSTALL to R.string.action_install,
                BulkAction.FAVORITE to if (favoriteAfter(picked)) R.string.action_favorite else R.string.action_unfavorite,
                BulkAction.MODE to R.string.action_set_mode,
                BulkAction.SHARE_ADDRESSES to R.string.action_share_addresses,
                BulkAction.SHARE_LINKS to R.string.action_share_config_links,
                BulkAction.SHARE_EXPORT to R.string.action_share_export,
                BulkAction.MARK_SEEN to R.string.action_mark_seen,
                BulkAction.SAVE_FILES to R.string.action_save_files,
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
    BulkAction.UPDATE, BulkAction.INSTALL -> online && touched(action, picked).isNotEmpty()
    BulkAction.UNINSTALL, BulkAction.MARK_SEEN -> touched(action, picked).isNotEmpty()
    BulkAction.SAVE_FILES -> online && touched(action, picked).isNotEmpty()
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
            QuietButton(stringResource(R.string.action_change_categories), onClick = { onAction(BulkAction.CATEGORY) })
            val netState = if (online) Modifier else Modifier.semantics { stateDescription = offline }
            QuietButton(stringResource(R.string.action_check_now), onClick = { onAction(BulkAction.CHECK) }, modifier = netState, enabled = available(BulkAction.CHECK, picked, online))
            QuietButton(stringResource(R.string.action_update), onClick = { onAction(BulkAction.UPDATE) }, modifier = netState, enabled = available(BulkAction.UPDATE, picked, online))
            QuietButton(stringResource(R.string.action_install), onClick = { onAction(BulkAction.INSTALL) }, modifier = netState, enabled = available(BulkAction.INSTALL, picked, online))
            QuietButton(stringResource(R.string.action_remove), onClick = { onAction(BulkAction.REMOVE) }, ink = MaterialTheme.colorScheme.error)
            BulkMenu(picked, onAction)
        }
    }
}

/** The confirmation for [action], naming how many apps it touches; [onDone] runs once the user agrees. */
@Composable
fun BulkDialog(action: BulkAction, picked: List<AppRow>, engine: Engine, actions: Actions, onDismiss: () -> Unit, onDone: () -> Unit) {
    val touched = touched(action, picked)
    val n = touched.size
    when (action) {
        BulkAction.CATEGORY -> {
            val resources = LocalContext.current.resources
            val rows = engine.apps.collectAsStateWithLifecycle().value
            val colors = engine.settings.collectAsStateWithLifecycle().value.categoryColors
            val names = remember(rows, colors) { allCategories(rows, colors) }
            CategoriesDialog(touched, names, colors, onDismiss) { changes ->
                actions.run {
                    for (row in touched) engine.configure(row.id) { withCategories(it, changes) }
                    actions.say(resources.getQuantityString(R.plurals.bulk_categories_done, n, n))
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
        BulkAction.MARK_SEEN -> {
            val words = pluralStringResource(R.plurals.bulk_marked_seen, touched.size, touched.size)
            LaunchedEffect(Unit) {
                actions.run { for (row in touched) engine.dismissRelease(row.id) }
                actions.say(words)
                onDismiss()
                onDone()
            }
        }
        BulkAction.SAVE_FILES -> SaveFiles(touched, engine, actions, onDismiss, onDone)
        BulkAction.INSTALL -> {
            val left = picked.size - n
            val text = stringResource(R.string.bulk_install_text) +
                if (left > 0) " " + pluralStringResource(R.plurals.bulk_install_left, left, left) else ""
            ConfirmDialog(
                title = pluralStringResource(R.plurals.bulk_install_title, n, n),
                text = text,
                confirm = stringResource(R.string.action_install),
                onConfirm = {
                    touched.forEach { engine.install(it.id) }
                    onDone()
                },
                onDismiss = onDismiss,
            )
        }
        BulkAction.SHARE_LINKS -> {
            val context = LocalContext.current
            val title = stringResource(R.string.action_share_config_links)
            val links = remember(touched) { touched.mapNotNull { ConfigLink.web(it.config) } }
            val left = n - links.size
            val leftOut = if (left > 0) pluralStringResource(R.plurals.share_links_left, left, left) else null
            LaunchedEffect(Unit) {
                // One link a line, as Obtainium shares them: each one opens by itself.
                if (links.isNotEmpty()) shareText(context, title, links.joinToString("\n"))
                leftOut?.let(actions::say)
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

/**
 * One app after the other, a picker of every file of its offered release, with the file Tern would
 * install picked at first, as Obtainium asks. Each file picked is saved by the engine, which says
 * in a notification when it is done; Skip passes over an app, and leaving the picker stops.
 */
@Composable
private fun SaveFiles(rows: List<AppRow>, engine: Engine, actions: Actions, onDismiss: () -> Unit, onDone: () -> Unit) {
    var at by rememberSaveable { mutableIntStateOf(0) }
    var started by rememberSaveable { mutableIntStateOf(0) }
    val row = rows.getOrNull(at)
    val release = row?.latest
    if (row == null || release == null) {
        val words = pluralStringResource(R.plurals.files_bulk_saving, started, started)
        LaunchedEffect(Unit) {
            if (started > 0) actions.say(words)
            onDismiss()
            onDone()
        }
        return
    }
    SaveFileDialog(
        title = stringResource(R.string.files_pick_title_app, row.config.shownName),
        release = release,
        preselected = row.file?.asset?.url,
        onSave = { file ->
            actions.run { engine.saveFile(row.id, release.id, file.url) }
            started++
            at++
        },
        onSkip = { at++ },
        onDismiss = { at = rows.size },
    )
}

/**
 * Every category, each with a box that says whether all, some or none of the picked [rows] are
 * filed under it. A press turns a box on and off, and back to "some" where it started there. On
 * [onSave] every app is filed under the ticked categories and taken out of the empty ones; a
 * category left at "some" stays as each app has it. A new one can be named at the top.
 */
@Composable
private fun CategoriesDialog(rows: List<AppRow>, names: List<String>, colors: Map<String, Int>, onDismiss: () -> Unit, onSave: (Map<String, Filed>) -> Unit) {
    val look = LocalLook.current
    val before = remember(rows, names) { names.associateWith { filedUnder(rows, it) } }
    var chosen by remember { mutableStateOf(before) }
    var text by rememberSaveable { mutableStateOf("") }
    val typed = cleanCategory(text)?.let { canonicalCategory(it, names) }
    val changes = LinkedHashMap(chosen.filter { (name, filed) -> filed != before[name] })
    if (typed != null) changes[typed] = Filed.ALL
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.bulk_categories_title, rows.size, rows.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_CATEGORY) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.categories_new)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().textFieldKeys().testTag(BULK_CATEGORY_FIELD_TAG),
                )
                if (names.isNotEmpty()) {
                    Text(stringResource(R.string.bulk_categories_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        for (name in names) {
                            val filed = chosen[name] ?: Filed.NONE
                            CategoryBox(name, filed, categoryColor(name, colors)) {
                                chosen = chosen + (name to nextFiled(filed, before[name] ?: Filed.NONE))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(changes); onDismiss() }, enabled = changes.isNotEmpty()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CategoryBox(name: String, filed: Filed, color: Color, onPress: () -> Unit) {
    val look = LocalLook.current
    val state = when (filed) {
        Filed.ALL -> ToggleableState.On
        Filed.SOME -> ToggleableState.Indeterminate
        Filed.NONE -> ToggleableState.Off
    }
    val spoken = stringResource(
        when (filed) {
            Filed.ALL -> R.string.categories_filed_all
            Filed.SOME -> R.string.categories_filed_some
            Filed.NONE -> R.string.categories_filed_none
        },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .focusLook()
            .clip(MaterialTheme.shapes.medium)
            .triStateToggleable(state = state, role = Role.Checkbox, onClick = onPress)
            .semantics { stateDescription = spoken }
            .heightIn(min = look.touchTarget)
            .padding(horizontal = look.gapSmall),
    ) {
        TriStateCheckbox(state = state, onClick = null)
        ColorDot(color)
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}
