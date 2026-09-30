package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.RequestHeaders
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aStoreAddressRoutedAfterAHashKeepsItsApp() {
        fun one(url: String, override: String?): String? {
            val source = if (override == null) "null" else "\"$override\""
            val text = """{"apps":[{"id":"x.y","url":"$url","author":"","name":"Hashed","overrideSource":$source,"additionalSettings":"{}"}]}"""
            return ObtainiumImport.read(text).apps.singleOrNull()?.source?.url
        }
        assertEquals("https://appgallery.huawei.com/app/C100000000", one("https://appgallery.huawei.com/#/app/C100000000", "HuaweiAppGallery"))
        assertEquals("https://appgallery.huawei.com/app/C100000000", one("https://appgallery.huawei.com/#/app/C100000000", null))
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
        // Exempt from background updates still checks and notifies; it only does not install by itself.
        assertEquals(io.github.munzzyy.tern.core.model.UpdateMode.NOTIFY, sixteen.updates)
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

    private fun options() = ObtainiumImport.read(Fixtures.text("interop/obtainium-options.json")).apps.associateBy { it.name }

    @Test
    fun mapsHowAReleaseIsChosen() {
        val all = options()
        val everything = all.getValue("Everything").releases
        assertEquals("$1.$2", everything.matchGroup)
        assertEquals(VersionFrom.TITLE, everything.versionFrom)
        assertEquals(ReleaseOrder.DATE, everything.order)
        assertEquals(1, everything.stayBehind)
        assertEquals("^2\\.", everything.versionFilter)
        assertEquals(ReleasePolicy(), all.getValue("Nothing").releases)

        val dated = all.getValue("Dated").releases
        assertEquals(VersionFrom.DATE, dated.versionFrom)
        assertEquals(ReleaseOrder.SOURCE, dated.order)
        // Obtainium takes the whole match; for a pattern without groups that is what Tern takes too, so nothing is named.
        assertEquals(null, dated.matchGroup)

        assertEquals(ReleaseOrder.NAME, all.getValue("Named").releases.order)
        assertEquals(ReleaseOrder.VERSION, all.getValue("Smart").releases.order)
        assertEquals(ReleaseOrder.VERSION, all.getValue("Smart Date").releases.order)
        val legacy = all.getValue("Legacy Date").releases
        assertEquals(VersionFrom.DATE, legacy.versionFrom)
        assertEquals(ReleaseOrder.SOURCE, legacy.order)
    }

    @Test
    fun theWholeMatchIsNamedWhereTernWouldTakeAGroup() {
        fun group(pattern: String, named: String): String? {
            val settings = Json.write(Json.obj("versionExtractionRegEx" to pattern, "matchGroupToUse" to named))
            val text = "[" + Json.write(Json.obj("id" to "a.b", "url" to "https://github.com/a/b", "name" to "B", "additionalSettings" to settings)) + "]"
            return ObtainiumImport.read(text).apps.single().releases.matchGroup
        }
        assertEquals("0", group("v(\\d+)", ""))
        assertEquals(null, group("v(\\d+)", "1"))
        assertEquals("2", group("(\\d+)-(\\d+)", "2"))
    }

    @Test
    fun mapsHowAFileIsChosen() {
        val all = options()
        assertEquals(AssetPolicy(archives = true, innerFilter = "arm64"), all.getValue("Everything").assets)
        assertEquals(AssetPolicy(archives = true, innerFilter = "\\.apk$"), all.getValue("Dated").assets)
        assertEquals(AssetPolicy(), all.getValue("Nothing").assets)
        assertEquals(AssetPolicy(innerFilter = "universal"), all.getValue("Page").assets)
    }

    @Test
    fun mapsWhatThePersonChoseForTheApp() {
        val everything = options().getValue("Everything")
        assertEquals("Everything", everything.name)
        assertEquals("Chosen Name", everything.customName)
        assertEquals("Chosen Author", everything.customAuthor)
        assertEquals("Chosen Name", everything.shownName)
        assertTrue(everything.muted)
        assertTrue(everything.refreshFirst)
        assertTrue(everything.playInstaller)
        val nothing = options().getValue("Nothing")
        assertNull(nothing.customName)
        assertNull(nothing.customAuthor)
        assertFalse(nothing.muted || nothing.refreshFirst || nothing.playInstaller)
    }

    @Test
    fun mapsTheForgeOptionsForTheForgesThatReadThem() {
        val all = options()
        val both = mapOf(SourceOptions.VERIFY_LATEST to "true", SourceOptions.ASSET_DATE to "true")
        assertEquals(both, all.getValue("Everything").source.options)
        assertEquals(SourceTypes.FORGEJO, all.getValue("Named").source.type)
        assertEquals(both, all.getValue("Named").source.options)
        assertTrue(all.getValue("Nothing").source.options.isEmpty())
        assertTrue(all.getValue("Lab").source.options.isEmpty())
    }

    @Test
    fun mapsTheOptionsOfAnHtmlPage() {
        val page = options().getValue("Page").source
        assertEquals(SourceTypes.HTML, page.type)
        assertEquals("true", page.option(SourceOptions.FIRST_LINK))
        assertEquals("true", page.option(SourceOptions.LAST_SEGMENT))
        assertEquals("true", page.option(SourceOptions.ANY_TEXT))
        assertEquals("link", page.option(SourceOptions.PSEUDO))
        assertEquals(mapOf("X-Mirror" to "eu:west", "user-agent" to "Example/2.0"), RequestHeaders.parse(page.option(SourceOptions.HEADERS)))
        assertEquals(
            Json.parseArray("""[{"filter": "Releases", "text": true}, "/latest/", {"filter": "/builds/", "arch": true}]"""),
            Json.parseArray(page.option(SourceOptions.STEPS)!!),
        )

        val plain = options().getValue("Plain Page").source
        assertEquals("[\"download\"]", plain.option(SourceOptions.STEPS))
        for (key in listOf(SourceOptions.FIRST_LINK, SourceOptions.LAST_SEGMENT, SourceOptions.ANY_TEXT, SourceOptions.HEADERS, SourceOptions.PSEUDO)) {
            assertNull(key, plain.option(key))
        }

        val legacy = options().getValue("Legacy Page").source
        assertEquals(Json.parseArray("""[{"filter": "Download", "text": true}]"""), Json.parseArray(legacy.option(SourceOptions.STEPS)!!))
        assertEquals("true", legacy.option(SourceOptions.LAST_SEGMENT))
        assertEquals("link", legacy.option(SourceOptions.PSEUDO))
    }

    @Test
    fun mapsTheOptionsOfADirectLinkWhetherNamedOrFoundByItsAddress() {
        val direct = options().getValue("Direct").source
        assertEquals(SourceTypes.DIRECT, direct.type)
        assertEquals("etag", direct.option(SourceOptions.PSEUDO))
        assertTrue(RequestHeaders.parse(direct.option(SourceOptions.HEADERS)).getValue("User-Agent").startsWith("Mozilla/5.0 (Linux; Android 10; K)"))
        val byAddress = options().getValue("Direct By Address").source
        assertEquals(SourceTypes.DIRECT, byAddress.type)
        assertEquals("hash", byAddress.option(SourceOptions.PSEUDO))
        assertEquals(mapOf("Referer" to "https://example.com/files/"), RequestHeaders.parse(byAddress.option(SourceOptions.HEADERS)))
    }

    @Test
    fun everythingImportedSurvivesBeingStored() {
        for (app in options().values) {
            assertEquals(app.name, app, AppConfigJson.decode(AppConfigJson.encode(app)))
        }
    }
}
