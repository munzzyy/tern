package io.github.munzzyy.tern.ui.detail

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.apps.rememberRemove
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.ChoiceRow
import io.github.munzzyy.tern.ui.common.FileChoiceView
import io.github.munzzyy.tern.ui.common.FileWords
import io.github.munzzyy.tern.ui.common.GlyphButton
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.SwitchRow
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.icons.Bin
import io.github.munzzyy.tern.ui.icons.Close
import io.github.munzzyy.tern.ui.icons.Collapse
import io.github.munzzyy.tern.ui.icons.Expand
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.text.breakableFingerprint
import io.github.munzzyy.tern.ui.text.canPickInstall
import io.github.munzzyy.tern.ui.text.formatFingerprint
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.text.ltr
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.fingerprint

private val MIN_AGE_CHOICES = listOf(0, 1, 2, 3, 5, 7, 14, 30)

fun LazyListScope.settings(vm: DetailViewModel, row: AppRow, onRemoved: () -> Unit) {
    item(key = "s-files") { FilesGroup(vm, row) }
    if (row.config.source.type in SOURCES_WITH_OPTIONS) item(key = "s-source") { SourceOptionsCard(vm, row.config) }
    item(key = "s-updates") { UpdatesGroup(vm, row.config) }
    item(key = "s-name") { NameGroup(vm, row.config) }
    item(key = "s-advanced") { AdvancedGroup(vm, row.config) }
    item(key = "s-remove") { RemoveGroup(row, onRemoved) }
}

@Composable
fun versionFromLabel(from: VersionFrom): String = stringResource(
    when (from) {
        VersionFrom.TAG -> R.string.version_from_tag
        VersionFrom.TITLE -> R.string.version_from_title
        VersionFrom.DATE -> R.string.version_from_date
    },
)

@Composable
fun orderLabel(order: ReleaseOrder): String = stringResource(
    when (order) {
        ReleaseOrder.VERSION -> R.string.order_version
        ReleaseOrder.DATE -> R.string.order_date
        ReleaseOrder.SOURCE -> R.string.order_source
        ReleaseOrder.NAME -> R.string.order_name
    },
)

