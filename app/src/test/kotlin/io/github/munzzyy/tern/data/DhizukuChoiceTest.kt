package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.interop.ObtainiumSettings
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.engine.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dhizuku as the installer: where Settings offers it, and that it stays on the device as the choice of Shizuku does. */
class DhizukuChoiceTest {
    @Test
    fun dhizukuIsOfferedBetweenShizukuAndRootAndKeptByItsName() {
        assertEquals(
            listOf(InstallerMode.SYSTEM, InstallerMode.SHIZUKU, InstallerMode.DHIZUKU, InstallerMode.ROOT, InstallerMode.OTHER_APP),
            InstallerMode.entries,
        )
        assertEquals(InstallerMode.DHIZUKU, InstallerMode.valueOf("DHIZUKU"))
        assertEquals(InstallerMode.SYSTEM, Settings().installer)
    }

    @Test
    fun anExportCarriesTheChoiceOfDhizukuAsItCarriesShizukusThatIsNotAtAll() {
        for (mode in listOf(InstallerMode.SHIZUKU, InstallerMode.DHIZUKU)) {
            val written = SettingsJson.encode(Settings(installer = mode))
            assertFalse("installer" in written.fields.keys)
            assertEquals(SettingsJson.encode(Settings()), written)
        }
    }

    @Test
    fun anImportCannotChooseDhizukuNorTakeItAway() {
        val file = Json.parseObject("""{"installer":"DHIZUKU","theme":"DARK"}""")
        val taken = SettingsJson.apply(file, Settings())
        assertEquals(InstallerMode.SYSTEM, taken.installer)
        assertEquals(ThemeMode.DARK, taken.theme)
        val kept = SettingsJson.apply(Json.parseObject("""{"installer":"SYSTEM"}"""), Settings(installer = InstallerMode.DHIZUKU))
        assertEquals(InstallerMode.DHIZUKU, kept.installer)
        // Obtainium keeps its installer, Dhizuku among them, under installMethod and useShizuku; none of it comes across.
        assertTrue(ObtainiumSettings.toTern(Json.parseObject("""{"installMethod":"shizuku","useShizuku":true}""")).fields.isEmpty())
    }
}
