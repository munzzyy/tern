package io.github.munzzyy.stamp.ui.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.stamp.engine.Detection
import io.github.munzzyy.stamp.engine.Engine
import io.github.munzzyy.stamp.ui.MAX_INCOMING_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AddState {
    data object Idle : AddState
    data class Looking(val input: String) : AddState
    data class Answer(val detection: Detection) : AddState
    data class Adding(val found: Detection.Found, val install: Boolean) : AddState

    /** The engine threw instead of answering; the screen says so in general words. */
    data object Broken : AddState
}

class AddViewModel(private val engine: Engine) : ViewModel() {
    var input by mutableStateOf("")
        private set

    private val _state = MutableStateFlow<AddState>(AddState.Idle)
    val state: StateFlow<AddState> = _state.asStateFlow()

    private val _added = MutableStateFlow<String?>(null)

    /** Id of the app just added; the screen navigates to it once and calls [consumeAdded]. */
    val added: StateFlow<String?> = _added.asStateFlow()

    private var job: Job? = null
    private var handledNonce: Long? = null

    fun edit(text: String) {
        input = text.take(MAX_INCOMING_CHARS).replace('\n', ' ').replace('\r', ' ')
    }

    /** An incoming share or link fills the field and looks it up once per [nonce]. */
    fun prefill(text: String?, nonce: Long) {
        if (text == null || handledNonce == nonce) return
        handledNonce = nonce
        edit(text)
        detect()
    }

    fun detect(text: String = input) {
        val query = text.trim().take(MAX_INCOMING_CHARS)
        if (query.isEmpty()) return
        job?.cancel()
        _state.value = AddState.Looking(query)
        job = viewModelScope.launch {
            _state.value = try {
                AddState.Answer(engine.detect(query))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AddState.Broken
            }
        }
    }

    /** Looks an address up that the screen itself offered, as if it had been pasted. Nothing is added. */
    fun look(url: String) {
        edit(url)
        detect()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = AddState.Idle
    }

    /** Empties the field and forgets the answer, which brings the well known apps back. */
    fun clear() {
        if (_state.value is AddState.Adding) return
        cancel()
        input = ""
    }

    fun add(found: Detection.Found, install: Boolean) {
        if (_state.value is AddState.Adding) return
        _state.value = AddState.Adding(found, install)
        job = viewModelScope.launch {
            try {
                _added.value = engine.add(found, install)
                input = ""
                _state.value = AddState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = AddState.Broken
            }
        }
    }

    /** What this link would set beyond a plain new app; nothing is stored until [add]. */
    fun carried(found: Detection.Found): List<CarriedSetting> = try {
        carriedSettings(engine.proposedConfig(found), engine.proposedConfig(found.copy(carried = null)))
    } catch (_: Exception) {
        emptyList()
    }

    fun consumeAdded() {
        _added.value = null
    }
}
