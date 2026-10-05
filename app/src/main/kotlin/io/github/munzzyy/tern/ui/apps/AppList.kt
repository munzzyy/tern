package io.github.munzzyy.tern.ui.apps

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.engine.UpdateAllMode
import io.github.munzzyy.tern.ui.text.canUpdateNow
import io.github.munzzyy.tern.ui.text.isUpdate
import java.text.Collator
import java.util.Locale

/** A chip above the list. Each one turns a part of [ListFilter] on or off; All clears it. */
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

/**
 * What the list is narrowed to. Filters of one kind widen each other: two categories show the
 * apps in either, and Updates with Problems shows both. Filters of different kinds all have to
 * hold: a category with Installed shows the installed apps in it. Every word of [name], [author]
 * and [packageName] has to be found in that field.
 */
data class ListFilter(
    /** Apps with an update or a new release. */
    val updates: Boolean = false,
    /** Apps whose check failed or whose offered file Tern refused. */
    val problems: Boolean = false,
    /** True keeps installed apps only, false apps that are not installed only, null both. */
    val installed: Boolean? = null,
    /** True keeps apps that are only tracked only, false leaves them out, null keeps both. */
    val tracked: Boolean? = null,
    val favorites: Boolean = false,
    val hideUpToDate: Boolean = false,
    val categories: Set<String> = emptySet(),
    /** Source types, such as github. */
    val sources: Set<String> = emptySet(),
    val name: String = "",
    val author: String = "",
    val packageName: String = "",
) {
    /** True when anything narrows the list. */
    val isOn: Boolean
        get() = updates || problems || installed != null || tracked != null || favorites || hideUpToDate ||
            categories.isNotEmpty() || sources.isNotEmpty() || name.isNotBlank() || author.isNotBlank() || packageName.isNotBlank()
}

