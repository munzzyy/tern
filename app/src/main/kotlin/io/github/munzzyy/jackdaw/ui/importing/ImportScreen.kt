package io.github.munzzyy.jackdaw.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.engine.ImportSummary
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.LocalSnackbar
import io.github.munzzyy.jackdaw.ui.common.ProblemBox
import io.github.munzzyy.jackdaw.ui.common.TrustLine
import io.github.munzzyy.jackdaw.ui.text.Trust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
fun ImportScreen(onBack: () -> Unit) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "import") { ImportViewModel(engine) }
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val pick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 720.dp),
        ) {
            Text(stringResource(R.string.import_explain), style = MaterialTheme.typography.bodyLarge)
            when (val s = state) {
                ImportState.Idle -> Button(onClick = pick) { Text(stringResource(R.string.import_pick)) }
                ImportState.Working -> Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.import_working), modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::cancel) { Text(stringResource(R.string.action_cancel)) }
                }
                ImportState.Failed -> ProblemBox(
                    title = stringResource(R.string.import_failed),
                    body = stringResource(R.string.import_failed_help),
                    action = stringResource(R.string.import_pick_other),
                    onAction = pick,
                )
                is ImportState.Done -> {
                    Summary(s.summary)
                    TextButton(onClick = pick) { Text(stringResource(R.string.import_pick_other)) }
                }
            }
        }
    }
}

@Composable
private fun Summary(summary: ImportSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.import_done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        TrustLine(Trust.GOOD, pluralStringResource(R.plurals.import_added, summary.added, summary.added))
        TrustLine(Trust.NOTE, pluralStringResource(R.plurals.import_present, summary.alreadyPresent, summary.alreadyPresent))
        if (summary.skipped.isNotEmpty()) {
            TrustLine(Trust.BAD, pluralStringResource(R.plurals.import_skipped, summary.skipped.size, summary.skipped.size))
            for ((name, reason) in summary.skipped) {
                Column(Modifier.padding(start = 32.dp).semantics(mergeDescendants = true) {}) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
