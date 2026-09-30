package io.github.munzzyy.tern.ui.apps

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.ui.text.canUpdateNow

enum class BulkAction { CATEGORY, CHECK, UPDATE, REMOVE, FAVORITE, MODE, SHARE_ADDRESSES, SHARE_EXPORT, UNINSTALL, MARK_SEEN, SAVE_FILES }

const val MAX_CATEGORY = 40

/** The selected apps an action would change; an update leaves out those with nothing to install now. */
fun touched(action: BulkAction, rows: List<AppRow>): List<AppRow> = when (action) {
    BulkAction.UPDATE -> rows.filter(::canUpdateNow)
    BulkAction.UNINSTALL -> rows.filter { it.installed != null }
    BulkAction.MARK_SEEN -> rows.filter { it.config.trackOnly && it.status == AppStatus.NEW_RELEASE }
    BulkAction.SAVE_FILES -> rows.filter { it.file != null && it.latest != null }
    else -> rows
}

/** Favourites are taken back only when every picked app is one already; otherwise all become favourites. */
fun favoriteAfter(rows: List<AppRow>): Boolean = !rows.all { it.config.favorite }

/** The addresses of [rows], one a line, as Tern's and Obtainium's imports of a list of addresses read them. */
fun addressList(rows: List<AppRow>): String = rows.joinToString("\n") { it.config.source.url }

/** A category name as typed, trimmed and capped, or null when nothing usable is left. */
fun cleanCategory(text: String): String? =
    text.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(MAX_CATEGORY).trim().takeIf { it.isNotEmpty() }

/** Files the app under [name] once; an existing category of the same name, in any case, is reused as spelled. */
fun withCategory(config: AppConfig, name: String): AppConfig {
    if (config.categories.any { it.trim().equals(name, ignoreCase = true) }) return config
    return config.copy(categories = config.categories + name)
}

/** The spelling already in use for [name], so "work" joins an existing "Work" instead of making a second one. */
fun canonicalCategory(name: String, existing: List<String>): String = existing.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
