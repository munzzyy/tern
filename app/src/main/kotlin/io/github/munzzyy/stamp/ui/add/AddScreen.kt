package io.github.munzzyy.stamp.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.Detection
import io.github.munzzyy.stamp.engine.SearchHit
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.LocalOnline
import io.github.munzzyy.stamp.ui.common.OfflineBanner
import io.github.munzzyy.stamp.ui.LocalSnackbar
import io.github.munzzyy.stamp.ui.common.ProblemBox
import io.github.munzzyy.stamp.ui.common.firstFocus
import io.github.munzzyy.stamp.ui.common.focusWhenShown
import io.github.munzzyy.stamp.ui.common.rememberScreenFocus
import io.github.munzzyy.stamp.ui.common.textFieldKeys
import io.github.munzzyy.stamp.ui.common.rememberActions
import io.github.munzzyy.stamp.ui.MAX_INCOMING_CHARS

const val ADD_FIELD_TAG = "add_field"
const val ADD_FIND_TAG = "add_find"
const val ADD_CONFIRM_TAG = "add_confirm"
const val ADD_INSTALL_TAG = "add_install"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(prefill: String?, nonce: Long, onAdded: (String) -> Unit, onShow: (String) -> Unit) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "add") { AddViewModel(engine) }
    val state by vm.state.collectAsStateWithLifecycle()
    val added by vm.added.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val actions = rememberActions()

    val online = LocalOnline.current
    val offlineReason = stringResource(R.string.offline_reason)
    val screen = rememberScreenFocus()
    LaunchedEffect(prefill, nonce) { vm.prefill(prefill, nonce) }
    LaunchedEffect(added) {
        added?.let {
            vm.consumeAdded()
            onAdded(it)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.add_title)) }) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OfflineBanner(online)
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val busy = state is AddState.Looking || state is AddState.Adding
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = vm::edit,
                    label = { Text(stringResource(R.string.add_field_label)) },
                    placeholder = { Text(stringResource(R.string.add_field_hint)) },
                    supportingText = { Text(stringResource(R.string.add_field_help)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (online) vm.detect() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ADD_FIELD_TAG)
                        .firstFocus(screen)
                        .textFieldKeys(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        actions.run {
                            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                            if (!text.isNullOrBlank()) vm.edit(text.toString().take(MAX_INCOMING_CHARS))
                        }
                    }) { Text(stringResource(R.string.action_paste)) }
                    Button(
                        onClick = { vm.detect() },
                        enabled = vm.input.isNotBlank() && !busy && online,
                        modifier = Modifier
                            .testTag(ADD_FIND_TAG)
                            .then(if (online) Modifier else Modifier.semantics { stateDescription = offlineReason }),
                    ) { Text(stringResource(R.string.action_find)) }
                }
                when (val s = state) {
                    AddState.Idle -> Unit
                    is AddState.Looking -> Busy(stringResource(R.string.add_looking), onCancel = vm::cancel)
                    is AddState.Adding -> Busy(stringResource(R.string.add_adding), onCancel = null)
                    AddState.Broken -> ProblemBox(
                        title = stringResource(R.string.add_broken),
                        body = null,
                        action = stringResource(R.string.action_try_again),
                        onAction = { vm.detect() },
                        modifier = Modifier.focusWhenShown(),
                    )
                    is AddState.Answer -> Column(Modifier.focusWhenShown(revealTop = true)) {
                        when (val d = s.detection) {
                            is Detection.Found -> PreviewCard(
                                found = d,
                                carried = remember(d) { vm.carried(d) },
                                onAdd = { install -> vm.add(d, install) },
                                onShow = onShow,
                            )
                            is Detection.Results -> SearchResults(d, onPick = { vm.detect(it.url) })
                            is Detection.Failed -> ProblemBox(
                                title = d.problem.message,
                                body = stringResource(io.github.munzzyy.stamp.ui.text.problemAdvice(d.problem.kind, installed = false)),
                                action = stringResource(R.string.action_try_again),
                                onAction = { vm.detect() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String, onCancel: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        onCancel?.let { TextButton(onClick = it, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) } }
    }
}

@Composable
private fun SearchResults(results: Detection.Results, onPick: (SearchHit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (results.hits.isEmpty()) {
                stringResource(R.string.search_none, results.query)
            } else {
                pluralStringResource(R.plurals.search_count, results.hits.size, results.hits.size, results.query)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        for (hit in results.hits) {
            val stars = hit.stars?.let { pluralStringResource(R.plurals.search_stars, it, it) }
            val source = listOfNotNull(hit.owner, hit.origin, stars).joinToString(" · ")
            ListItem(
                headlineContent = { Text(hit.name) },
                supportingContent = {
                    Column {
                        Text(source)
                        hit.description?.let { Text(it, maxLines = 3) }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .widthIn(max = 720.dp)
                    .selectable(selected = false, role = Role.Button, onClick = { onPick(hit) }),
            )
        }
    }
}
