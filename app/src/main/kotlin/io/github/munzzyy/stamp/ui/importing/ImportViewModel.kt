package io.github.munzzyy.stamp.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.stamp.engine.Engine
import io.github.munzzyy.stamp.engine.ImportSummary
import io.github.munzzyy.stamp.engine.ProblemException
import io.github.munzzyy.stamp.engine.SavedFile
import io.github.munzzyy.stamp.ui.MAX_INCOMING_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the file came in. It decides the words while it is read and the way to try again. */
enum class Door { PICKER, FILE, LINK }

sealed interface ImportState {
    data object Idle : ImportState

    data class Working(val door: Door) : ImportState

    data class Done(val summary: ImportSummary) : ImportState

    /** [message] is the engine's own sentence, or null when it gave none. */
    data class Failed(val door: Door, val message: String?) : ImportState
}

/** The export files this device holds, once the user has asked for them. */
sealed interface FileList {
    data object Closed : FileList

    data object Loading : FileList

    data class Listed(val files: List<SavedFile>) : FileList
}

const val MAX_FILES_LISTED = 50

class ImportViewModel(private val engine: Engine) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val _files = MutableStateFlow<FileList>(FileList.Closed)
    val files: StateFlow<FileList> = _files.asStateFlow()

    private var job: Job? = null
    private var listing: Job? = null
    private var last: (() -> Unit)? = null

    fun import(uri: Uri) = run(Door.PICKER) { engine.importFrom(uri) }

    fun import(file: SavedFile) = run(Door.FILE) { engine.importFromFile(file) }

    fun importLink(url: String) = run(Door.LINK) { engine.importFromLink(url.trim().take(MAX_INCOMING_CHARS)) }

    /** Tries what failed once more, from the same file or the same link. */
    fun again() {
        last?.invoke()
    }

    private fun run(door: Door, read: suspend () -> ImportSummary) {
        last = { run(door, read) }
        job?.cancel()
        _state.value = ImportState.Working(door)
        _files.value = FileList.Closed
        job = viewModelScope.launch {
            _state.value = try {
                ImportState.Done(read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProblemException) {
                ImportState.Failed(door, e.problem.message)
            } catch (_: Exception) {
                ImportState.Failed(door, null)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = ImportState.Idle
    }

    /** Opens the list of files, or closes it when it is open. */
    fun toggleFiles() {
        listing?.cancel()
        if (_files.value != FileList.Closed) {
            _files.value = FileList.Closed
            return
        }
        _files.value = FileList.Loading
        listing = viewModelScope.launch {
            _files.value = try {
                FileList.Listed(engine.importableFiles().take(MAX_FILES_LISTED))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FileList.Listed(emptyList())
            }
        }
    }
}
