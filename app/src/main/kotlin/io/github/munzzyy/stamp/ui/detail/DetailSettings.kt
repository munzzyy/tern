package io.github.munzzyy.stamp.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.UpdateMode
import io.github.munzzyy.stamp.engine.AppRow
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.apps.RemoveDialog
import io.github.munzzyy.stamp.ui.common.ActionRow
import io.github.munzzyy.stamp.ui.common.ChoiceRow
import io.github.munzzyy.stamp.ui.common.FileChoiceView
import io.github.munzzyy.stamp.ui.common.SwitchRow
import io.github.munzzyy.stamp.ui.common.rememberActions
import io.github.munzzyy.stamp.ui.common.textFieldKeys
import io.github.munzzyy.stamp.ui.text.breakableFingerprint
import io.github.munzzyy.stamp.ui.text.canPickInstall
import io.github.munzzyy.stamp.ui.text.formatFingerprint

private val MIN_AGE_CHOICES = listOf(0, 1, 3, 7, 14, 30)

fun LazyListScope.settings(vm: DetailViewModel, row: AppRow, onRemoved: () -> Unit) {
    item(key = "s-updates") { UpdatesGroup(vm, row.config) }
    item(key = "s-files") { FilesGroup(vm, row) }
    item(key = "s-advanced") { AdvancedGroup(vm, row.config) }
    item(key = "s-remove") { RemoveGroup(row, onRemoved) }
}

@Composable
private fun saver(vm: DetailViewModel): ((AppConfig) -> AppConfig) -> Unit {
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    return { change -> vm.save(change) { actions.say(failed) } }
}

@Composable
private fun GroupTitle(text: String) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle(text) }
}

@Composable
fun updateModeLabel(mode: UpdateMode): String = stringResource(
    when (mode) {
        UpdateMode.NOTIFY -> R.string.mode_notify
        UpdateMode.AUTO -> R.string.mode_auto
        UpdateMode.MANUAL -> R.string.mode_manual
    },
)

@Composable
fun updateModeEffect(mode: UpdateMode): String = stringResource(
    when (mode) {
        UpdateMode.NOTIFY -> R.string.mode_notify_effect
        UpdateMode.AUTO -> R.string.mode_auto_effect
        UpdateMode.MANUAL -> R.string.mode_manual_effect
    },
)

@Composable
fun minAgeLabel(days: Int): String =
    if (days == 0) stringResource(R.string.min_age_none) else pluralStringResource(R.plurals.min_age_days, days, days)

@Composable
private fun UpdatesGroup(vm: DetailViewModel, config: AppConfig) {
    val save = saver(vm)
    Column(Modifier.widthIn(max = 840.dp)) {
        GroupTitle(stringResource(R.string.group_updates))
        ChoiceRow(
            title = stringResource(R.string.setting_update_mode),
            options = UpdateMode.entries,
            selected = config.updates,
            label = { updateModeLabel(it) },
            summary = updateModeEffect(config.updates),
            onSelect = { mode -> save { it.copy(updates = mode) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_prereleases),
            summary = stringResource(if (config.releases.includePrereleases) R.string.setting_prereleases_on else R.string.setting_prereleases_off),
            checked = config.releases.includePrereleases,
            onChange = { on -> save { it.copy(releases = it.releases.copy(includePrereleases = on)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.setting_min_age),
            options = (MIN_AGE_CHOICES + config.releases.minAgeDays).distinct().sorted(),
            selected = config.releases.minAgeDays,
            label = { minAgeLabel(it) },
            summary = stringResource(R.string.setting_min_age_effect),
            onSelect = { days -> save { it.copy(releases = it.releases.copy(minAgeDays = days)) } },
        )
    }
}

@Composable
private fun FilesGroup(vm: DetailViewModel, row: AppRow) {
    val engine = LocalEngine.current
    val save = saver(vm)
    val config = row.config
    val draft = vm.draftFor(config)
    val invalid = draft.invalid
    Column(Modifier.widthIn(max = 840.dp)) {
        GroupTitle(stringResource(R.string.group_files))
        val ranked = listOfNotNull(row.file) + row.otherFiles
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ranked.isEmpty()) {
                Text(stringResource(R.string.files_none), style = MaterialTheme.typography.bodyMedium)
            }
            ranked.forEachIndexed { index, choice ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(if (index == 0) R.string.file_recommended else R.string.file_alternative, index + 1),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FileChoiceView(choice)
                    if (index > 0 && canPickInstall(row)) {
                        OutlinedButton(onClick = { engine.install(row.id, assetUrl = choice.asset.url) }) {
                            Text(stringResource(R.string.action_install_file))
                        }
                    }
                }
            }
        }
        SwitchRow(
            title = stringResource(R.string.setting_match_device),
            summary = stringResource(R.string.setting_match_device_effect),
            checked = config.assets.matchDevice,
            onChange = { on -> save { it.copy(assets = it.assets.copy(matchDevice = on)) } },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PatternField(R.string.setting_include, R.string.setting_include_help, draft.include, "include" in invalid) { v ->
                vm.editDraft(config) { it.copy(include = v) }
            }
            PatternField(R.string.setting_exclude, R.string.setting_exclude_help, draft.exclude, "exclude" in invalid) { v ->
                vm.editDraft(config) { it.copy(exclude = v) }
            }
            SaveDraft(vm, config)
        }
    }
}

