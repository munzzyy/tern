package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Release

/**
 * [releases] with the one the forge marks as its latest flagged as such, and put in front when
 * the list lacks it, still at most [max] long.
 */
internal fun withLatest(releases: List<Release>, latest: Release?, max: Int): List<Release> {
    if (latest == null) return releases
    if (releases.any { it.id == latest.id }) return releases.map { if (it.id == latest.id) it.copy(latest = true) else it }
    return (listOf(latest.copy(latest = true)) + releases).take(max)
}

/** When the newest of a release's files was uploaded or last changed, or null when none says. */
internal fun newestFileMs(assets: List<JsonObject>): Long? =
    assets.mapNotNull { (it.string("updated_at") ?: it.string("created_at"))?.let(Iso8601::parseMs) }.maxOrNull()
