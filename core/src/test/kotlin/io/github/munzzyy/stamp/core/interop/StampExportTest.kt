package io.github.munzzyy.stamp.core.interop

import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.SourceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StampExportTest {
    private fun apps() = listOf(
        AppConfig(id = "one", source = SourceSpec("github", "https://github.com/example/one"), name = "One"),
        AppConfig(id = "two", source = SourceSpec("fdroid", "https://f-droid.org/packages/two"), name = "Two", favorite = true),
    )

    @Test
    fun roundTripsAppsList() {
        val text = StampExport.write(apps(), exportedAtMs = 1700000000000, appVersion = "1.0.0")
        val decoded = StampExport.read(text)
        assertEquals(apps(), decoded)
    }

    @Test
    fun writesExpectedTopLevelShape() {
        val text = StampExport.write(apps(), exportedAtMs = 1700000000000, appVersion = "1.0.0")
        assertTrue(text.contains("\"format\""))
        assertTrue(text.contains("stamp-export"))
        assertTrue(text.contains("\"schema\""))
    }

    @Test
    fun neverIncludesTokenLikeFields() {
        val text = StampExport.write(apps(), exportedAtMs = 1, appVersion = "1")
        assertTrue(!text.contains("token", ignoreCase = true))
        assertTrue(!text.contains("secret", ignoreCase = true))
    }

    @Test(expected = StampExportException::class)
    fun rejectsWrongFormatTag() {
        StampExport.read("""{"format":"something-else","schema":1,"apps":[]}""")
    }

    @Test(expected = StampExportException::class)
    fun rejectsUnsupportedSchema() {
        StampExport.read("""{"format":"stamp-export","schema":99,"apps":[]}""")
    }

    @Test(expected = StampExportException::class)
    fun rejectsMalformedJson() {
        StampExport.read("not json at all")
    }

    @Test(expected = StampExportException::class)
    fun rejectsMissingAppsArray() {
        StampExport.read("""{"format":"stamp-export","schema":1}""")
    }
}
