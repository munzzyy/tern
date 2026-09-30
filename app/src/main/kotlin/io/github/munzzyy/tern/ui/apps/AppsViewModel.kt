package io.github.munzzyy.tern.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Settings
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
import kotlinx.coroutines.launch

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

/** The groups folded now: those the person folded, or, when all start folded, those not opened since. */
fun foldedGroups(groups: List<RowGroup>, toggled: Set<String>, foldedAtStart: Boolean): Set<String> =
    if (!foldedAtStart) toggled else groups.mapTo(LinkedHashSet()) { it.key }.filterTo(LinkedHashSet()) { it !in toggled }

class AppsViewModel(private val engine: Engine, hidden: StateFlow<Set<String>> = MutableStateFlow(emptySet())) : ViewModel() {
    /** What was typed and the filter; the order and grouping come from the settings, so they last. */
    private val _query = MutableStateFlow(ListQuery())

    val query: StateFlow<ListQuery> = combine(_query, engine.settings) { typed, settings ->
        ListQuery.of(settings).copy(text = typed.text, filter = typed.filter)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListQuery.of(engine.settings.value))

    val state: StateFlow<AppsState> = combine(engine.apps, query, hidden, ::listState)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsState())

    /** Read once: the setting says how groups start, not what happens to those already opened. */
    private val foldedAtStart = engine.settings.value.collapseGroups
    private val _toggled = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the groups folded away. Not kept: groups start open again when Tern starts, or folded when the setting asks. */
    val collapsed: StateFlow<Set<String>> = combine(state, _toggled) { s, toggled -> foldedGroups(s.sections.groups, toggled, foldedAtStart) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleGroup(key: String) {
        _toggled.update { if (key in it) it - key else it + key }
    }

    fun setText(text: String) {
        _query.value = _query.value.copy(text = text.take(200))
    }

    fun setFilter(filter: AppFilter) {
        _query.value = _query.value.copy(filter = filter)
    }

    fun setSort(sort: AppSort) = saveList { it.copy(listSort = sort) }

    fun setDescending(descending: Boolean) = saveList { it.copy(listDescending = descending) }

    fun setGrouping(grouping: AppGrouping) = saveList { it.copy(listGrouping = grouping) }

    fun setUpdatesFirst(first: Boolean) = saveList { it.copy(updatesFirst = first) }

    fun setBuryNotInstalled(bury: Boolean) = saveList { it.copy(buryNotInstalled = bury) }

    private fun saveList(change: (Settings) -> Settings) {
        viewModelScope.launch { engine.saveSettings(change(engine.settings.value)) }
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
