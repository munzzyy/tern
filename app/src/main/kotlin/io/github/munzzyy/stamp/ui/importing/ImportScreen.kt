package io.github.munzzyy.stamp.ui.importing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.SavedFile
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.LocalOnline
import io.github.munzzyy.stamp.ui.LocalSnackbar
import io.github.munzzyy.stamp.ui.MAX_INCOMING_CHARS
import io.github.munzzyy.stamp.ui.common.ActionRow
import io.github.munzzyy.stamp.ui.common.Nest
import io.github.munzzyy.stamp.ui.common.PressRow
import io.github.munzzyy.stamp.ui.common.PrimaryButton
import io.github.munzzyy.stamp.ui.common.ProblemBox
import io.github.munzzyy.stamp.ui.common.QuietButton
import io.github.munzzyy.stamp.ui.common.ReadBlock
import io.github.munzzyy.stamp.ui.common.ScreenFocus
import io.github.munzzyy.stamp.ui.common.ScreenTop
import io.github.munzzyy.stamp.ui.common.SectionCard
import io.github.munzzyy.stamp.ui.common.TonalButton
import io.github.munzzyy.stamp.ui.common.firstFocus
import io.github.munzzyy.stamp.ui.common.focusWhenShown
import io.github.munzzyy.stamp.ui.common.rememberScreenFocus
import io.github.munzzyy.stamp.ui.common.returnFocus
import io.github.munzzyy.stamp.ui.common.textFieldKeys
import io.github.munzzyy.stamp.ui.handoff.HandoffGlyphs
import io.github.munzzyy.stamp.ui.text.formatBytes
import io.github.munzzyy.stamp.ui.text.formatDate
import io.github.munzzyy.stamp.ui.text.isolate
import io.github.munzzyy.stamp.ui.text.ltr
import io.github.munzzyy.stamp.ui.theme.LocalLook

const val IMPORT_LIST_TAG = "import_list"
const val DOOR_FILES_TAG = "import_door_files"
const val DOOR_LINK_TAG = "import_door_link"
const val DOOR_LINK_FIELD_TAG = "import_door_link_field"
const val DOOR_LINK_GO_TAG = "import_door_link_go"
const val DOOR_HANDOFF_TAG = "import_door_handoff"

private const val HANDOFF_KEY = "handoff"

@Composable
fun ImportScreen(onBack: () -> Unit, onOpenApp: (String) -> Unit = {}, onHandoff: () -> Unit = {}) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "import") { ImportViewModel(engine) }
    val stars = viewModel(key = "import-stars") { StarsViewModel(engine) }
    val state by vm.state.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val starsState by stars.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val pick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    val hasPicker = remember(engine) { engine.hasFilePicker() }
    val screen = rememberScreenFocus()
    val look = LocalLook.current

    Scaffold(
        topBar = { ScreenTop(stringResource(R.string.import_title), onBack = onBack) },
        bottomBar = { (starsState as? StarsState.Listed)?.let { AddPickedBar(it, stars::addPicked) } },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = look.screenPadding, end = look.screenPadding, bottom = look.gapSection),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(IMPORT_LIST_TAG),
        ) {
            // One item, so that all of the card is laid out: a list lays out no more of what is below the screen than it needs at the moment.
            item(key = "file") {
                SectionCard(title = stringResource(R.string.import_file_heading), modifier = Modifier.widthIn(max = look.contentMaxWidth)) {
                    Words(stringResource(if (hasPicker) R.string.import_explain else R.string.door_explain))
                    Outcome(state, pick, vm, onOpenApp)
                    if (state !is ImportState.Working) {
                        if (hasPicker) {
                            PrimaryButton(
                                stringResource(if (state == ImportState.Idle) R.string.import_pick else R.string.import_pick_other),
                                onClick = pick,
                                modifier = Modifier
                                    .padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2)
                                    .firstFocus(screen),
                            )
                        } else {
                            Doors(files, vm, screen, onHandoff)
                        }
                    }
                }
            }
            starsSection(starsState, stars)
        }
    }
}

/** Text inside a card, which stands as far from the card's side as the words of its rows do. */
@Composable
fun Words(text: String, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
    )
}

@Composable
private fun ColumnScope.Outcome(state: ImportState, pick: () -> Unit, vm: ImportViewModel, onOpenApp: (String) -> Unit) {
    val look = LocalLook.current
    when (state) {
        ImportState.Idle -> Unit
        is ImportState.Working -> Working(
            text = stringResource(if (state.door == Door.LINK) R.string.door_link_working else R.string.import_working),
            stop = stringResource(R.string.action_cancel),
            onStop = vm::cancel,
            modifier = Modifier.padding(horizontal = look.cardPadding, vertical = look.gapSmall),
            stopModifier = Modifier.focusWhenShown(),
        )
        is ImportState.Failed -> ProblemBox(
            title = state.message ?: stringResource(R.string.import_failed),
            body = if (state.message == null) stringResource(R.string.import_failed_help) else null,
            action = stringResource(if (state.door == Door.PICKER) R.string.import_pick_other else R.string.action_try_again),
            onAction = if (state.door == Door.PICKER) pick else vm::again,
            modifier = Modifier
                .padding(horizontal = look.cardPadding, vertical = look.gapSmall)
                .focusWhenShown(),
        )
        is ImportState.Done -> ReadBlock(Modifier.focusWhenShown()) {
            val s = state.summary
            ImportSummaryView(s.added, s.alreadyPresent, s.skipped) { CarriedNote(s, onOpenApp) }
        }
    }
}

