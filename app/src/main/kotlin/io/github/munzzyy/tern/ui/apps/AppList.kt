package io.github.munzzyy.tern.ui.apps

import androidx.compose.ui.unit.Dp
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.text.canUpdateNow
import io.github.munzzyy.tern.ui.text.isUpdate
import java.text.Collator
import java.util.Locale

sealed interface AppFilter {
    data object All : AppFilter
    data object Updates : AppFilter
    data object Installed : AppFilter
    data object NotInstalled : AppFilter
    data object Favorites : AppFilter
    data object TrackOnly : AppFilter

    /** Apps whose last check failed or whose offered file Tern refused. */
    data object Problems : AppFilter
    data class Category(val name: String) : AppFilter

    /** Apps from one kind of source, such as GitHub or F-Droid. */
    data class Source(val type: String) : AppFilter
}

data class ListQuery(
    val text: String = "",
    val filter: AppFilter = AppFilter.All,
    val sort: AppSort = AppSort.NAME,
    val descending: Boolean = false,
    val grouping: AppGrouping = AppGrouping.NONE,
    val updatesFirst: Boolean = true,
    val buryNotInstalled: Boolean = false,
) {
    companion object {
        /** The order, grouping and placement the person chose, with nothing typed and no filter. */
        fun of(settings: Settings): ListQuery = ListQuery(
            sort = settings.listSort,
            descending = settings.listDescending,
            grouping = settings.listGrouping,
            updatesFirst = settings.updatesFirst,
            buryNotInstalled = settings.buryNotInstalled,
        )
    }
}

/** A group of the list. [title] is null for the apps that belong to none, such as those without a category. */
data class RowGroup(val key: String, val title: String?, val rows: List<AppRow>)

/** What the key of a group of one category starts with. */
const val CATEGORY_GROUP = "c:"

/**
 * The list as it is drawn: [updates] on top, then [others]. When the list is grouped, [groups]
 * holds [others] again, divided.
 */
data class AppSections(val updates: List<AppRow>, val others: List<AppRow>, val groups: List<RowGroup> = emptyList()) {
    val isEmpty: Boolean get() = updates.isEmpty() && others.isEmpty()
}

fun categoriesOf(rows: List<AppRow>, locale: Locale = Locale.getDefault()): List<String> {
    val collator = Collator.getInstance(locale)
    return rows.flatMap { it.config.categories }.map { it.trim() }.filter { it.isNotEmpty() }
        .distinct().sortedWith(collator)
}

/** Source types present in [rows], by the name shown for them. */
fun sourcesOf(rows: List<AppRow>, locale: Locale = Locale.getDefault()): List<String> {
    val collator = Collator.getInstance(locale)
    return rows.map { it.config.source.type }.distinct().sortedWith(compareBy(collator) { sourceLabel(it) })
}

/** What a source type is called in a filter or a group heading. */
fun sourceLabel(type: String): String = SourceTypes.displayName(type) ?: type

/** Every word typed has to be found, in the name, the author, the package or the address. */
fun matches(row: AppRow, text: String): Boolean {
    val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val c = row.config
    val fields = listOfNotNull(c.name, c.author, c.packageName, row.installed?.packageName, c.source.url, sourceLabel(c.source.type))
    return words.all { word -> fields.any { it.contains(word, ignoreCase = true) } }
}

fun passes(row: AppRow, filter: AppFilter): Boolean = when (filter) {
    AppFilter.All -> true
    AppFilter.Updates -> isUpdate(row)
    AppFilter.Installed -> row.installed != null
    AppFilter.NotInstalled -> row.installed == null
    AppFilter.Favorites -> row.config.favorite
    AppFilter.TrackOnly -> row.config.trackOnly
    AppFilter.Problems -> row.status == AppStatus.ERROR || row.status == AppStatus.BLOCKED
    is AppFilter.Category -> filter.name in row.config.categories.map { it.trim() }
    is AppFilter.Source -> row.config.source.type == filter.type
}

/** The order of [query.sort], with ties broken by name, and each direction a true reversal of the other. */
fun order(query: ListQuery, locale: Locale = Locale.getDefault()): Comparator<AppRow> {
    val collator = Collator.getInstance(locale)
    val byName = compareBy<AppRow, String>(collator) { it.config.name }
    val primary: Comparator<AppRow> = when (query.sort) {
        AppSort.NAME -> byName
        AppSort.AUTHOR -> compareBy<AppRow, String>(collator) { it.config.author.orEmpty() }
        AppSort.ADDED -> compareBy { it.addedAtMs ?: Long.MIN_VALUE }
        AppSort.RELEASED -> compareBy { it.latest?.publishedAtMs ?: Long.MIN_VALUE }
        // Newest first reads as the natural order here, so ascending means most recent first.
        AppSort.RECENTLY_CHECKED -> compareByDescending { it.lastCheckedMs ?: Long.MIN_VALUE }
        AppSort.SOURCE -> compareBy<AppRow, String>(collator) { sourceLabel(it.config.source.type) }
    }
    val directed = if (query.descending) primary.reversed() else primary
    return directed.then(byName).then(compareBy { it.id })
}

fun arrange(rows: List<AppRow>, query: ListQuery, locale: Locale = Locale.getDefault()): AppSections {
    val kept = rows.filter { passes(it, query.filter) && matches(it, query.text) }.sortedWith(order(query, locale))
    // Stable sorts: each placement keeps the chosen order within what it moves.
    val placed = kept
        .sortedBy { if (query.buryNotInstalled && it.installed == null && !it.config.trackOnly) 1 else 0 }
        .sortedBy { if (it.config.favorite) 0 else 1 }
    val (updates, others) = if (query.updatesFirst) placed.partition(::isUpdate) else emptyList<AppRow>() to placed
    return AppSections(updates, others, group(others, query.grouping, locale))
}

/** [rows] divided by [grouping], the groups by name and the rows that belong to none last. */
fun group(rows: List<AppRow>, grouping: AppGrouping, locale: Locale = Locale.getDefault()): List<RowGroup> = when (grouping) {
    AppGrouping.NONE -> emptyList()
    AppGrouping.CATEGORY -> {
        val named = categoriesOf(rows, locale).map { name ->
            RowGroup("$CATEGORY_GROUP$name", name, rows.filter { row -> name in row.config.categories.map { it.trim() } })
        }
        val none = rows.filter { row -> row.config.categories.none { it.isNotBlank() } }
        named + listOfNotNull(none.takeIf { it.isNotEmpty() }?.let { RowGroup(CATEGORY_GROUP, null, it) })
    }
    AppGrouping.SOURCE -> sourcesOf(rows, locale).map { type ->
        RowGroup("s:$type", sourceLabel(type), rows.filter { it.config.source.type == type })
    }
}

fun updatableCount(rows: List<AppRow>): Int = rows.count(::canUpdateNow)

private val WHITESPACE = Regex("\\s+")
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
    val narrowing = listOf(
        AppFilter.Updates, AppFilter.Installed, AppFilter.NotInstalled, AppFilter.Favorites, AppFilter.TrackOnly, AppFilter.Problems,
    ) + categories.map { AppFilter.Category(it) } + sourcesOf(rows).map { AppFilter.Source(it) }
    val useful = narrowing.filter { filter -> filter == current || rows.count { passes(it, filter) } in 1 until rows.size }
    return if (useful.isEmpty()) emptyList() else listOf(AppFilter.All) + useful
}