@Composable
private fun SaveDraft(vm: DetailViewModel, config: AppConfig) {
    if (!vm.isDirty(config)) return
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    val invalid = vm.draftFor(config).invalid.isNotEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { vm.editDraft(config) { PatternDraft.of(config) } }) { Text(stringResource(R.string.action_discard)) }
        Button(onClick = { vm.saveDraft(config) { actions.say(failed) } }, enabled = !invalid) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun PatternField(label: Int, help: Int, value: String, invalid: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(500)) },
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(if (invalid) R.string.pattern_invalid else help)) },
        isError = invalid,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().textFieldKeys(),
    )
}

@Composable
private fun AdvancedGroup(vm: DetailViewModel, config: AppConfig) {
    var open by rememberSaveable(config.id) { mutableStateOf(false) }
    val save = saver(vm)
    val draft = vm.draftFor(config)
    val invalid = draft.invalid
    Column(Modifier.widthIn(max = 840.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ActionRow(
            title = stringResource(R.string.group_advanced),
            summary = stringResource(if (open) R.string.advanced_hide else R.string.advanced_show),
            icon = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            onClick = { open = !open },
        )
        if (!open) return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PatternField(R.string.setting_tag_filter, R.string.setting_tag_filter_help, draft.tag, "tag" in invalid) { v ->
                vm.editDraft(config) { it.copy(tag = v) }
            }
            PatternField(R.string.setting_title_filter, R.string.setting_title_filter_help, draft.title, "title" in invalid) { v ->
                vm.editDraft(config) { it.copy(title = v) }
            }
            PatternField(R.string.setting_notes_filter, R.string.setting_notes_filter_help, draft.notes, "notes" in invalid) { v ->
                vm.editDraft(config) { it.copy(notes = v) }
            }
            PatternField(R.string.setting_version_pattern, R.string.setting_version_pattern_help, draft.version, "version" in invalid) { v ->
                vm.editDraft(config) { it.copy(version = v) }
            }
            OutlinedTextField(
                value = draft.categories,
                onValueChange = { v -> vm.editDraft(config) { it.copy(categories = v.take(500)) } },
                label = { Text(stringResource(R.string.setting_categories)) },
                supportingText = { Text(stringResource(R.string.setting_categories_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
            SaveDraft(vm, config)
        }
        SwitchRow(
            title = stringResource(R.string.setting_fallback),
            summary = stringResource(R.string.setting_fallback_effect),
            checked = config.releases.fallbackToOlder,
            onChange = { on -> save { it.copy(releases = it.releases.copy(fallbackToOlder = on)) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_track_only),
            summary = stringResource(R.string.setting_track_only_effect),
            checked = config.trackOnly,
            onChange = { on -> save { it.copy(trackOnly = on) } },
        )
        PinnedSigners(config, save)
    }
}

@Composable
private fun PinnedSigners(config: AppConfig, save: ((AppConfig) -> AppConfig) -> Unit) {
    var entry by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pins_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (config.pinnedSigners.isEmpty()) R.string.pins_none else R.string.pins_effect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (pin in config.pinnedSigners) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(breakableFingerprint(formatFingerprint(pin)), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = { save { c -> c.copy(pinnedSigners = c.pinnedSigners - pin) } }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pins_remove))
                }
            }
        }
        OutlinedTextField(
            value = entry,
            onValueChange = {
                entry = it.take(200)
                bad = false
            },
            label = { Text(stringResource(R.string.pins_add_label)) },
            supportingText = { Text(stringResource(if (bad) R.string.pins_add_invalid else R.string.pins_add_help)) },
            isError = bad,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().textFieldKeys(),
        )
        OutlinedButton(
            onClick = {
                val fp = normalizeFingerprint(entry)
                if (fp == null) {
                    bad = true
                } else {
                    save { c -> c.copy(pinnedSigners = (c.pinnedSigners + fp).distinct()) }
                    entry = ""
                }
            },
            enabled = entry.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.pins_add)) }
    }
}

@Composable
private fun RemoveGroup(row: AppRow, onRemoved: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 840.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ActionRow(
            title = stringResource(R.string.action_remove),
            summary = stringResource(if (row.installed != null) R.string.remove_text_installed else R.string.remove_text),
            icon = Icons.Filled.Delete,
            tint = MaterialTheme.colorScheme.error,
            onClick = { confirm = true },
        )
    }
    if (confirm) RemoveDialog(row, onDismiss = { confirm = false }, onRemoved = onRemoved)
}
