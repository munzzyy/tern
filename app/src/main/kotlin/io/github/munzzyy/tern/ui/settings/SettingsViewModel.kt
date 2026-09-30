package io.github.munzzyy.tern.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.engine.ExportStatus
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.SavedFile
import io.github.munzzyy.tern.engine.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val engine: Engine) : ViewModel() {
    val settings: StateFlow<Settings> = engine.settings

    private val _tokenHosts = MutableStateFlow<List<String>?>(null)
    val tokenHosts: StateFlow<List<String>?> = _tokenHosts.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    init {
        refreshHosts()
    }

    private fun refreshHosts() {
        viewModelScope.launch {
            _tokenHosts.value = attempt { engine.tokenHosts().sorted() } ?: emptyList()
        }
    }

    fun update(onFailed: () -> Unit, change: (Settings) -> Settings) {
        val next = change(settings.value)
        if (next == settings.value) return
        viewModelScope.launch { if (attempt { engine.saveSettings(next) } == null) onFailed() }
    }

    fun setToken(host: String, token: String?, onFailed: () -> Unit) {
        viewModelScope.launch {
            if (attempt { engine.setToken(host, token) } == null) onFailed()
            refreshHosts()
        }
    }

    fun export(uri: Uri, format: ExportFormat = ExportFormat.TERN, onDone: (Int?) -> Unit) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            val count = attempt { engine.exportTo(uri, format) }
            _exporting.value = false
            onDone(count)
        }
    }

    val exportStatus: StateFlow<ExportStatus?> = engine.exportStatus

    private val _runningCheck = MutableStateFlow(false)
    val runningCheck: StateFlow<Boolean> = _runningCheck.asStateFlow()

    /** Whether Let Me Downgrade is installed; read once, as the screen opens. */
    val canDowngrade: Boolean = engine.canDowngrade()

    fun runBackgroundCheck() {
        if (_runningCheck.value) return
        _runningCheck.value = true
        viewModelScope.launch {
            try {
                attempt { engine.runBackgroundCheck() }
            } finally {
                _runningCheck.value = false
            }
        }
    }

    /** Keeps the export up to date in [folder] from now on; Android's grant of it is taken first. */
    fun keepIn(folder: Uri, onFailed: () -> Unit) {
        viewModelScope.launch {
            val taken = attempt { engine.takeExportFolder(folder) } != null
            val next = settings.value.copy(exportFolder = folder.toString(), autoExport = true)
            if (!taken || attempt { engine.saveSettings(next) } == null) onFailed()
        }
    }

    fun writeKept() {
        viewModelScope.launch { attempt { engine.writeKeptExport() } }
    }

    /** Hands back the file that was written, or else the engine's sentence about why not, which is null when it gave none. */
    fun exportToFolder(onDone: (SavedFile?, String?) -> Unit) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            val (saved, problem) = try {
                engine.exportToFolder() to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProblemException) {
                null to e.problem.message
            } catch (_: Exception) {
                null to null
            } finally {
                _exporting.value = false
            }
            onDone(saved, problem)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
