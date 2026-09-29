package io.github.munzzyy.stamp.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.Engine
import io.github.munzzyy.stamp.engine.ImportSummary
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.LocalSnackbar
import io.github.munzzyy.stamp.ui.common.ProblemBox
import io.github.munzzyy.stamp.ui.common.SectionHeader
import io.github.munzzyy.stamp.ui.common.backupFocus
import io.github.munzzyy.stamp.ui.common.firstFocus
import io.github.munzzyy.stamp.ui.common.rememberScreenFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val IMPORT_LIST_TAG = "import_list"

sealed interface ImportState {
    data object Idle : ImportState
    data object Working : ImportState
    data class Done(val summary: ImportSummary) : ImportState
    data object Failed : ImportState
}

class ImportViewModel(private val engine: Engine) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null

    fun import(uri: Uri) {
        job?.cancel()
        _state.value = ImportState.Working
        job = viewModelScope.launch {
            _state.value = try {
                ImportState.Done(engine.importFrom(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ImportState.Failed
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = ImportState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, onOpenApp: (String) -> Unit = {}) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "import") { ImportViewModel(engine) }
    val stars = viewModel(key = "import-stars") { StarsViewModel(engine) }
    val state by vm.state.collectAsStateWithLifecycle()
    val starsState by stars.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val pick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    val screen = rememberScreenFocus()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.backupFocus(screen)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = { (starsState as? StarsState.Listed)?.let { AddPickedBar(it, stars::addPicked) } },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(IMPORT_LIST_TAG),
        ) {
            item(key = "file-heading") { SectionHeader(stringResource(R.string.import_file_heading)) }
            item(key = "file") {
                Padded(Modifier.firstFocus(screen)) {
                    Text(stringResource(R.string.import_explain), style = MaterialTheme.typography.bodyLarge)
                    FileState(state, pick, vm::cancel, onOpenApp)
                }
            }
            item(key = "divider") { HorizontalDivider(Modifier.padding(top = 16.dp)) }
            starsSection(starsState, stars)
        }
    }
}

@Composable
fun Padded(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 720.dp),
    ) { content() }
}

@Composable
private fun FileState(state: ImportState, pick: () -> Unit, cancel: () -> Unit, onOpenApp: (String) -> Unit) {
    when (state) {
        ImportState.Idle -> Button(onClick = pick) { Text(stringResource(R.string.import_pick)) }
        ImportState.Working -> Working(stringResource(R.string.import_working), stringResource(R.string.action_cancel), cancel)
        ImportState.Failed -> ProblemBox(
            title = stringResource(R.string.import_failed),
            body = stringResource(R.string.import_failed_help),
            action = stringResource(R.string.import_pick_other),
            onAction = pick,
        )
        is ImportState.Done -> {
            val s = state.summary
            ImportSummaryView(s.added, s.alreadyPresent, s.skipped) { CarriedNote(s, onOpenApp) }
            TextButton(onClick = pick) { Text(stringResource(R.string.import_pick_other)) }
        }
    }
}

@Composable
fun Working(text: String, stop: String, onStop: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator()
        Text(text, modifier = Modifier.weight(1f))
        TextButton(onClick = onStop) { Text(stop) }
    }
}

@Composable
private fun AddPickedBar(listed: StarsState.Listed, onAdd: () -> Unit) {
    if (listed.hits.isEmpty()) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val count = listed.picked.size
            if (count == 0) {
                Text(stringResource(R.string.import_stars_pick_some), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Button(onClick = onAdd, modifier = Modifier.testTag(STARS_ADD_TAG)) {
                    Text(pluralStringResource(R.plurals.import_stars_add, count, count))
                }
            }
        }
    }
}
