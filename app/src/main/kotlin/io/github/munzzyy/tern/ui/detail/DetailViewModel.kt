package io.github.munzzyy.tern.ui.detail

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.NoteBlock
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.SavedFile
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

sealed interface MoveState {
    data object Idle : MoveState
    data object Working : MoveState
    data class Refused(val problem: Problem) : MoveState
    data object Followed : MoveState
}

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

    private val _offerable = MutableStateFlow<List<Release>>(emptyList())

    /** The releases the app's settings let through, in the same order; empty until loaded. */
    val offerable: StateFlow<List<Release>> = _offerable.asStateFlow()

    private val _notes = MutableStateFlow<Map<String, Loadable<List<NoteBlock>>>>(emptyMap())
    val notes: StateFlow<Map<String, Loadable<List<NoteBlock>>>> = _notes.asStateFlow()

    init {
        viewModelScope.launch {
            row.map { it?.lastCheckedMs to it?.latest?.id }.distinctUntilChanged().collect { loadReleases() }
        }
        if (checksOnOpen(engine.settings.value.checkOnOpen, row.value, System.currentTimeMillis())) {
            viewModelScope.launch {
                try {
                    engine.check(appId, CheckCause.PAGE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
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
        _offerable.value = try {
            engine.offerable(appId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
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

    private val _move = MutableStateFlow<MoveState>(MoveState.Idle)
    val move: StateFlow<MoveState> = _move.asStateFlow()

    fun followMove(onFailed: () -> Unit) {
        if (_move.value == MoveState.Working) return
        _move.value = MoveState.Working
        viewModelScope.launch {
            _move.value = try {
                engine.followMove(appId)?.let { MoveState.Refused(it) } ?: MoveState.Followed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailed()
                MoveState.Idle
            }
        }
    }

    fun keepAddress(onFailed: () -> Unit) {
        _move.value = MoveState.Idle
        viewModelScope.launch {
            try {
                engine.keepAddress(appId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailed()
            }
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

    /** The size of a file whose source names none, as its server says; null when it does not say or cannot be asked. */
    suspend fun fileSize(releaseId: String, assetUrl: String): Long? = try {
        engine.fileSize(appId, releaseId, assetUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Hands back the copy that was saved, or else why not, while the page is there to say so.
     * The save itself runs in the engine and goes on when the page is left.
     */
    fun saveFile(releaseId: String, assetUrl: String, onDone: (SavedFile?, String?) -> Unit) {
        viewModelScope.launch {
            val (file, problem) = try {
                engine.saveFile(appId, releaseId, assetUrl) to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProblemException) {
                null to e.problem.message
            } catch (e: Exception) {
                null to (e.message ?: "")
            }
            onDone(file, problem)
        }
    }

    /** As [saveFile], but to [destination], for an Android with no Download/Tern of its own. */
    fun saveFileTo(releaseId: String, assetUrl: String, destination: Uri, onDone: (SavedFile?, String?) -> Unit) {
        viewModelScope.launch {
            val (file, problem) = try {
                engine.saveFileTo(appId, releaseId, assetUrl, destination) to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProblemException) {
                null to e.problem.message
            } catch (e: Exception) {
                null to (e.message ?: "")
            }
            onDone(file, problem)
        }
    }

    /** Saves at once; the list flow brings the change back to the screen. */
    fun save(change: (AppConfig) -> AppConfig, onFailed: () -> Unit) {
        val config = row.value?.config ?: return
        if (change(config) == config) return
        viewModelScope.launch {
            try {
                engine.configure(config.id, change)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailed()
            }
        }
    }
}

/** How long after a check opening the page does not check again, so going back and forth costs nothing. */
const val OPEN_CHECK_QUIET_MS = 5 * 60 * 1000L

/** Whether opening the page of [row] checks it now, as the setting asks, unless it is being checked or was a moment ago. */
fun checksOnOpen(setting: Boolean, row: AppRow?, nowMs: Long): Boolean {
    if (!setting || row == null || row.checking) return false
    val last = row.lastCheckedMs ?: return true
    return nowMs - last >= OPEN_CHECK_QUIET_MS
}
