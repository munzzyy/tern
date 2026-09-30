package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.text.Shown

private const val MAX_SHORT = 200
private const val MAX_LONG = 4000

/**
 * A configuration that came in a file or a link, with what a person will read of it fit to be
 * shown. Filters, addresses and hashes are not read by a person and stay as they came.
 */
internal fun shown(config: AppConfig): AppConfig = config.copy(
    name = Shown.line(config.name, MAX_SHORT).ifEmpty { config.source.url.take(MAX_SHORT) },
    author = Shown.lineOrNull(config.author, MAX_SHORT),
    categories = config.categories.mapNotNull { Shown.lineOrNull(it, MAX_SHORT) },
    notes = config.notes?.let { Shown.prose(it, MAX_LONG) },
    customName = Shown.lineOrNull(config.customName, MAX_SHORT),
    customAuthor = Shown.lineOrNull(config.customAuthor, MAX_SHORT),
)
