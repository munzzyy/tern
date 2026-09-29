package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
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