/** The three ways in on a device without a file picker. */
@Composable
private fun Doors(files: FileList, vm: ImportViewModel, screen: ScreenFocus, onHandoff: () -> Unit) {
    val look = LocalLook.current
    var linkOpen by rememberSaveable { mutableStateOf(false) }
    ActionRow(
        title = stringResource(R.string.door_files),
        summary = stringResource(R.string.door_files_effect),
        icon = HandoffGlyphs.Folder,
        onClick = vm::toggleFiles,
        modifier = Modifier
            .testTag(DOOR_FILES_TAG)
            .firstFocus(screen),
    )
    if (files != FileList.Closed) {
        Nest {
            when (files) {
                FileList.Closed -> Unit
                FileList.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(look.gap),
                    modifier = Modifier
                        .padding(horizontal = look.rowPaddingHorizontal, vertical = look.gapSmall)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(Modifier.size(look.glyph))
                    Text(stringResource(R.string.door_files_loading), style = MaterialTheme.typography.bodyLarge)
                }
                is FileList.Listed -> if (files.files.isEmpty()) {
                    ReadBlock(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(R.string.door_no_files), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    for (file in files.files) SavedFileRow(file, onImport = { vm.import(file) })
                }
            }
        }
    }
    ActionRow(
        title = stringResource(R.string.door_link),
        summary = stringResource(R.string.door_link_effect),
        icon = HandoffGlyphs.Link,
        onClick = { linkOpen = !linkOpen },
        modifier = Modifier.testTag(DOOR_LINK_TAG),
    )
    if (linkOpen) Nest { LinkDoor(onImport = vm::importLink) }
    ActionRow(
        title = stringResource(R.string.handoff_title),
        summary = stringResource(R.string.handoff_effect),
        icon = HandoffGlyphs.Phone,
        onClick = onHandoff,
        modifier = Modifier
            .testTag(DOOR_HANDOFF_TAG)
            .returnFocus(screen, HANDOFF_KEY),
    )
}

@Composable
private fun SavedFileRow(file: SavedFile, onImport: () -> Unit) {
    val look = LocalLook.current
    PressRow(
        action = stringResource(R.string.action_import),
        onClick = onImport,
        below = LocalConfiguration.current.screenWidthDp < NARROW_DP,
        leading = { Icon(HandoffGlyphs.File, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(look.glyph)) },
    ) {
        Text(ltr(file.name.take(MAX_NAME_SHOWN)), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.door_file_line, ltr(file.place.take(MAX_NAME_SHOWN)), isolate(formatDate(file.modifiedAtMs)), isolate(formatBytes(file.sizeBytes))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val MAX_NAME_SHOWN = 120
private const val NARROW_DP = 600

@Composable
private fun LinkDoor(onImport: (String) -> Unit) {
    val look = LocalLook.current
    val online = LocalOnline.current
    val offlineReason = stringResource(R.string.offline_reason)
    var link by rememberSaveable { mutableStateOf("") }
    val ready = link.isNotBlank() && online
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.rowPaddingHorizontal, vertical = look.gapSmall),
    ) {
        OutlinedTextField(
            value = link,
            onValueChange = { typed -> link = typed.take(MAX_INCOMING_CHARS).filterNot { it == '\n' || it == '\r' } },
            label = { Text(stringResource(R.string.door_link_label)) },
            supportingText = { Text(stringResource(R.string.door_link_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { if (ready) onImport(link) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DOOR_LINK_FIELD_TAG)
                .textFieldKeys(),
        )
        TonalButton(
            stringResource(R.string.action_import),
            onClick = { onImport(link) },
            enabled = ready,
            modifier = Modifier
                .align(Alignment.End)
                .testTag(DOOR_LINK_GO_TAG)
                .then(if (online) Modifier else Modifier.semantics { stateDescription = offlineReason }),
        )
    }
}

@Composable
fun Working(text: String, stop: String, onStop: () -> Unit, modifier: Modifier = Modifier, stopModifier: Modifier = Modifier) {
    val look = LocalLook.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(Modifier.size(look.glyph + look.gapSmall))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        QuietButton(stop, onStop, stopModifier)
    }
}

@Composable
private fun AddPickedBar(listed: StarsState.Listed, onAdd: () -> Unit) {
    if (listed.hits.isEmpty()) return
    val look = LocalLook.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = look.screenPadding, vertical = look.gapSmall)) {
            val count = listed.picked.size
            if (count == 0) {
                Text(stringResource(R.string.import_stars_pick_some), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = look.gapSmall))
            } else {
                PrimaryButton(pluralStringResource(R.plurals.import_stars_add, count, count), onClick = onAdd, modifier = Modifier.testTag(STARS_ADD_TAG))
            }
        }
    }
}
