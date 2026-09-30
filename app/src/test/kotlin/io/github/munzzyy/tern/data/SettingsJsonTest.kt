package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.interop.ObtainiumSettings
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.Density
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.engine.UpdateAllMode
import io.github.munzzyy.tern.ui.settings.folderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun portableSettingsComeBackAsTheyWent() {
        val chosen = Settings().copy(
            checkEveryMinutes = 720,
            pureBlack = true,
            listSort = AppSort.ADDED,
            listDescending = true,
            listGrouping = AppGrouping.SOURCE,
            buryNotInstalled = true,
            swipeActions = false,
            updateAllMode = UpdateAllMode.ALL,
            confirmUpdateAll = true,
        )
        val text = Json.write(SettingsJson.encode(chosen))
        val back = SettingsJson.apply(Json.parseObject(text), Settings())
        assertEquals(chosen, back)
    }

    @Test
    fun noFileOrLinkTurnsThirdPartyStoresOn() {
        assertFalse(Settings().thirdPartyStores)
        assertFalse("thirdPartyStores" in SettingsJson.encode(Settings().copy(thirdPartyStores = true)).fields.keys)
        val file = Json.parseObject("""{"thirdPartyStores":true,"theme":"DARK"}""")
        assertFalse(SettingsJson.apply(file, Settings()).thirdPartyStores)
        val obtainium = ObtainiumSettings.toTern(Json.parseObject("""{"thirdPartyStores":true,"enableThirdPartyStores":true}"""))
        assertFalse(SettingsJson.apply(obtainium, Settings()).thirdPartyStores)
        assertTrue("one who has them on keeps them on", SettingsJson.apply(file, Settings().copy(thirdPartyStores = true)).thirdPartyStores)
    }

    @Test
    fun whatReachesPastThePhoneNeverTravels() {
        val guarded = Settings().copy(
            proxy = ProxyMode.ORBOT,
            installer = InstallerMode.ROOT,
            otherInstaller = "com.example.installer",
            exportFolder = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
            autoExport = true,
            otherInstallerActivity = "com.example.installer.InstallActivity",
            shareToVerifier = true,
        )
        val keys = SettingsJson.encode(guarded).fields.keys
        for (key in listOf("proxy", "proxyHost", "installer", "otherInstaller", "exportFolder", "autoExport", "openObtainiumLinks", "otherInstallerActivity", "shareToVerifier")) {
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
        val file = Json.parseObject("""{"checkEveryMinutes":-4,"listSort":"SIDEWAYS","pureBlack":"yes","customHue":900}""")
        assertEquals(Settings(), SettingsJson.apply(file, Settings()))
        assertEquals(Settings(), SettingsJson.apply(JsonObject(emptyMap()), Settings()))
    }

    @Test
    fun everySettingObtainiumSharesIsOneTernKeeps() {
        val all = Json.parseObject(
            """{"updateInterval":30,"bgUpdatesOnWiFiOnly":true,"bgUpdatesWhileChargingOnly":true,"checkOnStart":true,
               "checkUpdateOnDetailPage":true,"onlyCheckInstalledOrTrackOnlyApps":true,"removeOnExternalUninstall":true,
               "includePrereleasesByDefault":true,"minimumUpdateAgeDays":3,"theme":1,"useBlackTheme":true,"sortColumn":3,
               "sortOrder":1,"pinUpdates":false,"buryNonInstalled":true,"groupBy":"source","disableSwipeActions":true,
               "alwaysUsePhoneLayout":true,"tactileFeedbackEnabled":false,"collapseGroupsOnStartup":true,"appListDensity":"compact",
               "actionBannerMode":"none","skipBulkUpdateConfirmation":false}""",
        )
        val tern = ObtainiumSettings.toTern(all)
        assertEquals(23, tern.fields.size)
        for (key in tern.fields.keys) assertTrue("$key is not a setting Tern keeps", key in SettingsJson.KEYS)
        val taken = SettingsJson.apply(tern, Settings())
        assertEquals(30, taken.checkEveryMinutes)
        assertEquals(AppSort.RELEASED, taken.listSort)
        assertEquals(AppGrouping.SOURCE, taken.listGrouping)
        assertEquals(Density.COMPACT, taken.density)
        assertFalse(taken.haptics)
        assertFalse(taken.updatesFirst)
        assertEquals(UpdateAllMode.NONE, taken.updateAllMode)
        assertTrue(taken.confirmUpdateAll)
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
