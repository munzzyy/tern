package io.github.munzzyy.tern.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.core.interop.AddressList
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.ui.add.filterHits
import io.github.munzzyy.tern.ui.text.hostOf
import io.github.munzzyy.tern.ui.text.shortUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is picked of [listed] once everything it shows is ticked, or with [on] false unticked. */
fun pickedShown(listed: StarsState.Listed, on: Boolean): Set<String> {
    val shown = listed.shown.mapTo(HashSet()) { it.url }
    return if (on) listed.picked + shown else listed.picked - shown
}

sealed interface StarsState {
    data object Idle : StarsState
    data object Loading : StarsState
    data class Failed(val problem: Problem?) : StarsState
    /** [shown] is what [filter] leaves of [hits]; what is picked stays picked while it is hidden. */
    data class Listed(
        val user: String,
        val hits: List<SearchHit>,
        val picked: Set<String> = emptySet(),
        val filter: String = "",
        val shown: List<SearchHit> = hits,
    ) : StarsState
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

    /**
     * The https addresses in [text], all of them picked, for the same one-by-one add as starred
     * repositories get. A list with none shows as empty; nothing is fetched until Add.
     */
    fun showAddresses(text: String) {
        job?.cancel()
        val hits = AddressList.read(text).map { url ->
            SearchHit(name = shortUrl(url), owner = null, description = null, url = url, origin = hostOf(url))
        }
        _state.value = StarsState.Listed(user = "", hits = hits, picked = hits.mapTo(HashSet()) { it.url })
    }

    fun toggle(url: String) {
        _state.update { s -> if (s is StarsState.Listed) s.copy(picked = if (url in s.picked) s.picked - url else s.picked + url) else s }
    }

    /** Ticks what the filter shows, or with [on] false unticks it; what it hides stays as it is. */
    fun pickShown(on: Boolean) {
        _state.update { s -> if (s is StarsState.Listed) s.copy(picked = pickedShown(s, on)) else s }
    }

    private var filtering: Job? = null

    /** Shows only what matches [text], worked out away from the screen's thread since it may be a pattern. */
    fun filter(text: String) {
        val listed = _state.value as? StarsState.Listed ?: return
        _state.value = listed.copy(filter = text)
        filtering?.cancel()
        filtering = viewModelScope.launch {
            val shown = withContext(Dispatchers.Default) { filterHits(listed.hits, text) }
            _state.update { s -> if (s is StarsState.Listed && s.filter == text) s.copy(shown = shown) else s }
        }
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
