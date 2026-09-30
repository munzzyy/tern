package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.Refusal
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.RequestHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TernExportTest {
    private fun apps() = listOf(
        AppConfig(id = "one", source = SourceSpec("github", "https://github.com/example/one"), name = "One"),
        AppConfig(id = "two", source = SourceSpec("fdroid", "https://f-droid.org/packages/two"), name = "Two", favorite = true),
    )

    @Test
    fun roundTripsAppsList() {
        val text = TernExport.write(apps(), exportedAtMs = 1700000000000, appVersion = "1.0.0")
        val decoded = TernExport.read(text)
        assertEquals(apps(), decoded)
    }

    @Test
    fun writesExpectedTopLevelShape() {
        val text = TernExport.write(apps(), exportedAtMs = 1700000000000, appVersion = "1.0.0")
        assertTrue(text.contains("\"format\""))
        assertTrue(text.contains("tern-export"))
        assertTrue(text.contains("\"schema\""))
    }

    @Test
    fun neverIncludesTokenLikeFields() {
        val text = TernExport.write(apps(), exportedAtMs = 1, appVersion = "1")
        assertTrue(!text.contains("token", ignoreCase = true))
        assertTrue(!text.contains("secret", ignoreCase = true))
    }

    @Test
    fun aHeaderThatCouldHoldAKeyNeverLeavesInAnExport() {
        val headers = RequestHeaders.write(mapOf("User-Agent" to "Example/1.0", "X-Api-Key" to "k123", "X-Mirror" to "eu"))
        val app = AppConfig("web", SourceSpec(SourceTypes.DIRECT, "https://example.org/app.apk", mapOf(SourceOptions.HEADERS to headers)), "Web")
        val text = TernExport.write(listOf(app), exportedAtMs = 1, appVersion = "1")
        assertFalse(text, "k123" in text || "X-Api-Key" in text || "X-Mirror" in text)
        assertEquals(mapOf("User-Agent" to "Example/1.0"), RequestHeaders.parse(TernExport.read(text).single().source.option(SourceOptions.HEADERS)))
        val onlySecret = app.copy(source = app.source.copy(options = mapOf(SourceOptions.HEADERS to RequestHeaders.write(mapOf("X-Api-Key" to "k123")))))
        assertNull(TernExport.read(TernExport.write(listOf(onlySecret), 1, "1")).single().source.option(SourceOptions.HEADERS))
    }

    @Test(expected = TernExportException::class)
    fun rejectsWrongFormatTag() {
        TernExport.read("""{"format":"something-else","schema":1,"apps":[]}""")
    }

    @Test(expected = TernExportException::class)
    fun rejectsUnsupportedSchema() {
        TernExport.read("""{"format":"tern-export","schema":99,"apps":[]}""")
    }

    @Test(expected = TernExportException::class)
    fun rejectsMalformedJson() {
        TernExport.read("not json at all")
    }

    @Test(expected = TernExportException::class)
    fun rejectsMissingAppsArray() {
        TernExport.read("""{"format":"tern-export","schema":1}""")
    }

    @Test
    fun anAppOfASiteTernNoLongerReadsIsSkippedAndTheRestIsRead() {
        val kept = AppConfigJson.encode(apps()[0])
        val text = """{"format":"tern-export","schema":1,"apps":[
            {"id":"ru","name":"Store App","source":{"type":"rustore","url":"https://www.rustore.ru/catalog/app/org.example"}},
            {"id":"mod","name":"Page","customName":"Modded","source":{"type":"html","url":"https://liteapks.com/example.html"}},
            ${Json.write(kept)}]}"""
        val file = TernExport.readFile(text)
        assertEquals(listOf(apps()[0]), file.apps)
        assertEquals(listOf("Store App" to Refusal.IMPERSONATION, "Modded" to Refusal.MODIFIED_APPS), file.skipped.map { it.name to it.refusal })
    }
}
