package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonBool
import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ObtainiumSettingsTest {
    private val obtainium = Json.parseObject(
        """
        {"updateInterval":720,"updateIntervalSliderVal":7.0,"checkOnStart":true,"theme":2,"useBlackTheme":true,
         "sortColumn":0,"sortOrder":1,"groupBy":"category","disableSwipeActions":true,"appListDensity":"dense",
         "github-creds":"secret","installMethod":"root","exportDir":"content://x","themeColor":-10209227}
        """,
    )

    @Test
    fun obtainiumsSettingsAreReadUnderTernsNames() {
        val tern = ObtainiumSettings.toTern(obtainium)
        assertEquals(JsonNumber("720"), tern["checkEveryMinutes"])
        assertEquals(JsonBool(true), tern["checkOnStart"])
        assertEquals(JsonString("DARK"), tern["theme"])
        assertEquals(JsonBool(true), tern["pureBlack"])
        assertEquals(JsonString("ADDED"), tern["listSort"])
        assertEquals(JsonBool(true), tern["listDescending"])
        assertEquals(JsonString("CATEGORY"), tern["listGrouping"])
        assertEquals(JsonBool(false), tern["swipeActions"])
        assertEquals(JsonString("MINIMAL"), tern["density"])
    }

    @Test
    fun tokensInstallersAndFoldersNeverComeAcross() {
        val tern = ObtainiumSettings.toTern(obtainium)
        for (key in tern.fields.keys) assertFalse(key, "creds" in key || "install" in key.lowercase() || "export" in key.lowercase())
        assertEquals(9, tern.fields.size)
    }

    @Test
    fun ternsSettingsGoBackUnderObtainiumsNames() {
        val back = ObtainiumSettings.toObtainium(ObtainiumSettings.toTern(obtainium))
        assertEquals(JsonNumber("720"), back["updateInterval"])
        assertEquals(JsonNumber("7.0"), back["updateIntervalSliderVal"])
        assertEquals(JsonNumber("2"), back["theme"])
        assertEquals(JsonNumber("0"), back["sortColumn"])
        assertEquals(JsonNumber("1"), back["sortOrder"])
        assertEquals(JsonString("category"), back["groupBy"])
        assertEquals(JsonBool(true), back["disableSwipeActions"])
        assertEquals(JsonString("dense"), back["appListDensity"])
        assertNull(back["github-creds"])
    }

    @Test
    fun categoryColoursComeOutOfTheirStringAndGoBackIntoOne() {
        val tern = ObtainiumSettings.toTern(Json.parseObject("""{"categories":"{\"Work\":4294198070}"}"""))
        assertEquals(Json.parseObject("""{"Work":4294198070}"""), tern["categoryColors"])
        val back = ObtainiumSettings.toObtainium(Json.parseObject("""{"categoryColors":{"Work":-769226}}"""))
        assertEquals(JsonString("{\"Work\":4294198070}"), back["categories"])
    }

    @Test
    fun whatObtainiumHasNoWordForStaysBehind() {
        val tern = Json.parseObject("""{"listSort":"SOURCE","checkEveryMinutes":45,"theme":"PURPLE"}""")
        val out = ObtainiumSettings.toObtainium(tern)
        assertNull(out["sortColumn"])
        assertNull(out["theme"])
        assertEquals(JsonNumber("45"), out["updateInterval"])
        // 45 minutes is between the slider's steps, so the slider is left where it was.
        assertNull(out["updateIntervalSliderVal"])
    }

    @Test
    fun anExportOfObtainiumsCarriesItsSettingsIntoTheImport() {
        val text = """{"schemaVersion":2,"apps":[],"settings":{"checkOnStart":true,"pinUpdates":false}}"""
        val settings = ObtainiumImport.read(text).settings!!
        assertEquals(JsonBool(true), settings["checkOnStart"])
        assertEquals(JsonBool(false), settings["updatesFirst"])
        assertNull(ObtainiumImport.read("""{"apps":[]}""").settings)
    }

    @Test
    fun theBannerButtonIsTernsUpdateAllAndSkippingTheQuestionIsNotAskingIt() {
        val tern = ObtainiumSettings.toTern(Json.parseObject("""{"actionBannerMode":"all","skipBulkUpdateConfirmation":false}"""))
        assertEquals(JsonString("ALL"), tern["updateAllMode"])
        assertEquals(JsonBool(true), tern["confirmUpdateAll"])
        assertEquals(JsonString("NONE"), ObtainiumSettings.toTern(Json.parseObject("""{"actionBannerMode":"none"}"""))["updateAllMode"])
        assertNull(ObtainiumSettings.toTern(Json.parseObject("""{"actionBannerMode":"sometimes"}"""))["updateAllMode"])
        val back = ObtainiumSettings.toObtainium(Json.parseObject("""{"updateAllMode":"UPDATES","confirmUpdateAll":false}"""))
        assertEquals(JsonString("updatesOnly"), back["actionBannerMode"])
        assertEquals(JsonBool(true), back["skipBulkUpdateConfirmation"])
    }
}