/** The name and author the person gives the app, in place of what the source says. */
@Composable
private fun NameGroup(vm: DetailViewModel, config: AppConfig) {
    val draft = vm.draftFor(config)
    DetailCard(stringResource(R.string.group_name)) {
        Padded {
            OutlinedTextField(
                value = draft.customName,
                onValueChange = { v -> vm.editDraft(config) { it.copy(customName = v.take(MAX_SHOWN_NAME)) } },
                label = { Text(stringResource(R.string.setting_custom_name)) },
                placeholder = { Text(config.name) },
                supportingText = { Text(stringResource(R.string.setting_custom_name_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
            OutlinedTextField(
                value = draft.customAuthor,
                onValueChange = { v -> vm.editDraft(config) { it.copy(customAuthor = v.take(MAX_SHOWN_NAME)) } },
                label = { Text(stringResource(R.string.setting_custom_author)) },
                placeholder = config.author?.let { author -> { Text(author) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
            SaveDraft(vm, config, DraftPart.NAME)
        }
    }
}

@Composable
private fun saver(vm: DetailViewModel): ((AppConfig) -> AppConfig) -> Unit {
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    return { change -> vm.save(change) { actions.say(failed) } }
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
    DetailCard(stringResource(R.string.group_updates)) {
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
        val globalWait = LocalEngine.current.settings.collectAsStateWithLifecycle().value.minAgeDaysByDefault
        ChoiceRow(
            title = stringResource(R.string.setting_min_age),
            options = listOf<Int?>(null) + (MIN_AGE_CHOICES + listOfNotNull(config.releases.minAgeDays)).distinct().sorted(),
            selected = config.releases.minAgeDays,
            label = { days -> if (days == null) stringResource(R.string.min_age_global, minAgeLabel(globalWait)) else minAgeLabel(days) },
            summary = stringResource(R.string.setting_min_age_effect),
            onSelect = { days -> save { it.copy(releases = it.releases.copy(minAgeDays = days)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.setting_stay_behind),
            options = (0..ReleaseSelector.MAX_STAY_BEHIND).toList(),
            selected = config.releases.stayBehind,
            label = { if (it == 0) stringResource(R.string.stay_behind_none) else pluralStringResource(R.plurals.stay_behind_releases, it, it) },
            summary = stringResource(R.string.setting_stay_behind_effect),
            onSelect = { n -> save { it.copy(releases = it.releases.copy(stayBehind = n)) } },
        )
        config.releases.skippedReleaseId?.let { skipped ->
            ActionRow(
                title = stringResource(R.string.setting_unskip),
                summary = stringResource(R.string.setting_unskip_effect, isolate(skipped)),
                onClick = { save { it.copy(releases = it.releases.copy(skippedReleaseId = null)) } },
            )
        }
        SwitchRow(
            title = stringResource(R.string.setting_muted),
            summary = stringResource(R.string.setting_muted_effect),
            checked = config.muted,
            onChange = { on -> save { it.copy(muted = on) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_refresh_first),
            summary = stringResource(R.string.setting_refresh_first_effect),
            checked = config.refreshFirst,
            onChange = { on -> save { it.copy(refreshFirst = on) } },
        )
        val installer = LocalEngine.current.settings.collectAsStateWithLifecycle().value.installer
        if (installer == InstallerMode.SHIZUKU || installer == InstallerMode.ROOT || config.playInstaller) {
            SwitchRow(
                title = stringResource(R.string.setting_play_installer),
                summary = stringResource(R.string.setting_play_installer_effect),
                checked = config.playInstaller,
                onChange = { on -> save { it.copy(playInstaller = on) } },
            )
        }
    }
}

/** Text and fields inside a card whose rows bring their own padding. */
@Composable
private fun Padded(content: @Composable () -> Unit) {
    val look = LocalLook.current
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall + look.gapSmall / 2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
    ) { content() }
}

@Composable
private fun FilesGroup(vm: DetailViewModel, row: AppRow) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    val save = saver(vm)
    val config = row.config
    val draft = vm.draftFor(config)
    val invalid = draft.invalid
    DetailCard(stringResource(R.string.group_files)) {
        val ranked = listOfNotNull(row.file) + row.otherFiles
        Padded {
            if (ranked.isEmpty() && row.latest?.savable.isNullOrEmpty()) {
                Text(stringResource(R.string.files_none), style = MaterialTheme.typography.bodyMedium)
            }
            ranked.forEachIndexed { index, choice ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
                    Text(
                        when {
                            choice.picked -> stringResource(R.string.install_file_picked)
                            else -> stringResource(if (index == 0) R.string.file_recommended else R.string.file_alternative, index + 1)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FileChoiceView(choice, size = row.latest?.let { rememberServerSize(vm, it.id, choice.asset) })
                    Row(horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2)) {
                        if (index > 0 && canPickInstall(row)) {
                            // The file picked is kept for the updates that follow, by the shape of its name.
                            TonalButton(stringResource(R.string.action_install_file), onClick = {
                                save { it.copy(preferredFile = choice.asset.name) }
                                engine.install(row.id, assetUrl = choice.asset.url)
                            })
                        }
                        row.latest?.let { release -> SaveFileButton(vm, release.id, choice.asset) }
                    }
                }
            }
            row.latest?.let { release ->
                val others = release.savable.filter { file -> ranked.none { it.asset.url == file.url } }
                if (others.isNotEmpty()) OtherFiles(vm, release, others, divided = ranked.isNotEmpty())
            }
        }
        config.preferredFile?.let { picked ->
            ActionRow(
                title = stringResource(R.string.install_file_automatic),
                summary = stringResource(
                    if (row.file?.picked == true || row.file == null) R.string.install_file_picked_kept else R.string.install_file_picked_missing,
                    ltr(picked),
                ),
                onClick = { save { it.copy(preferredFile = null) } },
            )
        }
        SwitchRow(
            title = stringResource(R.string.setting_match_device),
            summary = stringResource(R.string.setting_match_device_effect),
            checked = config.assets.matchDevice,
            onChange = { on -> save { it.copy(assets = it.assets.copy(matchDevice = on)) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_archives),
            summary = stringResource(R.string.setting_archives_effect),
            checked = config.assets.archives,
            onChange = { on -> save { it.copy(assets = it.assets.copy(archives = on)) } },
        )
        Padded {
            PatternField(R.string.setting_include, R.string.setting_include_help, draft.include, "include" in invalid) { v ->
                vm.editDraft(config) { it.copy(include = v) }
            }
            PatternField(R.string.setting_exclude, R.string.setting_exclude_help, draft.exclude, "exclude" in invalid) { v ->
                vm.editDraft(config) { it.copy(exclude = v) }
            }
            if (config.assets.archives || draft.innerFilter.isNotEmpty()) {
                PatternField(R.string.setting_inner_filter, R.string.setting_inner_filter_help, draft.innerFilter, "innerFilter" in invalid) { v ->
                    vm.editDraft(config) { it.copy(innerFilter = v) }
                }
            }
            SaveDraft(vm, config, DraftPart.FILES)
        }
    }
}

/** The test tag of the Save and Discard row of the card for [part]. */
fun draftSaveTag(part: DraftPart): String = "draft_save_" + part.name.lowercase()

@Composable
private fun SaveDraft(vm: DetailViewModel, config: AppConfig, part: DraftPart) {
    if (!vm.changedIn(part, config)) return
    val look = LocalLook.current
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    val invalid = vm.draftFor(config).invalidIn(part).isNotEmpty()
    Row(
        horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2, Alignment.End),
        modifier = Modifier.fillMaxWidth().testTag(draftSaveTag(part)),
    ) {
        QuietButton(stringResource(R.string.action_discard), onClick = { vm.editDraft(config) { it.resetPart(part, config) } })
        TonalButton(stringResource(R.string.action_save), onClick = { vm.saveDraft(config, part) { actions.say(failed) } }, enabled = !invalid)
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
        textStyle = MaterialTheme.typography.bodyLarge.fingerprint(),
        modifier = Modifier.fillMaxWidth().textFieldKeys(),
    )
}

@Composable
private fun AdvancedGroup(vm: DetailViewModel, config: AppConfig) {
    var open by rememberSaveable(config.id) { mutableStateOf(false) }
    val save = saver(vm)
    val draft = vm.draftFor(config)
    val invalid = draft.invalid
    DetailCard(stringResource(R.string.group_advanced)) {
        ActionRow(
            title = stringResource(if (open) R.string.advanced_hide else R.string.advanced_show),
            icon = if (open) Glyphs.Collapse else Glyphs.Expand,
            onClick = { open = !open },
        )
        if (!open) return@DetailCard
        Padded {
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
            if (draft.version.isNotBlank() || draft.matchGroup.isNotEmpty()) {
                OutlinedTextField(
                    value = draft.matchGroup,
                    onValueChange = { v -> vm.editDraft(config) { it.copy(matchGroup = v.take(40)) } },
                    label = { Text(stringResource(R.string.setting_match_group)) },
                    supportingText = {
                        Text(stringResource(if ("matchGroup" in invalid) R.string.setting_match_group_invalid else R.string.setting_match_group_help))
                    },
                    isError = "matchGroup" in invalid,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.fingerprint(),
                    modifier = Modifier.fillMaxWidth().textFieldKeys(),
                )
            }
            PatternField(R.string.setting_version_filter, R.string.setting_version_filter_help, draft.versionFilter, "versionFilter" in invalid) { v ->
                vm.editDraft(config) { it.copy(versionFilter = v) }
            }
            SaveDraft(vm, config, DraftPart.ADVANCED)
        }
        ChoiceRow(
            title = stringResource(R.string.setting_version_from),
            options = VersionFrom.entries,
            selected = config.releases.versionFrom,
            label = { versionFromLabel(it) },
            onSelect = { from -> save { it.copy(releases = it.releases.copy(versionFrom = from)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.setting_order),
            options = ReleaseOrder.entries,
            selected = config.releases.order,
            label = { orderLabel(it) },
            onSelect = { order -> save { it.copy(releases = it.releases.copy(order = order)) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_fallback),
            summary = stringResource(R.string.setting_fallback_effect),
            checked = config.releases.fallbackToOlder,
            onChange = { on -> save { it.copy(releases = it.releases.copy(fallbackToOlder = on)) } },
        )
        // A source that offers no file keeps its apps track-only, and says why.
        val forced = config.source.type in SourceTypes.TRACK_ONLY
        SwitchRow(
            title = stringResource(R.string.setting_track_only),
            summary = if (forced) {
                stringResource(R.string.setting_track_only_forced, SourceTypes.displayName(config.source.type) ?: config.source.type)
            } else {
                stringResource(R.string.setting_track_only_effect)
            },
            checked = config.trackOnly || forced,
            onChange = { on -> save { it.copy(trackOnly = on) } },
            enabled = !forced,
        )
        Padded { PackageNameSetting(config, save) }
        PinnedSigners(config, save)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinnedSigners(config: AppConfig, save: ((AppConfig) -> AppConfig) -> Unit) {
    val look = LocalLook.current
    var entry by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Padded {
        Text(stringResource(R.string.pins_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (config.pinnedSigners.isEmpty()) R.string.pins_none else R.string.pins_effect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (pin in config.pinnedSigners) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(breakableFingerprint(formatFingerprint(pin)), style = MaterialTheme.typography.bodySmall.fingerprint())
                }
                GlyphButton(
                    Glyphs.Close,
                    stringResource(R.string.pins_remove),
                    onClick = { save { c -> c.copy(pinnedSigners = c.pinnedSigners - pin) } },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            textStyle = MaterialTheme.typography.bodyMedium.fingerprint(),
            modifier = Modifier.fillMaxWidth().textFieldKeys(),
        )
        FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TonalButton(
                stringResource(R.string.pins_add),
                enabled = entry.isNotBlank(),
                onClick = {
                    val fp = normalizeFingerprint(entry)
                    if (fp == null) {
                        bad = true
                    } else {
                        save { c -> c.copy(pinnedSigners = (c.pinnedSigners + fp).distinct()) }
                        entry = ""
                    }
                },
            )
        }
    }
}

/** No question is asked: the app leaves the list at once and can be taken back while the snackbar shows. */
@Composable
private fun RemoveGroup(row: AppRow, onRemoved: () -> Unit) {
    val remove = rememberRemove()
    DetailCard(title = null) {
        ActionRow(
            title = stringResource(R.string.action_remove),
            summary = stringResource(if (row.installed != null) R.string.remove_text_installed else R.string.remove_text),
            icon = Glyphs.Bin,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(DETAIL_REMOVE_TAG),
            onClick = {
                remove(row)
                onRemoved()
            },
        )
    }
}

/**
 * Puts a copy of the file in Download/Tern, as it came; only an install checks a file. Below
 * Android 10, which has no Download/Tern of its own, a person is asked where to put it instead.
 */
@Composable
private fun SaveFileButton(vm: DetailViewModel, releaseId: String, file: Asset, modifier: Modifier = Modifier) {
    val actions = rememberActions()
    val saved = stringResource(R.string.file_saved)
    val failed = stringResource(R.string.save_failed)
    val saveFile = rememberFileSaver(vm)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            vm.saveFileTo(releaseId, file.url, uri) { saved2, problem ->
                actions.say(if (saved2 != null) saved.format(saved2.name, saved2.place) else problem ?: failed)
            }
        }
    }
    val spoken = stringResource(R.string.files_save_spoken, file.name)
    QuietButton(
        stringResource(R.string.action_save_file),
        onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveFile(releaseId, file.url) else picker.launch(file.name)
        },
        enabled = LocalOnline.current,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}

/**
 * Saves a file of a release, given the release and the file's address, and says in a snackbar
 * where it went or why not, while the page is there. The save goes on when the page is left.
 */
@Composable
fun rememberFileSaver(vm: DetailViewModel): (String, String) -> Unit {
    val actions = rememberActions()
    val saved = stringResource(R.string.file_saved)
    return { releaseId, url -> vm.saveFile(releaseId, url) { file, problem -> actions.say(if (file != null) saved.format(file.name, file.place) else problem.orEmpty()) } }
}

/** The size the server gives for [file] where its source names none, asked once while online. */
@Composable
private fun rememberServerSize(vm: DetailViewModel, releaseId: String, file: Asset): Long? {
    val online = LocalOnline.current
    val size by produceState<Long?>(null, releaseId, file.url, online) {
        if (file.size == null && online) value = vm.fileSize(releaseId, file.url)
    }
    return size
}

private const val FOLDED_FILES = 3

/** The files of the release that Tern would not install, and the archives of its source, each to save. */
@Composable
private fun OtherFiles(vm: DetailViewModel, release: Release, files: List<Asset>, divided: Boolean) {
    val look = LocalLook.current
    var all by rememberSaveable(release.id) { mutableStateOf(false) }
    if (divided) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(stringResource(R.string.files_other_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    for (file in if (all) files else files.take(FOLDED_FILES)) {
        Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4)) {
            FileWords(file, source = file in release.sourceArchives)
            SaveFileButton(vm, release.id, file, Modifier.offset(x = -quietInset()))
        }
    }
    if (files.size > FOLDED_FILES) {
        QuietButton(
            if (all) stringResource(R.string.versions_fewer) else stringResource(R.string.versions_all, files.size),
            onClick = { all = !all },
            modifier = Modifier.offset(x = -quietInset()),
        )
    }
}
