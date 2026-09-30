package io.github.munzzyy.tern.ui.apps

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.ui.detail.toggledCategory
import io.github.munzzyy.tern.ui.text.canInstallNow
import io.github.munzzyy.tern.ui.text.canUpdateNow

enum class BulkAction { CATEGORY, CHECK, UPDATE, REMOVE, FAVORITE, MODE, SHARE_ADDRESSES, SHARE_EXPORT, UNINSTALL, MARK_SEEN, SAVE_FILES, INSTALL, SHARE_LINKS }

const val MAX_CATEGORY = 40

/** The selected apps an action would change; an update leaves out those with nothing to install now. */
fun touched(action: BulkAction, rows: List<AppRow>): List<AppRow> = when (action) {
    BulkAction.UPDATE -> rows.filter(::canUpdateNow)
    BulkAction.UNINSTALL -> rows.filter { it.installed != null }
    BulkAction.MARK_SEEN -> rows.filter { it.config.trackOnly && it.status == AppStatus.NEW_RELEASE }
    BulkAction.SAVE_FILES -> rows.filter { it.latest?.savable?.isNotEmpty() == true }
    BulkAction.INSTALL -> rows.filter(::canInstallNow)
    else -> rows
}

/** Favourites are taken back only when every picked app is one already; otherwise all become favourites. */
fun favoriteAfter(rows: List<AppRow>): Boolean = !rows.all { it.config.favorite }

/** The addresses of [rows], one a line, as Tern's and Obtainium's imports of a list of addresses read them. */
fun addressList(rows: List<AppRow>): String = rows.joinToString("\n") { it.config.source.url }

/** A category name as typed, trimmed and capped, or null when nothing usable is left. */
fun cleanCategory(text: String): String? =
    text.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(MAX_CATEGORY).trim().takeIf { it.isNotEmpty() }

/** The spelling already in use for [name], so "work" joins an existing "Work" instead of making a second one. */
fun canonicalCategory(name: String, existing: List<String>): String = existing.firstOrNull { it.equals(name, ignoreCase = true) } ?: name

/** How many of the picked apps are filed under a category. */
enum class Filed { ALL, SOME, NONE }

fun filedUnder(rows: List<AppRow>, name: String): Filed {
    val filed = rows.count { row -> row.config.categories.any { it.trim().equals(name, ignoreCase = true) } }
    return when (filed) {
        0 -> Filed.NONE
        rows.size -> Filed.ALL
        else -> Filed.SOME
    }
}

/** What a press on a category makes of it. Only a category some of the apps were under can go back to that. */
fun nextFiled(now: Filed, before: Filed): Filed = when (now) {
    Filed.ALL -> Filed.NONE
    Filed.NONE -> if (before == Filed.SOME) Filed.SOME else Filed.ALL
    Filed.SOME -> Filed.ALL
}

/**
 * [config] under the categories [chosen] names: every app is filed under one chosen [Filed.ALL]
 * and taken out of one chosen [Filed.NONE]. A category left at [Filed.SOME] stays as each app has it.
 */
fun withCategories(config: AppConfig, chosen: Map<String, Filed>): AppConfig {
    var categories = config.categories
    for ((name, filed) in chosen) {
        when (filed) {
            Filed.ALL -> categories = toggledCategory(categories, name, on = true)
            Filed.NONE -> categories = toggledCategory(categories, name, on = false)
            Filed.SOME -> Unit
        }
    }
    return if (categories == config.categories) config else config.copy(categories = categories)
}
