package io.github.munzzyy.stamp.core.interop

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.AssetPolicy
import io.github.munzzyy.stamp.core.model.ReleasePolicy
import io.github.munzzyy.stamp.core.model.SourceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownConfigTest {
    private val turn = "‮"
    private val hidden = "​"

    private val hostile = AppConfig(
        id = "abc",
        source = SourceSpec("github", "https://github.com/example/app"),
        name = "Bank${turn}knaB$hidden  of\nNames",
        author = "⁦Some  Body⁩",
        packageName = "org.example.app",
        releases = ReleasePolicy(tagFilter = "^v\\d+$turn"),
        assets = AssetPolicy(include = "arm64$hidden"),
        pinnedSigners = listOf("a".repeat(64)),
        categories = listOf("To${turn}ols", hidden, " Media "),
        notes = "First$turn line\nsecond\u0007 line",
    )

    @Test
    fun anExportOfStampsIsReadWithItsTextsFitToBeShown() {
        val read = StampExport.read(StampExport.write(listOf(hostile), 1L, "0.1.0")).single()
        assertEquals("BankknaB of Names", read.name)
        assertEquals("Some Body", read.author)
        assertEquals(listOf("Tools", "Media"), read.categories)
        assertEquals("First line\nsecond line", read.notes)
    }

    @Test
    fun whatIsNotReadByAPersonStaysAsItCame() {
        val read = StampExport.read(StampExport.write(listOf(hostile), 1L, "0.1.0")).single()
        assertEquals(hostile.id, read.id)
        assertEquals(hostile.source, read.source)
        assertEquals(hostile.packageName, read.packageName)
        assertEquals(hostile.releases, read.releases)
        assertEquals(hostile.assets, read.assets)
        assertEquals(hostile.pinnedSigners, read.pinnedSigners)
    }

    @Test
    fun anOrdinaryExportComesBackAsItWasWritten() {
        val ordinary = hostile.copy(name = "Example App", author = "example", categories = listOf("Tools"), notes = "Line one\n\tline two")
        assertEquals(ordinary, StampExport.read(StampExport.write(listOf(ordinary), 1L, "0.1.0")).single())
    }

    @Test
    fun aNameWithNothingToShowFallsBackToTheAddress() {
        val read = StampExport.read(StampExport.write(listOf(hostile.copy(name = "$turn$hidden", author = hidden)), 1L, "0.1.0")).single()
        assertEquals("https://github.com/example/app", read.name)
        assertNull(read.author)
    }

    @Test
    fun anExportOfObtainiumsIsReadWithItsTextsFitToBeShown() {
        val entry = Json.obj(
            "id" to "org.example.app",
            "url" to "https://github.com/example/app",
            "name" to "Bank${turn}knaB$hidden  of\nNames",
            "author" to "⁦Some  Body⁩",
            "categories" to listOf("To${turn}ols"),
            "additionalSettings" to Json.write(Json.obj("about" to "First$turn line\nsecond\u0007 line")),
        )
        val skippedEntry = Json.obj("url" to "https://apkpure.com/x$turn", "name" to "Sto${turn}re app", "overrideSource" to "APKPure")
        val result = ObtainiumImport.read(Json.write(Json.obj("apps" to listOf(entry, skippedEntry))))
        val app = result.apps.single()
        assertEquals("BankknaB of Names", app.name)
        assertEquals("Some Body", app.author)
        assertEquals(listOf("Tools"), app.categories)
        assertEquals("First line\nsecond line", app.notes)
        val skipped = result.skipped.single()
        assertEquals("Store app", skipped.name)
        assertEquals("https://apkpure.com/x", skipped.url)
    }
}
