package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.Refusal
import org.junit.Assert.assertEquals
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
