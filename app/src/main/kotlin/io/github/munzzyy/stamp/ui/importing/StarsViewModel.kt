package io.github.munzzyy.stamp.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.stamp.engine.Engine
import io.github.munzzyy.stamp.engine.Problem
import io.github.munzzyy.stamp.engine.ProblemException
import io.github.munzzyy.stamp.engine.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface StarsState {
    data object Idle : StarsState
    data object Loading : StarsState
    data class Failed(val problem: Problem?) : StarsState
    data class Listed(val user: String, val hits: List<SearchHit>, val picked: Set<String> = emptySet()) : StarsState
    data class Adding(val done: Int, val total: Int, val soFar: StarsOutcome = StarsOutcome(0, 0, emptyList())) : StarsState
    data class Done(val outcome: StarsOutcome) : StarsState
}

class StarsViewModel(private val engine: Engine) : ViewModel() {
    private val _state = MutableStateFlow<StarsState>(StarsState.Idle)
    val state: StateFlow<StarsState> = _state.asStateFlow()
    private var job: Job? = null

    fun look(user: String) {
        job?.cancel()
        _state.value = StarsState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                StarsState.Listed(user.trim().removePrefix("@"), engine.starredBy(user))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProblemException) {
                StarsState.Failed(e.problem)
            } catch (_: Exception) {
                StarsState.Failed(null)
            }
        }
    }

    fun toggle(url: String) {
        _state.update { s -> if (s is StarsState.Listed) s.copy(picked = if (url in s.picked) s.picked - url else s.picked + url) else s }
    }

    fun addPicked() {
        val listed = _state.value as? StarsState.Listed ?: return
        val picked = listed.hits.filter { it.url in listed.picked }
        if (picked.isEmpty()) return
        job?.cancel()
        _state.value = StarsState.Adding(0, picked.size)
        job = viewModelScope.launch {
            val outcome = addEach(picked, engine::detect, { engine.add(it, install = false) }) { done, soFar ->
                _state.value = StarsState.Adding(done, picked.size, soFar)
            }
            _state.value = StarsState.Done(outcome)
        }
    }

    /** Stops at once. What was added stays; the summary counts what had finished. */
    fun stop() {
        val adding = _state.value as? StarsState.Adding ?: return
        job?.cancel()
        _state.value = StarsState.Done(adding.soFar)
    }

    fun reset() {
        job?.cancel()
        _state.value = StarsState.Idle
    }
}
