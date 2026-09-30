package io.github.munzzyy.tern.ui.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Reading
import io.github.munzzyy.tern.ui.MAX_INCOMING_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AddState {
    data object Idle : AddState

    /** [again] is a reading the person asked for from the options, who stays where they are while it runs. */
    data class Looking(val input: String, val again: Boolean = false) : AddState
    data class Answer(val detection: Detection, val again: Boolean = false) : AddState
    data class Adding(val found: Detection.Found, val install: Boolean) : AddState

    /** The engine threw instead of answering; the screen says so in general words. */
    data object Broken : AddState
}

/** A package name as it may be typed: nothing, or one Android takes. */
fun isPackageName(text: String): Boolean = text.isBlank() || BinaryManifest.isValidName(text.trim())

class AddViewModel(private val engine: Engine) : ViewModel() {
    var input by mutableStateOf("")
        private set

    /** The kind of source the address is read as; null lets Tern find out. */
    var readAs by mutableStateOf<String?>(null)
        private set

    /** The package name the person typed, which the next reading holds the app to. */
    var packageName by mutableStateOf("")
        private set

    var optionsOpen by mutableStateOf(false)
        private set

    /** The source of the last answer, whose options may be set before adding. Kept while it is read again. */
    var spec by mutableStateOf<SourceSpec?>(null)
        private set

    /** The source as the person set its options, read that way until the address changes. */
    private var options: SourceSpec? = null

    private val _state = MutableStateFlow<AddState>(AddState.Idle)
    val state: StateFlow<AddState> = _state.asStateFlow()

    private val _added = MutableStateFlow<String?>(null)

    /** Id of the app just added; the screen navigates to it once and calls [consumeAdded]. */
    val added: StateFlow<String?> = _added.asStateFlow()

    private var job: Job? = null
    private var handledNonce: Long? = null

    fun edit(text: String) {
        val next = text.take(MAX_INCOMING_CHARS).replace('\n', ' ').replace('\r', ' ')
        // Options belong to the address they were set for.
        if (next != input) {
            options = null
            spec = null
        }
        input = next
    }

    /** An incoming share or link fills the field and looks it up once per [nonce]. */
    fun prefill(text: String?, nonce: Long) {
        if (text == null || handledNonce == nonce) return
        handledNonce = nonce
        forget()
        edit(text)
        detect()
    }

    /** Looks [text] up; with [words], the apps of the repository it names that have them. */
    fun detect(text: String = input, words: String? = null) {
        read(text, words, again = false)
    }

    private fun read(text: String, words: String?, again: Boolean) {
        val query = text.trim().take(MAX_INCOMING_CHARS)
        if (query.isEmpty()) return
        job?.cancel()
        _state.value = AddState.Looking(query, again)
        val reading = Reading(
            type = options?.type ?: readAs,
            options = options?.options,
            packageName = packageName.trim().takeIf { it.isNotEmpty() && isPackageName(it) },
            words = words,
        )
        job = viewModelScope.launch {
            _state.value = try {
                val detection = engine.detect(query, reading)
                spec = when (detection) {
                    is Detection.Found -> detection.spec
                    is Detection.Failed -> detection.spec
                    is Detection.Results, is Detection.StoresOff -> null
                }
                AddState.Answer(detection, again)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AddState.Broken
            }
        }
    }

    /** Turns third-party stores on because the person asked on the card that explains them, then reads the address again. */
    fun turnOnStores() {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                engine.saveSettings(engine.settings.value.copy(thirdPartyStores = true))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = AddState.Broken
                return@launch
            }
            detect()
        }
    }

    /** Looks an address up that the screen itself offered, as if it had been pasted, read as [type] where the address alone does not say. Nothing is added. */
    fun look(url: String, type: String? = null) {
        edit(url)
        readAs = type
        packageName = ""
        detect()
    }

    fun toggleOptions() {
        optionsOpen = !optionsOpen
    }

    fun openOptions() {
        optionsOpen = true
    }

    /** Reads the address as [type] from now on; an answer already shown is read again that way. */
    fun pickReadAs(type: String?) {
        if (type == readAs) return
        readAs = type
        options = null
        spec = null
        if (_state.value is AddState.Answer) read(input, null, again = true)
    }

    /** The options of the source as the person set them, read again at once so the answer shows what they do. */
    fun applyOptions(changed: SourceSpec) {
        if (changed == spec) return
        options = changed
        spec = changed
        read(input, null, again = true)
    }

    fun editPackage(text: String) {
        packageName = text.filterNot { it.isWhitespace() }.take(MAX_PACKAGE)
    }

    /** Reads the address again with the package name and the options as they are now. */
    fun checkAgain() {
        read(input, null, again = true)
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
        forget()
        input = ""
    }

    private fun forget() {
        readAs = null
        packageName = ""
        options = null
        spec = null
        optionsOpen = false
    }

    fun add(found: Detection.Found, install: Boolean) = store(found, install) { engine.add(found, install) }

    /** Gives the app already in the list the settings [found] carried, then shows it. */
    fun replace(found: Detection.Found) = store(found, install = false) { engine.replaceSettings(found) }

    private fun store(found: Detection.Found, install: Boolean, action: suspend () -> String) {
        if (_state.value is AddState.Adding) return
        _state.value = AddState.Adding(found, install)
        job = viewModelScope.launch {
            try {
                _added.value = action()
                input = ""
                forget()
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

    private companion object {
        const val MAX_PACKAGE = 255
    }
}
