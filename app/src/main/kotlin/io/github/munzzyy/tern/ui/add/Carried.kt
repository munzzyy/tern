package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.model.VersionFrom

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
    data class CustomName(val name: String) : CarriedSetting
    data class CustomAuthor(val author: String) : CarriedSetting
    data class VersionFilter(val pattern: String) : CarriedSetting
    data class MatchGroup(val group: String) : CarriedSetting
    data class InnerFilter(val pattern: String) : CarriedSetting
    data object Archives : CarriedSetting
    data class StayBehind(val releases: Int) : CarriedSetting
    data class ReadVersionFrom(val from: VersionFrom) : CarriedSetting
    data class Order(val order: ReleaseOrder) : CarriedSetting
    data object Muted : CarriedSetting
    data object PlayInstaller : CarriedSetting
    data object RefreshFirst : CarriedSetting
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
    // A name chosen by whoever made the link can make one app look like another, so it is said first among these.
    proposed.customName?.takeIf { it != plain.customName }?.let { add(CarriedSetting.CustomName(it)) }
    proposed.customAuthor?.takeIf { it != plain.customAuthor }?.let { add(CarriedSetting.CustomAuthor(it)) }
    r.versionFilter?.takeIf { it != p.versionFilter }?.let { add(CarriedSetting.VersionFilter(it)) }
    r.matchGroup?.takeIf { it != p.matchGroup }?.let { add(CarriedSetting.MatchGroup(it)) }
    proposed.assets.innerFilter?.takeIf { it != plain.assets.innerFilter }?.let { add(CarriedSetting.InnerFilter(it)) }
    if (proposed.assets.archives && !plain.assets.archives) add(CarriedSetting.Archives)
    if (r.stayBehind != p.stayBehind && r.stayBehind > 0) add(CarriedSetting.StayBehind(r.stayBehind))
    if (r.versionFrom != p.versionFrom) add(CarriedSetting.ReadVersionFrom(r.versionFrom))
    if (r.order != p.order) add(CarriedSetting.Order(r.order))
    if (proposed.muted && !plain.muted) add(CarriedSetting.Muted)
    if (proposed.playInstaller && !plain.playInstaller) add(CarriedSetting.PlayInstaller)
    if (proposed.refreshFirst && !plain.refreshFirst) add(CarriedSetting.RefreshFirst)
    for (pin in proposed.pinnedSigners.distinct()) if (pin !in plain.pinnedSigners) add(CarriedSetting.Pin(pin))
}
