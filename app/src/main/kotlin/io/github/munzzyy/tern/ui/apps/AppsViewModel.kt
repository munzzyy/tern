package io.github.munzzyy.tern.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.ui.text.isWaitingForUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class AppsState(
    val loaded: Boolean = false,
    val total: Int = 0,
    val sections: AppSections = AppSections(emptyList(), emptyList()),
    val categories: List<String> = emptyList(),
    val updatable: Int = 0,
    /** The filters to show, none when there is nothing to tell apart. */
    val filters: List<AppFilter> = emptyList(),
    /** Apps whose install waits for the user, whatever the search and the filter hide. */
    val waiting: List<AppRow> = emptyList(),
)

/** What the list shows of [rows]: the apps in [gone] were removed and can still be taken back, so they are left out. */
fun listState(rows: List<AppRow>, query: ListQuery, gone: Set<String>): AppsState {
    val shown = if (gone.isEmpty()) rows else rows.filterNot { it.id in gone }
    val categories = categoriesOf(shown)
    val filter = query.filter
    val effective = if (filter is AppFilter.Category && filter.name !in categories) query.copy(filter = AppFilter.All) else query
    val everything = arrange(shown, ListQuery(sort = query.sort))
    return AppsState(
        loaded = true,
        total = shown.size,
        sections = arrange(shown, effective),
        categories = categories,
        updatable = updatableCount(shown),
        filters = offeredFilters(shown, categories, effective.filter),
        waiting = (everything.updates + everything.others).filter(::isWaitingForUser),
    )
}

class AppsViewModel(engine: Engine, hidden: StateFlow<Set<String>> = MutableStateFlow(emptySet())) : ViewModel() {
    private val _query = MutableStateFlow(ListQuery())
    val query: StateFlow<ListQuery> = _query.asStateFlow()

    val state: StateFlow<AppsState> = combine(engine.apps, _query, hidden, ::listState)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsState())

    fun setText(text: String) {
        _query.value = _query.value.copy(text = text.take(200))
    }

    fun setFilter(filter: AppFilter) {
        _query.value = _query.value.copy(filter = filter)
    }

    fun setSort(sort: AppSort) {
        _query.value = _query.value.copy(sort = sort)
    }

    private val _selection = MutableStateFlow<Set<String>?>(null)

    /** Ids picked for a bulk action; null while not selecting. */
    val selection: StateFlow<Set<String>?> = _selection.asStateFlow()

    fun startSelecting(first: String? = null) {
        _selection.value = setOfNotNull(first)
    }

    fun toggle(id: String) {
        _selection.update { s -> s?.let { if (id in it) it - id else it + id } }
    }

    fun selectAll(ids: Collection<String>) {
        _selection.update { s -> s?.plus(ids) }
    }

    fun stopSelecting() {
        _selection.value = null
    }
}