data class ListQuery(
    val text: String = "",
    val filter: ListFilter = ListFilter(),
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

/** What a key pressed on the list does there. */
sealed interface ListKey {
    /** Opens the search and adds [typed] to what it holds. */
    data class Search(val typed: String) : ListKey
    data object PageUp : ListKey
    data object PageDown : ListKey
    data object Top : ListKey
    data object Bottom : ListKey
}

/**
 * The list's own keys: Search or Ctrl+F opens the search and a letter typed starts one; page,
 * channel, Home and End keys move through the list. [char] is the code point the key types, 0 for
 * none, and [alt] stands for Alt or Meta held. A television's number keys are for channels and
 * start no search.
 */
fun listKey(keyCode: Int, char: Int, ctrl: Boolean, alt: Boolean, television: Boolean): ListKey? = when (keyCode) {
    KeyEvent.KEYCODE_SEARCH -> ListKey.Search("")
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> ListKey.PageUp
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> ListKey.PageDown
    KeyEvent.KEYCODE_MOVE_HOME -> ListKey.Top
    KeyEvent.KEYCODE_MOVE_END -> ListKey.Bottom
    else -> when {
        ctrl && !alt && keyCode == KeyEvent.KEYCODE_F -> ListKey.Search("")
        ctrl || alt || char <= 0 -> null
        Character.isLetter(char) || (Character.isDigit(char) && !television) -> ListKey.Search(String(Character.toChars(char)))
        else -> null
    }
}

/** The item a page key scrolls to from [first], when [shown] items are on screen of [total]: a screenful on, less the one that stays in sight. */
fun pageTarget(first: Int, shown: Int, total: Int, down: Boolean): Int {
    if (total <= 0) return 0
    val step = (shown - 1).coerceAtLeast(1)
    return (if (down) first + step else first - step).coerceIn(0, total - 1)
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

fun headerKey(group: RowGroup): String = "g-${group.key}"

/**
 * The key of what focus lands on when the list opens under keys: its first app, or the header of
 * the first group when that group is [folded]. An app in a group is keyed by the group and its id,
 * so an app filed under two categories has a place of its own in each.
 */
fun firstPlace(sections: AppSections, folded: Set<String>): String? {
    sections.updates.firstOrNull()?.let { return it.id }
    val group = sections.groups.firstOrNull() ?: return sections.others.firstOrNull()?.id
    val first = group.rows.firstOrNull()
    return if (group.key in folded || first == null) headerKey(group) else group.key + first.id
}

/** The apps the list shows: none of those inside a folded group, and an app shown twice only once. */
fun shownIds(sections: AppSections, folded: Set<String>): List<String> {
    val others = if (sections.groups.isEmpty()) sections.others else sections.groups.filter { it.key !in folded }.flatMap { it.rows }
    return (sections.updates + others).map { it.id }.distinct()
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
    val c = row.config
    return allFound(text, listOfNotNull(c.shownName, c.name, c.shownAuthor, c.author, c.packageName, row.installed?.packageName, c.source.url, sourceLabel(c.source.type)))
}

/** True when every word of [text] is in one of [fields], and when [text] has no words. */
private fun allFound(text: String, fields: List<String>): Boolean {
    val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    return words.all { word -> fields.any { it.contains(word, ignoreCase = true) } }
}

fun passes(row: AppRow, filter: ListFilter): Boolean {
    val c = row.config
    val wanted = (filter.updates && isUpdate(row)) || (filter.problems && hasProblem(row))
    return when {
        (filter.updates || filter.problems) && !wanted -> false
        filter.installed != null && (row.installed != null) != filter.installed -> false
        filter.tracked != null && c.trackOnly != filter.tracked -> false
        filter.favorites && !c.favorite -> false
        filter.hideUpToDate && row.status == AppStatus.UP_TO_DATE -> false
        filter.categories.isNotEmpty() && c.categories.none { it.trim() in filter.categories } -> false
        filter.sources.isNotEmpty() && c.source.type !in filter.sources -> false
        else -> allFound(filter.name, listOfNotNull(c.shownName, c.name)) &&
            allFound(filter.author, listOfNotNull(c.shownAuthor, c.author)) &&
            allFound(filter.packageName, listOfNotNull(c.packageName, row.installed?.packageName))
    }
}

private fun hasProblem(row: AppRow): Boolean = row.status == AppStatus.ERROR || row.status == AppStatus.BLOCKED

/** Whether [chip] is on in this filter. All is on while nothing else is. */
fun ListFilter.has(chip: AppFilter): Boolean = when (chip) {
    AppFilter.All -> !isOn
    AppFilter.Updates -> updates
    AppFilter.Installed -> installed == true
    AppFilter.NotInstalled -> installed == false
    AppFilter.Favorites -> favorites
    AppFilter.TrackOnly -> tracked == true
    AppFilter.Problems -> problems
    is AppFilter.Category -> chip.name in categories
    is AppFilter.Source -> chip.type in sources
}

/** This filter with [chip] turned on, or off where it was on. All clears everything; Installed and Not installed turn each other off. */
fun ListFilter.toggled(chip: AppFilter): ListFilter = when (chip) {
    AppFilter.All -> ListFilter()
    AppFilter.Updates -> copy(updates = !updates)
    AppFilter.Installed -> copy(installed = if (installed == true) null else true)
    AppFilter.NotInstalled -> copy(installed = if (installed == false) null else false)
    AppFilter.Favorites -> copy(favorites = !favorites)
    AppFilter.TrackOnly -> copy(tracked = if (tracked == true) null else true)
    AppFilter.Problems -> copy(problems = !problems)
    is AppFilter.Category -> copy(categories = if (chip.name in categories) categories - chip.name else categories + chip.name)
    is AppFilter.Source -> copy(sources = if (chip.type in sources) sources - chip.type else sources + chip.type)
}

/** This filter without the categories and sources that are gone from the list, which would otherwise hide every app for a reason nobody can see. */
fun ListFilter.within(categories: Collection<String>, sources: Collection<String>): ListFilter {
    val keptCategories = this.categories.filterTo(LinkedHashSet()) { it in categories }
    val keptSources = this.sources.filterTo(LinkedHashSet()) { it in sources }
    return if (keptCategories == this.categories && keptSources == this.sources) this else copy(categories = keptCategories, sources = keptSources)
}

/** The order of [query.sort], with ties broken by name, and each direction a true reversal of the other. */
fun order(query: ListQuery, locale: Locale = Locale.getDefault()): Comparator<AppRow> {
    val collator = Collator.getInstance(locale)
    val byName = compareBy<AppRow, String>(collator) { it.config.shownName }
    val primary: Comparator<AppRow> = when (query.sort) {
        AppSort.NAME -> byName
        AppSort.AUTHOR -> compareBy<AppRow, String>(collator) { it.config.shownAuthor.orEmpty() }
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
    // Stable sorts: each placement keeps the chosen order within what it moves. A project that
    // moved comes first of all, as the person has to say whether to follow it.
    val placed = kept
        .sortedBy { if (query.buryNotInstalled && it.installed == null && !it.config.trackOnly) 1 else 0 }
        .sortedBy { if (it.config.favorite) 0 else 1 }
        .sortedBy { if (it.movedTo != null) 0 else 1 }
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

/** What the Update all button would start: [updates] of installed apps, and [installs] of apps not installed yet. */
data class UpdateAllPlan(val updates: Int, val installs: Int) {
    val total: Int get() = updates + installs
}

/**
 * The Update all button as the setting has it, with [updatable] updates and [installable] first
 * installs to start; null when there is no button. One app has its own button in its row, so
 * the button stands only for two or more.
 */
fun updateAllPlan(mode: UpdateAllMode, updatable: Int, installable: Int): UpdateAllPlan? = when (mode) {
    UpdateAllMode.NONE -> null
    UpdateAllMode.UPDATES -> UpdateAllPlan(updatable, 0)
    UpdateAllMode.ALL -> UpdateAllPlan(updatable, installable)
}?.takeIf { it.total >= 2 }

/** What the button that starts [plan] says. */
@StringRes
fun updateAllLabel(plan: UpdateAllPlan): Int = when {
    plan.installs == 0 -> R.string.action_update_all
    plan.updates == 0 -> R.string.action_install_all
    else -> R.string.action_install_update_all
}

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
 * The chips worth showing: All, and each one that keeps some rows and drops others. Empty when
 * the rows are all of one kind, so there is nothing to filter. A chip that is on in [current] is
 * always among them, so it can be turned off again.
 */
fun offeredFilters(rows: List<AppRow>, categories: List<String>, current: ListFilter): List<AppFilter> {
    val narrowing = listOf(
        AppFilter.Updates, AppFilter.Installed, AppFilter.NotInstalled, AppFilter.Favorites, AppFilter.TrackOnly, AppFilter.Problems,
    ) + categories.map { AppFilter.Category(it) } + sourcesOf(rows).map { AppFilter.Source(it) }
    val useful = narrowing.filter { chip ->
        current.has(chip) || rows.count { passes(it, ListFilter().toggled(chip)) } in 1 until rows.size
    }
    return if (useful.isEmpty()) emptyList() else listOf(AppFilter.All) + useful
}
