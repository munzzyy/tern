package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.UpdateMode

/** One setting that a link or file brings with it, differing from what a plain new app would get. */
sealed interface CarriedSetting {
    data class Mode(val mode: UpdateMode) : CarriedSetting
    data class Prereleases(val on: Boolean) : CarriedSetting
    data class MinAge(val days: Int) : CarriedSetting
    data class Include(val pattern: String?) : CarriedSetting
    data class Exclude(val pattern: String?) : CarriedSetting
    data class TagFilter(val pattern: String?) : CarriedSetting
    data class TitleFilter(val pattern: String?) : CarriedSetting
    data class NotesFilter(val pattern: String?) : CarriedSetting
    data class VersionPattern(val pattern: String?) : CarriedSetting
    data class MatchDevice(val on: Boolean) : CarriedSetting
    data class FallBack(val on: Boolean) : CarriedSetting
    data class TrackOnly(val on: Boolean) : CarriedSetting
    data class Pin(val sha256: String) : CarriedSetting
}

/** [plain] is what the engine would store for the same app without carried settings. */
fun carriedSettings(proposed: AppConfig, plain: AppConfig): List<CarriedSetting> = buildList {
    val r = proposed.releases
    val p = plain.releases
    if (proposed.updates != plain.updates) add(CarriedSetting.Mode(proposed.updates))
    if (r.includePrereleases != p.includePrereleases) add(CarriedSetting.Prereleases(r.includePrereleases))
    if (r.minAgeDays != p.minAgeDays) add(CarriedSetting.MinAge(r.minAgeDays))
    if (proposed.assets.include != plain.assets.include) add(CarriedSetting.Include(proposed.assets.include))
    if (proposed.assets.exclude != plain.assets.exclude) add(CarriedSetting.Exclude(proposed.assets.exclude))
    if (r.tagFilter != p.tagFilter) add(CarriedSetting.TagFilter(r.tagFilter))
    if (r.titleFilter != p.titleFilter) add(CarriedSetting.TitleFilter(r.titleFilter))
    if (r.notesFilter != p.notesFilter) add(CarriedSetting.NotesFilter(r.notesFilter))
    if (r.versionExtract != p.versionExtract) add(CarriedSetting.VersionPattern(r.versionExtract))
    if (proposed.assets.matchDevice != plain.assets.matchDevice) add(CarriedSetting.MatchDevice(proposed.assets.matchDevice))
    if (r.fallbackToOlder != p.fallbackToOlder) add(CarriedSetting.FallBack(r.fallbackToOlder))
    if (proposed.trackOnly != plain.trackOnly) add(CarriedSetting.TrackOnly(proposed.trackOnly))
    for (pin in proposed.pinnedSigners.distinct()) if (pin !in plain.pinnedSigners) add(CarriedSetting.Pin(pin))
}
