package io.github.munzzyy.tern.ui.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.LocalSnackbar
import io.github.munzzyy.tern.ui.MAX_INCOMING_CHARS
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.OfflineBanner
import io.github.munzzyy.tern.ui.common.PrimaryButton
import io.github.munzzyy.tern.ui.common.ProblemBox
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.ScreenTop
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.firstFocus
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.rememberScreenFocus
import io.github.munzzyy.tern.ui.common.returnFocus
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.detail.SOURCES_WITH_OPTIONS
import io.github.munzzyy.tern.ui.handoff.HandoffGlyphs
import io.github.munzzyy.tern.ui.suggest.Starters
import io.github.munzzyy.tern.ui.text.problemAdvice
import io.github.munzzyy.tern.ui.theme.LocalLook

const val ADD_FIELD_TAG = "add_field"
const val ADD_FIND_TAG = "add_find"
const val ADD_CONFIRM_TAG = "add_confirm"
const val ADD_INSTALL_TAG = "add_install"
const val ADD_HANDOFF_TAG = "add_handoff"

private const val HANDOFF_KEY = "handoff"

/** A device that cannot be typed on with ease, or cannot pick a file, is offered the phone instead. */
fun offersHandoff(noTouch: Boolean, filePicker: Boolean): Boolean = noTouch || !filePicker

