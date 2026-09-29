package io.github.munzzyy.stamp.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.stamp.engine.Engine
import io.github.munzzyy.stamp.engine.ProblemException
import io.github.munzzyy.stamp.engine.SavedFile
import io.github.munzzyy.stamp.engine.Settings
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

    fun export(uri: Uri, onDone: (Int?) -> Unit) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            val count = attempt { engine.exportTo(uri) }
            _exporting.value = false
            onDone(count)
        }
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
