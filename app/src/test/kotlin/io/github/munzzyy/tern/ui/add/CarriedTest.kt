package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.model.VersionFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarriedTest {
    private val plain = AppConfig("finch", SourceSpec("github", "https://github.com/example/finch"), "Finch")
    private val pin = "ab".repeat(32)

    @Test
    fun aPlainAppCarriesNothing() {
        assertTrue(carriedSettings(plain, plain).isEmpty())
        assertTrue(carriedSettings(plain.copy(id = "other", name = "Other name"), plain).isEmpty())
    }

    @Test
    fun theOptionsObtainiumLinksCarryNowAreListedToo() {
        val proposed = plain.copy(
            customName = "Signal",
            releases = ReleasePolicy(versionFilter = "^2", matchGroup = "$1.$2", stayBehind = 2, versionFrom = VersionFrom.DATE, order = ReleaseOrder.SOURCE),
            assets = AssetPolicy(archives = true, innerFilter = "arm64"),
            muted = true,
            playInstaller = true,
            refreshFirst = true,
        )
        val carried = carriedSettings(proposed, plain)
        assertEquals(CarriedSetting.CustomName("Signal"), carried.first())
        assertTrue(CarriedSetting.VersionFilter("^2") in carried)
        assertTrue(CarriedSetting.MatchGroup("$1.$2") in carried)
        assertTrue(CarriedSetting.InnerFilter("arm64") in carried)
        assertTrue(CarriedSetting.StayBehind(2) in carried)
        assertTrue(CarriedSetting.ReadVersionFrom(VersionFrom.DATE) in carried)
        assertTrue(CarriedSetting.Order(ReleaseOrder.SOURCE) in carried)
        assertTrue(carried.containsAll(listOf(CarriedSetting.Archives, CarriedSetting.Muted, CarriedSetting.PlayInstaller, CarriedSetting.RefreshFirst)))
    }

    @Test
    fun everyCarriedSettingIsListedOnce() {
        val proposed = plain.copy(
            updates = UpdateMode.MANUAL,
            releases = ReleasePolicy(
                includePrereleases = true, tagFilter = "^v", titleFilter = "stable", notesFilter = "android",
                versionExtract = "v(.+)", minAgeDays = 3, fallbackToOlder = false,
            ),
            assets = AssetPolicy(include = "universal", exclude = "debug", matchDevice = false),
            trackOnly = true,
            pinnedSigners = listOf(pin, pin),
        )
        assertEquals(
            listOf(
                CarriedSetting.Mode(UpdateMode.MANUAL),
                CarriedSetting.Prereleases(true),
                CarriedSetting.MinAge(3),
                CarriedSetting.Include("universal"),
                CarriedSetting.Exclude("debug"),
                CarriedSetting.TagFilter("^v"),
                CarriedSetting.TitleFilter("stable"),
                CarriedSetting.NotesFilter("android"),
                CarriedSetting.VersionPattern("v(.+)"),
                CarriedSetting.MatchDevice(false),
                CarriedSetting.FallBack(false),
                CarriedSetting.TrackOnly(true),
                CarriedSetting.Pin(pin),
            ),
            carriedSettings(proposed, plain),
        )
    }

    @Test
    fun engineDefaultsAreNotReportedAsCarried() {
        val defaults = plain.copy(updates = UpdateMode.AUTO, releases = ReleasePolicy(includePrereleases = true))
        assertTrue(carriedSettings(defaults, defaults.copy(id = "x")).isEmpty())
        assertEquals(listOf(CarriedSetting.Prereleases(false)), carriedSettings(defaults.copy(releases = ReleasePolicy()), defaults))
    }
}