/** [onBack] is given when the screen was opened on top of another one, to look at a link that screen offered. */
@Composable
fun AddScreen(
    prefill: String?,
    nonce: Long,
    onAdded: (String) -> Unit,
    onShow: (String) -> Unit,
    onHandoff: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val engine = LocalEngine.current
    // A link that another screen offered is looked at by itself, and leaves the Add tab as the user left it.
    val vm = viewModel(key = if (onBack == null) "add" else "add-on-top") { AddViewModel(engine) }
    val state by vm.state.collectAsStateWithLifecycle()
    val added by vm.added.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val actions = rememberActions()
    val look = LocalLook.current
    val noTouch = LocalNoTouch.current

    val online = LocalOnline.current
    val offlineReason = stringResource(R.string.offline_reason)
    var cleared by rememberSaveable { mutableIntStateOf(0) }
    var listAt by rememberSaveable { mutableIntStateOf(0) }
    val screen = rememberScreenFocus(again = cleared)
    val scroll = rememberScrollState()
    val suggestions = remember(engine) { engine.suggestions() }
    val handoff = remember(engine, noTouch) { offersHandoff(noTouch, engine.hasFilePicker()) }
    val starting = state == AddState.Idle && vm.input.isBlank()

    LaunchedEffect(prefill, nonce) { vm.prefill(prefill, nonce) }
    LaunchedEffect(added) {
        added?.let {
            vm.consumeAdded()
            onAdded(it)
        }
    }
    LaunchedEffect(cleared) { if (cleared > 0) scroll.scrollTo(listAt) }
    val clear: () -> Unit = {
        vm.clear()
        cleared++
    }
    val find: () -> Unit = {
        listAt = 0
        screen.last = null
        vm.detect()
    }
    BackHandler(enabled = onBack == null && !starting && state !is AddState.Adding, onBack = clear)

    Scaffold(
        topBar = { ScreenTop(stringResource(R.string.add_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OfflineBanner(online)
            Column(
                verticalArrangement = Arrangement.spacedBy(look.gap),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = look.screenPadding)
                    .padding(bottom = look.gapSection)
                    .widthIn(max = look.contentMaxWidth)
                    .fillMaxWidth(),
            ) {
                val busy = state is AddState.Looking || state is AddState.Adding
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = vm::edit,
                    label = { Text(stringResource(R.string.add_field_label)) },
                    placeholder = { Text(stringResource(R.string.add_field_hint)) },
                    supportingText = { Text(stringResource(R.string.add_field_help)) },
                    trailingIcon = if (vm.input.isEmpty() || noTouch) {
                        null
                    } else {
                        {
                            IconButton(onClick = clear) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.add_clear)) }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (online) find() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ADD_FIELD_TAG)
                        .firstFocus(screen)
                        .textFieldKeys(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = look.focusRoom),
                ) {
                    QuietButton(
                        stringResource(R.string.action_paste),
                        onClick = {
                            actions.run {
                                val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                                if (!text.isNullOrBlank()) vm.edit(text.toString().take(MAX_INCOMING_CHARS))
                            }
                        },
                    )
                    val findButton = Modifier
                        .testTag(ADD_FIND_TAG)
                        .then(if (online) Modifier else Modifier.semantics { stateDescription = offlineReason })
                    val ready = vm.input.isNotBlank() && !busy && online
                    // One filled button on the screen: once an app is shown, adding it is what matters.
                    if (state is AddState.Answer) {
                        TonalButton(stringResource(R.string.action_find), onClick = find, modifier = findButton, enabled = ready)
                    } else {
                        PrimaryButton(stringResource(R.string.action_find), onClick = find, modifier = findButton, enabled = ready)
                    }
                }
                if (searchesByName(vm.input, state)) SearchPlaces()
                if (readsAnAddress(vm.input, state)) {
                    ReadingCard(vm, answered = (state as? AddState.Answer)?.detection.let { it is Detection.Found || it is Detection.Failed })
                }
                when (val s = state) {
                    AddState.Idle -> if (starting) {
                        if (handoff) {
                            SectionCard {
                                ActionRow(
                                    title = stringResource(R.string.handoff_title),
                                    summary = stringResource(R.string.handoff_effect),
                                    icon = HandoffGlyphs.Phone,
                                    onClick = onHandoff,
                                    modifier = Modifier
                                        .testTag(ADD_HANDOFF_TAG)
                                        .returnFocus(screen, HANDOFF_KEY),
                                )
                            }
                        }
                        Starters(
                            suggestions = suggestions,
                            onLook = {
                                listAt = scroll.value
                                vm.look(it.url)
                            },
                            rowFocus = { Modifier.returnFocus(screen, it.url) },
                        )
                        SourcesCard()
                    }
                    is AddState.Looking -> Busy(stringResource(R.string.add_looking), onCancel = vm::cancel, takeFocus = !s.again)
                    is AddState.Adding -> Busy(stringResource(R.string.add_adding), onCancel = null)
                    AddState.Broken -> ProblemBox(
                        title = stringResource(R.string.add_broken),
                        body = null,
                        action = stringResource(R.string.action_try_again),
                        onAction = { vm.detect() },
                        modifier = Modifier.focusWhenShown(),
                    )
                    // Read again from the options, the answer leaves the focus where the person is.
                    is AddState.Answer -> Column(if (s.again) Modifier else Modifier.focusWhenShown(revealTop = true)) {
                        when (val d = s.detection) {
                            is Detection.Found -> PreviewCard(
                                found = d,
                                carried = remember(d) { vm.carried(d) },
                                onAdd = { install -> vm.add(d, install) },
                                onShow = onShow,
                                onReplace = { vm.replace(d) },
                            )
                            is Detection.Results -> ResultsList(d, onPick = { vm.look(it.url, it.type) }, onSearchRepository = { vm.detect(words = it) })
                            is Detection.StoresOff -> StoresOffCard(d.type, onTurnOn = vm::turnOnStores)
                            is Detection.Failed -> {
                                val settable = optionsMayHelp(d)
                                ProblemBox(
                                    title = d.problem.message,
                                    body = stringResource(if (settable) R.string.add_failed_options else problemAdvice(d.problem.kind, installed = false)),
                                    action = stringResource(if (settable) R.string.add_set_options else R.string.action_try_again),
                                    onAction = if (settable) vm::openOptions else ({ vm.detect() }),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String, onCancel: (() -> Unit)?, takeFocus: Boolean = true) {
    val look = LocalLook.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.cardPadding)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(Modifier.size(look.glyph + look.gapSmall))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        onCancel?.let { QuietButton(stringResource(R.string.action_cancel), onClick = it, modifier = if (takeFocus) Modifier.focusWhenShown() else Modifier) }
    }
}

/** True for text that is an address or a link rather than words to search for. */
fun isAddress(input: String): Boolean {
    val text = input.trim()
    return text.contains("://") || text.contains('/') || text.contains('.') && !text.contains(' ')
}

/** Words, not an address, and nothing found yet but what a search found: where to search is worth choosing. */
fun searchesByName(input: String, state: AddState): Boolean {
    val text = input.trim()
    if (text.isEmpty() || isAddress(text)) return false
    return when (state) {
        AddState.Idle -> true
        is AddState.Answer -> (state.detection as? Detection.Results)?.let { !isRepository(it) && !isCarriedList(it) } ?: false
        else -> false
    }
}

/** An address, and not a list of apps that came in one link: how to read it and what to hold it to is worth saying. */
fun readsAnAddress(input: String, state: AddState): Boolean {
    if (!isAddress(input) || state is AddState.Adding) return false
    return ((state as? AddState.Answer)?.detection as? Detection.Results)?.let { !isCarriedList(it) } ?: true
}

/** A source was read and failed, and it has options that can tell it where to look; a failed connection is not one of those. */
fun optionsMayHelp(failed: Detection.Failed): Boolean =
    failed.spec?.type in SOURCES_WITH_OPTIONS && failed.problem.kind != ProblemKind.NETWORK && failed.problem.kind != ProblemKind.RATE_LIMITED
