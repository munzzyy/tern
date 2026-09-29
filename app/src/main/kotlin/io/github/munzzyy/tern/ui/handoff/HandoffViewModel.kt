package io.github.munzzyy.tern.ui.handoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Handoff
import io.github.munzzyy.tern.engine.HandoffEnd
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.Received
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the handoff is not open, when it is not. */
sealed interface Closed {
    /** Not opened yet, or closed from this device: nothing is said about it. */
    data object Quiet : Closed

    data class Ended(val why: HandoffEnd) : Closed

    /** It could not be opened. [problem] is null when the engine gave no reason. */
    data class Failed(val problem: Problem?) : Closed
}

/** An opening that did not work, with the engine's reason when it gave one. */
data class Failure(val problem: Problem?)

/** What the screen says while no handoff is open: why the last opening failed, else why the last handoff ended. */
fun closedFor(failure: Failure?, end: HandoffEnd?): Closed = when {
    failure != null -> Closed.Failed(failure.problem)
    end == null || end == HandoffEnd.CLOSED -> Closed.Quiet
    else -> Closed.Ended(end)
}

/**
 * Opens the handoff when its screen is entered and closes it when the screen is left. What has
 * arrived is taken from the engine at once and kept here, so it is still listed when the user
 * comes back from looking at one of the links.
 */
class HandoffViewModel(private val engine: Engine) : ViewModel() {
    val handoff: StateFlow<Handoff?> = engine.handoff

    private val _arrivals = MutableStateFlow<List<Arrival>>(emptyList())
    val arrivals: StateFlow<List<Arrival>> = _arrivals.asStateFlow()

    private val _opening = MutableStateFlow(false)
    val opening: StateFlow<Boolean> = _opening.asStateFlow()

    val end: StateFlow<HandoffEnd?> = engine.handoffEnd

    private val _failure = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> = _failure.asStateFlow()

    private val files = HashMap<Long, Received.ExportFile>()
    private var nextId = 1L
    private var entered = false

    init {
        viewModelScope.launch {
            engine.handoff.collect { if ((it?.waiting ?: 0) > 0) take() }
        }
    }

    /** The screen is shown. A screen that is only drawn again, as after the device was turned, has never left. */
    fun enter() {
        if (entered) return
        entered = true
        take()
        open()
    }

    /** An opening that is under way is not stopped half way: it closes what it opened when it finds the screen gone. */
    fun leave() {
        entered = false
        take()
        closeQuietly()
        _failure.value = null
    }

    fun open() {
        if (_opening.value) return
        _opening.value = true
        viewModelScope.launch {
            val problem = try {
                engine.openHandoff()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProblemException) {
                e.problem
            } catch (_: Exception) {
                _failure.value = Failure(null)
                _opening.value = false
                return@launch
            }
            _failure.value = problem?.let(::Failure)
            _opening.value = false
            if (!entered) closeQuietly()
        }
    }

    fun looked(id: Long) {
        _arrivals.update { list -> list.map { if (it is Arrival.Link && it.id == id) it.copy(looked = true) else it } }
    }

    fun import(id: Long) {
        val file = files[id] ?: return
        val row = _arrivals.value.firstOrNull { it.id == id } as? Arrival.File ?: return
        if (row.state == FileState.Working || row.state is FileState.Done) return
        set(id, FileState.Working)
        viewModelScope.launch {
            val state = try {
                FileState.Done(engine.importReceived(file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProblemException) {
                FileState.Failed(e.problem.message)
            } catch (_: Exception) {
                FileState.Failed(null)
            }
            if (state is FileState.Done) files.remove(id)
            set(id, state)
        }
    }

    private fun set(id: Long, state: FileState) {
        _arrivals.update { list -> list.map { if (it is Arrival.File && it.id == id) it.copy(state = state) else it } }
    }

    private fun take() {
        val taken = try {
            engine.takeReceived()
        } catch (_: Exception) {
            emptyList()
        }
        if (taken.isEmpty()) return
        val arrived = taken.take(MAX_ARRIVALS).mapNotNull { item ->
            val id = nextId++
            arrivalOf(id, item)?.also { if (item is Received.ExportFile) files[id] = item }
        }
        val now = listed(_arrivals.value, arrived)
        files.keys.retainAll(now.mapTo(HashSet()) { it.id })
        _arrivals.value = now
    }

    private fun closeQuietly() {
        try {
            engine.closeHandoff()
        } catch (_: Exception) {
            // Closing is all that was wanted.
        }
    }

    override fun onCleared() {
        if (entered) closeQuietly()
    }
}
