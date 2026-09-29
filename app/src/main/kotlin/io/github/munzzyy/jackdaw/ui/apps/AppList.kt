package io.github.munzzyy.jackdaw.ui.apps

import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.ui.text.canUpdateNow
import io.github.munzzyy.jackdaw.ui.text.isUpdate
import java.text.Collator
import java.util.Locale

sealed interface AppFilter {
    data object All : AppFilter
    data object Updates : AppFilter
    data object Installed : AppFilter
    data object NotInstalled : AppFilter
    data class Category(val name: String) : AppFilter
}

enum class AppSort { NAME, RECENTLY_CHECKED, SOURCE }

data class ListQuery(
    val text: String = "",
    val filter: AppFilter = AppFilter.All,
    val sort: AppSort = AppSort.NAME,
)

data class AppSections(val updates: List<AppRow>, val others: List<AppRow>) {
    val isEmpty: Boolean get() = updates.isEmpty() && others.isEmpty()
}

fun categoriesOf(rows: List<AppRow>, locale: Locale = Locale.getDefault()): List<String> {
    val collator = Collator.getInstance(locale)
    return rows.flatMap { it.config.categories }.map { it.trim() }.filter { it.isNotEmpty() }
        .distinct().sortedWith(collator)
}

fun matches(row: AppRow, text: String): Boolean {
    val q = text.trim()
    if (q.isEmpty()) return true
    val c = row.config
    return listOfNotNull(c.name, c.author, c.packageName, row.installed?.packageName, c.source.url)
        .any { it.contains(q, ignoreCase = true) }
}

fun passes(row: AppRow, filter: AppFilter): Boolean = when (filter) {
    AppFilter.All -> true
    AppFilter.Updates -> isUpdate(row)
    AppFilter.Installed -> row.installed != null
    AppFilter.NotInstalled -> row.installed == null
    is AppFilter.Category -> filter.name in row.config.categories.map { it.trim() }
}

fun arrange(rows: List<AppRow>, query: ListQuery, locale: Locale = Locale.getDefault()): AppSections {
    val collator = Collator.getInstance(locale)
    val byName = compareBy<AppRow, String>(collator) { it.config.name }
    val order: Comparator<AppRow> = when (query.sort) {
        AppSort.NAME -> byName
        AppSort.RECENTLY_CHECKED -> compareByDescending<AppRow> { it.lastCheckedMs ?: Long.MIN_VALUE }.then(byName)
        AppSort.SOURCE -> compareBy<AppRow> { it.config.source.type }.then(byName)
    }
    val kept = rows.filter { passes(it, query.filter) && matches(it, query.text) }.sortedWith(order)
    val (updates, others) = kept.partition(::isUpdate)
    return AppSections(updates, others)
}

fun updatableCount(rows: List<AppRow>): Int = rows.count(::canUpdateNow)
