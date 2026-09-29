package io.github.munzzyy.jackdaw.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class AppsState(
    val loaded: Boolean = false,
    val total: Int = 0,
    val sections: AppSections = AppSections(emptyList(), emptyList()),
    val categories: List<String> = emptyList(),
    val updatable: Int = 0,
)

class AppsViewModel(engine: Engine) : ViewModel() {
    private val _query = MutableStateFlow(ListQuery())
    val query: StateFlow<ListQuery> = _query.asStateFlow()

    val state: StateFlow<AppsState> = combine(engine.apps, _query) { rows: List<AppRow>, q: ListQuery ->
        val categories = categoriesOf(rows)
        val filter = q.filter
        val effective = if (filter is AppFilter.Category && filter.name !in categories) q.copy(filter = AppFilter.All) else q
        AppsState(
            loaded = true,
            total = rows.size,
            sections = arrange(rows, effective),
            categories = categories,
            updatable = updatableCount(rows),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsState())

    fun setText(text: String) {
        _query.value = _query.value.copy(text = text.take(200))
    }

    fun setFilter(filter: AppFilter) {
        _query.value = _query.value.copy(filter = filter)
    }

    fun setSort(sort: AppSort) {
        _query.value = _query.value.copy(sort = sort)
    }
}
