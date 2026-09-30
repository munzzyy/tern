package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.settings.folderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun portableSettingsComeBackAsTheyWent() {
        val chosen = Settings().copy(
            checkEveryHours = 12,
            pureBlack = true,
            listSort = AppSort.ADDED,
            listDescending = true,
            listGrouping = AppGrouping.SOURCE,
            buryNotInstalled = true,
            swipeActions = false,
        )
        val text = Json.write(SettingsJson.encode(chosen))
        val back = SettingsJson.apply(Json.parseObject(text), Settings())
        assertEquals(chosen, back)
    }

    @Test
    fun whatReachesPastThePhoneNeverTravels() {
        val guarded = Settings().copy(
            proxy = ProxyMode.ORBOT,
            installer = InstallerMode.ROOT,
            otherInstaller = "com.example.installer",
            exportFolder = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
            autoExport = true,
        )
        val keys = SettingsJson.encode(guarded).fields.keys
        for (key in listOf("proxy", "proxyHost", "installer", "otherInstaller", "exportFolder", "autoExport", "openObtainiumLinks")) {
            assertFalse("$key must not travel", key in keys)
        }
        val file = Json.parseObject("""{"installer":"ROOT","proxy":"ORBOT","exportFolder":"content://x/tree/y","theme":"DARK"}""")
        val taken = SettingsJson.apply(file, Settings())
        assertEquals(Settings().installer, taken.installer)
        assertEquals(Settings().proxy, taken.proxy)
        assertNull(taken.exportFolder)
    }

    @Test
    fun badValuesLeaveTheSettingAsItWas() {
        val file = Json.parseObject("""{"checkEveryHours":-4,"listSort":"SIDEWAYS","pureBlack":"yes","customHue":900}""")
        assertEquals(Settings(), SettingsJson.apply(file, Settings()))
        assertEquals(Settings(), SettingsJson.apply(JsonObject(emptyMap()), Settings()))
    }

    @Test
    fun aPickedFolderIsNamedAsThePersonKnowsIt() {
        assertEquals("Documents/Backups", folderName("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBackups"))
        assertEquals("primary", folderName("content://com.android.externalstorage.documents/tree/primary%3A"))
        assertEquals("A+B", folderName("content://com.android.externalstorage.documents/tree/primary%3AA%2BB"))
        assertNull(folderName(null))
        assertNull(folderName("content://nothing-here"))
    }
}
