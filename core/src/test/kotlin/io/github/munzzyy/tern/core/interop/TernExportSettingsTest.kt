package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TernExportSettingsTest {
    private val app = AppConfig("a", SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), "App")

    @Test
    fun settingsGoInOnlyWhenGiven() {
        assertFalse(TernExport.write(listOf(app), 0, "1").contains("\"settings\""))
        assertNull(TernExport.readFile(TernExport.write(listOf(app), 0, "1")).settings)
    }

    @Test
    fun settingsThatWentInComeBack() {
        val settings = Json.obj("theme" to "DARK", "checkEveryHours" to 12)
        val file = TernExport.readFile(TernExport.write(listOf(app), 0, "1", settings))
        assertEquals(listOf(app.source), file.apps.map { it.source })
        assertEquals("DARK", file.settings?.string("theme"))
        assertEquals(12L, file.settings?.long("checkEveryHours"))
    }
}
