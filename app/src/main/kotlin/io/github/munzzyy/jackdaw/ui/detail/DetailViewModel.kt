package io.github.munzzyy.jackdaw.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.engine.NoteBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data object Failed : Loadable<Nothing>
}

class DetailViewModel(private val engine: Engine, val appId: String) : ViewModel() {
    /** Null once the app is gone from the list, for example after Remove. */
    val row: StateFlow<AppRow?> = engine.apps.map { rows -> rows.firstOrNull { it.id == appId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, engine.apps.value.firstOrNull { it.id == appId })

    private val _releases = MutableStateFlow<Loadable<List<Release>>>(Loadable.Loading)
    val releases: StateFlow<Loadable<List<Release>>> = _releases.asStateFlow()

    private val _notes = MutableStateFlow<Map<String, Loadable<List<NoteBlock>>>>(emptyMap())
    val notes: StateFlow<Map<String, Loadable<List<NoteBlock>>>> = _notes.asStateFlow()

    init {
        viewModelScope.launch {
            row.map { it?.lastCheckedMs to it?.latest?.id }.distinctUntilChanged().collect { loadReleases() }
        }
    }

    private suspend fun loadReleases() {
        _releases.value = try {
            Loadable.Ready(engine.releases(appId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Loadable.Failed
        }
    }

    fun loadNotes(release: Release) {
        val current = _notes.value[release.id]
        if (current is Loadable.Ready || current == Loadable.Loading) return
        _notes.update { it + (release.id to Loadable.Loading) }
        viewModelScope.launch {
            val result = try {
                Loadable.Ready(engine.notes(release))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Loadable.Failed
            }
            _notes.update { it + (release.id to result) }
        }
    }

    /** Text in the pattern fields; kept after saving so the fields do not flash old values while the save lands. */
    private var draft by mutableStateOf<PatternDraft?>(null)

    fun draftFor(config: AppConfig): PatternDraft = draft ?: PatternDraft.of(config)

    fun isDirty(config: AppConfig): Boolean = draft.let { it != null && it != PatternDraft.of(config) }

    fun editDraft(config: AppConfig, change: (PatternDraft) -> PatternDraft) {
        draft = change(draftFor(config))
    }

    fun saveDraft(config: AppConfig, onFailed: () -> Unit) {
        val d = draft ?: return
        if (d.invalid.isNotEmpty()) return
        draft = PatternDraft.of(d.applyTo(config))
        save({ d.applyTo(it) }, onFailed)
    }

    /** Saves at once; the list flow brings the change back to the screen. */
    fun save(change: (AppConfig) -> AppConfig, onFailed: () -> Unit) {
        val config = row.value?.config ?: return
        val next = change(config)
        if (next == config) return
        viewModelScope.launch {
            try {
                engine.save(next)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailed()
            }
        }
    }
}
