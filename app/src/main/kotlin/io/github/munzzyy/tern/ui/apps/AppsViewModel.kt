package io.github.munzzyy.tern.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.text.canInstallNow
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
    /** The filter as it applies: without the categories and sources that are gone from the list. */
    val filter: ListFilter = ListFilter(),
    /** How many apps the search and the filter leave. */
    val shown: Int = 0,
    /** Source types in the list, for the filter. */
    val sources: List<String> = emptyList(),
    /** Apps whose first install can start now, whatever the search and the filter hide, in the order of the list. */
    val firstInstalls: List<String> = emptyList(),
)

/** What the list shows of [rows]: the apps in [gone] were removed and can still be taken back, so they are left out. */
fun listState(rows: List<AppRow>, query: ListQuery, gone: Set<String>): AppsState {
    val shown = if (gone.isEmpty()) rows else rows.filterNot { it.id in gone }
    val categories = categoriesOf(shown)
    val sources = sourcesOf(shown)
    val filter = query.filter.within(categories, sources)
    val sections = arrange(shown, query.copy(filter = filter))
    val everything = arrange(shown, ListQuery(sort = query.sort)).let { it.updates + it.others }
    return AppsState(
        loaded = true,
        total = shown.size,
        sections = sections,
        categories = categories,
        updatable = updatableCount(shown),
        filters = offeredFilters(shown, categories, filter),
        waiting = everything.filter(::isWaitingForUser),
        filter = filter,
        shown = sections.updates.size + sections.others.size,
        sources = sources,
        firstInstalls = everything.filter(::canInstallNow).map { it.id },
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

    fun setFilter(filter: ListFilter) {
        _query.update { it.copy(filter = filter) }
    }

    /** Turns [chip] on, or off where it was on; All clears every filter and the search. */
    fun toggleFilter(chip: AppFilter) {
        if (chip == AppFilter.All) clearFilters() else _query.update { it.copy(filter = it.filter.toggled(chip)) }
    }

    /** Clears the search and every filter at once. */
    fun clearFilters() {
        _query.update { it.copy(text = "", filter = ListFilter()) }
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
