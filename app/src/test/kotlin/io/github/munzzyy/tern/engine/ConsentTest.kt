package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.interop.ObtainiumImport
import io.github.munzzyy.tern.core.interop.TernExport
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.engine.real.Arrivals
import io.github.munzzyy.tern.engine.real.BuiltInPins
import io.github.munzzyy.tern.engine.real.Evaluation
import io.github.munzzyy.tern.engine.real.Installs
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing installs by itself unless the user of this device switched it on, for that very app. */
class ConsentTest {
    private val source = SourceSpec("github", "https://github.com/example/app")
    private val plain = AppConfig(id = "a", source = source, name = "App", packageName = "org.example.app")
    private val sure = Evaluation(AppStatus.UPDATE_AVAILABLE)

    private fun byItself(config: AppConfig, mayInstall: Boolean = true, evaluation: Evaluation? = sure, busy: Boolean = false) =
        Installs.installsByItself(config, mayInstall, evaluation, busy)

    @Test
    fun aNewInstallOfTernInstallsNothingByItself() {
        val settings = Settings()
        assertEquals(UpdateMode.NOTIFY, settings.defaultUpdateMode)
        assertFalse(settings.claimUpdateOwnership)
        assertFalse(settings.openObtainiumLinks)
        assertEquals(UpdateMode.NOTIFY, plain.updates)
        assertFalse(plain.trackOnly)
        assertFalse(byItself(plain.copy(updates = settings.defaultUpdateMode)))
    }

    @Test
    fun obtainiumLinksAreOffInTheManifestUntilTheUserSwitchesThemOn() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val manifest = File(checkNotNull(dir) { "not inside the project" }, "app/src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val aliases = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("activity-alias")
        val android = "http://schemas.android.com/apk/res/android"
        val found = (0 until aliases.length).map { aliases.item(it) as org.w3c.dom.Element }.filter { alias ->
            val data = alias.getElementsByTagName("data")
            (0 until data.length).any { (data.item(it) as org.w3c.dom.Element).getAttributeNS(android, "scheme") == "obtainium" }
        }
        assertEquals(1, found.size)
        assertEquals("false", found.single().getAttributeNS(android, "enabled"))

        val activities = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val data = (activities.item(i) as org.w3c.dom.Element).getElementsByTagName("data")
            for (j in 0 until data.length) assertFalse((data.item(j) as org.w3c.dom.Element).getAttributeNS(android, "scheme") == "obtainium")
        }
    }

    @Test
    fun theBackgroundCheckInstallsOnlyWhatTheUserSetToIt() {
        assertTrue(byItself(plain.copy(updates = UpdateMode.AUTO)))
        assertFalse(byItself(plain.copy(updates = UpdateMode.NOTIFY)))
        assertFalse(byItself(plain.copy(updates = UpdateMode.MANUAL)))
        for (mode in UpdateMode.entries) assertEquals(mode.name, mode == UpdateMode.AUTO, byItself(plain.copy(updates = mode)))
    }

    @Test
    fun evenThenOnlyWhenNothingSpeaksAgainstIt() {
        val auto = plain.copy(updates = UpdateMode.AUTO)
        assertFalse(byItself(auto, mayInstall = false))
        assertFalse(byItself(auto.copy(trackOnly = true)))
        assertFalse(byItself(auto, busy = true))
        assertFalse(byItself(auto, evaluation = null))
        assertFalse(byItself(auto, evaluation = Evaluation(AppStatus.UPDATE_AVAILABLE, certain = false)))
        assertFalse(byItself(auto, evaluation = Evaluation(AppStatus.UPDATE_AVAILABLE, problem = Problem(ProblemKind.NETWORK, "down"))))
        for (status in AppStatus.entries) assertEquals(status.name, status == AppStatus.UPDATE_AVAILABLE, byItself(auto, evaluation = Evaluation(status)))
    }

    @Test
    fun anAppTheUserSetToBeLeftAloneIsNotEvenChecked() {
        assertTrue(Installs.checkedInTheBackground(plain.copy(updates = UpdateMode.NOTIFY)))
        assertTrue(Installs.checkedInTheBackground(plain.copy(updates = UpdateMode.AUTO)))
        assertFalse(Installs.checkedInTheBackground(plain.copy(updates = UpdateMode.MANUAL)))
    }

    @Test
    fun anAppThatArrivesInAFileIsNeverSetToInstallByItself() {
        val pins = BuiltInPins(emptyList())
        val arrived = TernExport.read(TernExport.write(listOf(plain.copy(updates = UpdateMode.AUTO)), 1L, "0.1.0")).single()
        assertEquals("the file does ask for it", UpdateMode.AUTO, arrived.updates)
        val stored = Arrivals.stored(arrived, "b", pins)
        assertEquals(UpdateMode.NOTIFY, stored.updates)
        assertEquals("b", stored.id)
        assertFalse(byItself(stored))

        assertEquals(UpdateMode.MANUAL, Arrivals.stored(plain.copy(updates = UpdateMode.MANUAL), "b", pins).updates)
        assertEquals(UpdateMode.NOTIFY, Arrivals.stored(plain.copy(updates = UpdateMode.NOTIFY), "b", pins).updates)
        assertEquals(plain.copy(id = "b"), Arrivals.stored(plain, "b", pins))
    }

    @Test
    fun noExportOfObtainiumsAsksForIt() {
        val settings = listOf(
            Json.obj(),
            Json.obj("exemptFromBackgroundUpdates" to true),
            Json.obj("exemptFromBackgroundUpdates" to false),
            Json.obj("updates" to "AUTO", "autoUpdate" to true, "useShizuku" to true),
        )
        val entries = settings.mapIndexed { i, s ->
            Json.obj("id" to "org.example.app$i", "url" to "https://github.com/example/app$i", "name" to "App $i", "additionalSettings" to Json.write(s), "updates" to "AUTO")
        }
        val apps = ObtainiumImport.read(Json.write(Json.obj("apps" to entries))).apps
        assertEquals(settings.size, apps.size)
        assertTrue(apps.none { it.updates == UpdateMode.AUTO })
    }
}
