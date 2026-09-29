package io.github.munzzyy.stamp.ui.apps

import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.engine.AppRow
import io.github.munzzyy.stamp.ui.text.canUpdateNow

enum class BulkAction { CATEGORY, CHECK, UPDATE, REMOVE }

const val MAX_CATEGORY = 40

/** The selected apps an action would change; an update leaves out those with nothing to install now. */
fun touched(action: BulkAction, rows: List<AppRow>): List<AppRow> = when (action) {
    BulkAction.UPDATE -> rows.filter(::canUpdateNow)
    else -> rows
}

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
