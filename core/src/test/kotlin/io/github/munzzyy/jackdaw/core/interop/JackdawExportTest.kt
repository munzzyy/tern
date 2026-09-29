package io.github.munzzyy.jackdaw.core.interop

import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JackdawExportTest {
    private fun apps() = listOf(
        AppConfig(id = "one", source = SourceSpec("github", "https://github.com/example/one"), name = "One"),
        AppConfig(id = "two", source = SourceSpec("fdroid", "https://f-droid.org/packages/two"), name = "Two", favorite = true),
    )

    @Test
    fun roundTripsAppsList() {
        val text = JackdawExport.write(apps(), exportedAtMs = 1700000000000, appVersion = "1.0.0")
        val decoded = JackdawExport.read(text)
        assertEquals(apps(), decoded)
    }

    @Test
    fun writesExpectedTopLevelShape() {
        val text = JackdawExport.write(apps(), exportedAtMs = 1700000000000, appVersion = "1.0.0")
        assertTrue(text.contains("\"format\""))
        assertTrue(text.contains("jackdaw-export"))
        assertTrue(text.contains("\"schema\""))
    }

    @Test
    fun neverIncludesTokenLikeFields() {
        val text = JackdawExport.write(apps(), exportedAtMs = 1, appVersion = "1")
        assertTrue(!text.contains("token", ignoreCase = true))
        assertTrue(!text.contains("secret", ignoreCase = true))
    }

    @Test(expected = JackdawExportException::class)
    fun rejectsWrongFormatTag() {
        JackdawExport.read("""{"format":"something-else","schema":1,"apps":[]}""")
    }

    @Test(expected = JackdawExportException::class)
    fun rejectsUnsupportedSchema() {
        JackdawExport.read("""{"format":"jackdaw-export","schema":99,"apps":[]}""")
    }

    @Test(expected = JackdawExportException::class)
    fun rejectsMalformedJson() {
        JackdawExport.read("not json at all")
    }

    @Test(expected = JackdawExportException::class)
    fun rejectsMissingAppsArray() {
        JackdawExport.read("""{"format":"jackdaw-export","schema":1}""")
    }
}
