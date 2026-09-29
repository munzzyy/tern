package io.github.munzzyy.stamp.ui.apps

import androidx.compose.ui.unit.Dp
import io.github.munzzyy.stamp.engine.AppRow
import io.github.munzzyy.stamp.ui.text.canUpdateNow
import io.github.munzzyy.stamp.ui.text.isUpdate
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

private const val SEARCH_SHOWN_FROM = 8
private const val STACK_FONT_SCALE = 1.5f
private const val ICONS_PER_ROW = 9

/** Where the one action of a row goes. */
enum class ActionPlace { BESIDE, UNDER, NOWHERE }

/**
 * The action stands beside the words of a row where there is room for both. Where the text is
 * large, or the list so narrow, measured in icons, that the words would be squeezed into a few
 * letters a line, it goes under them. Without a touch screen it is left out there instead: a
 * button under every row would double the presses it takes to walk the list, and the detail
 * that opens beside or over the list has the same action under the same key.
 */
fun actionPlace(width: Dp, icon: Dp, fontScale: Float, noTouch: Boolean): ActionPlace = when {
    fontScale < STACK_FONT_SCALE && width >= icon * ICONS_PER_ROW -> ActionPlace.BESIDE
    noTouch -> ActionPlace.NOWHERE
    else -> ActionPlace.UNDER
}

/**
 * A short list needs no search field in the way: it is folded into a glyph at the top of the
 * screen. On a television it always is, where typing is the last thing anyone wants to do.
 */
fun foldsSearch(total: Int, television: Boolean = false): Boolean = television || total < SEARCH_SHOWN_FROM

/**
 * The filters worth showing: All, and each one that keeps some rows and drops others. Empty
 * when the rows are all of one kind, so there is nothing to filter. [current] is always among
 * them, so a filter that was taken can be left again.
 */
fun offeredFilters(rows: List<AppRow>, categories: List<String>, current: AppFilter): List<AppFilter> {
    val narrowing = listOf(AppFilter.Updates, AppFilter.Installed, AppFilter.NotInstalled) + categories.map { AppFilter.Category(it) }
    val useful = narrowing.filter { filter -> filter == current || rows.count { passes(it, filter) } in 1 until rows.size }
    return if (useful.isEmpty()) emptyList() else listOf(AppFilter.All) + useful
}
