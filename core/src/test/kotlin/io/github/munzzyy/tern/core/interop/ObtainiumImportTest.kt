package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObtainiumImportTest {
    private fun result() = ObtainiumImport.read(Fixtures.text("interop/obtainium-export.json"))

    @Test
    fun mapsAllSixteenSupportedSourceTypes() {
        val result = result()
        val byId = result.apps.associateBy { it.id }

        assertEquals(SourceTypes.GITHUB, byId.getValue("dev.example.one").source.type)
        assertEquals(SourceTypes.GITLAB, byId.getValue("dev.example.two").source.type)
        assertEquals(SourceTypes.FORGEJO, byId.getValue("dev.example.three").source.type)
        assertEquals(SourceTypes.FDROID, byId.getValue("dev.example.four").source.type)
        assertEquals(SourceTypes.FDROID, byId.getValue("dev.example.five").source.type)
        assertEquals(SourceTypes.FDROID_REPO, byId.getValue("dev.example.six").source.type)
        assertEquals("dev.example.six", byId.getValue("dev.example.six").source.option(SourceOptions.PACKAGE))
    }

    @Test
    fun mapsHtmlDirectJenkinsSourceHutSourceForge() {
        val byId = result().apps.associateBy { it.name }
        assertEquals(SourceTypes.HTML, byId.getValue("Seven").source.type)
        assertEquals("\\.apk$", byId.getValue("Seven").source.option(SourceOptions.LINK_FILTER))
        assertEquals(SourceTypes.DIRECT, byId.getValue("Eight").source.type)
        assertEquals(SourceTypes.JENKINS, byId.getValue("Nine").source.type)
        assertEquals(SourceTypes.SOURCEHUT, byId.getValue("Ten").source.type)
        assertEquals(SourceTypes.SOURCEFORGE, byId.getValue("Eleven").source.type)
    }

    @Test
    fun inferSourceFromUrlWhenOverrideSourceIsNull() {
        val twelve = result().apps.first { it.name == "Twelve" }
        assertEquals(SourceTypes.DIRECT, twelve.source.type)
    }

    @Test
    fun skipsUnsupportedSourcesWithReasons() {
        val skipped = result().skipped
        assertEquals(2, skipped.size)
        assertTrue(skipped.any { it.name == "Thirteen" && it.reason.contains("APKPure") })
        assertTrue(skipped.any { it.name == "Fourteen" && it.reason.contains("Uptodown") })
        assertEquals(SourceTypes.TELEGRAM, result().apps.first { it.name == "Fifteen" }.source.type)
    }

    @Test
    fun onlyValidPackageLikeIdsBecomePackageName() {
        val byName = result().apps.associateBy { it.name }
        assertEquals("dev.example.one", byName.getValue("One").packageName)
        assertNull(byName.getValue("Eight").packageName)
    }

    @Test
    fun mapsCategoriesAndFavorite() {
        val one = result().apps.first { it.name == "One" }
        assertEquals(listOf("tools"), one.categories)
        assertTrue(one.favorite)
    }

    @Test
    fun acceptsLegacyStringTypedSettings() {
        val three = result().apps.first { it.name == "Three" }
        assertTrue(three.trackOnly)
        assertEquals(3, three.releases.minAgeDays)
    }

    @Test
    fun mapsPinnedSignersNotesAndUpdateMode() {
        val sixteen = result().apps.first { it.name == "Sixteen" }
        assertEquals(listOf("aa".repeat(32), "bb".repeat(32)), sixteen.pinnedSigners)
        assertEquals("Legacy notes", sixteen.notes)
        assertEquals(io.github.munzzyy.tern.core.model.UpdateMode.MANUAL, sixteen.updates)
        assertEquals("arm64", sixteen.assets.include)
    }

    @Test
    fun acceptsBareArrayExport() {
        val bare = """[{"id":"a","url":"https://example.com/a.apk","name":"A","additionalSettings":"{}"}]"""
        val result = ObtainiumImport.read(bare)
        assertEquals(1, result.apps.size)
    }

    @Test(expected = ObtainiumImportException::class)
    fun rejectsInvalidJson() {
        ObtainiumImport.read("not json")
    }
}
