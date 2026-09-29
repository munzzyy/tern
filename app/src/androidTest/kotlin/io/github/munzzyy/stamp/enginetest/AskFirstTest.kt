package io.github.munzzyy.stamp.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.core.interop.StampExport
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.model.UpdateMode
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.EventKind
import io.github.munzzyy.stamp.engine.Received
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The background check installs nothing for an app the user did not set to it. */
@RunWith(AndroidJUnit4::class)
class AskFirstTest {
    private val v1 = FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk"))))
    private val v2 = FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", asset("apk/app-v2.apk"))))

    @Before
    fun setUp() {
        prepareDevice()
        uninstallFixture()
        assertTrue(shellInstall(asset("apk/app-v1.apk")).contains("Success"))
    }

    @After
    fun tearDown() {
        Prompt.dismiss()
        uninstallFixture()
    }

    private fun assertNothingWasInstalled(h: Harness, id: String) {
        assertEquals(h.describe(id), 1L, installedVersionCode())
        assertEquals(h.describe(id), 0, h.installer.prepared.get())
        assertEquals(h.describe(id), 0, h.installer.committed.get())
        assertNull(h.describe(id), h.state(id).pending)
        assertNull(h.describe(id), h.state(id).record)
        assertNull(h.describe(id), h.row(id).progress)
        assertTrue(h.describe(id), h.eventsFor(id).none { it.kind == EventKind.DOWNLOADED || it.kind == EventKind.VERIFIED || it.kind == EventKind.INSTALLED })
        assertNull("a file was downloaded", h.engine.downloader.kept(id, "${FakeForge.FILES}v2.0/app-v2.apk"))
        assertFalse("the system installer asked the user", Prompt.visible())
    }

    @Test
    fun anAppSetToTellMeIsCheckedAndNotInstalled() = runBlocking {
        Harness("ask-tell").use { h ->
            h.forge.releases = listOf(v2, v1)
            val id = h.addFixture(mode = UpdateMode.NOTIFY)

            h.engine.runScheduledCheck()

            assertEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, h.row(id).status)
            assertTrue(h.describe(id), h.eventsFor(id).any { it.kind == EventKind.UPDATE_FOUND })
            assertNothingWasInstalled(h, id)
        }
    }

    @Test
    fun anAppSetToBeLeftAloneIsNotEvenChecked() = runBlocking {
        Harness("ask-manual").use { h ->
            h.forge.releases = listOf(v2, v1)
            val id = h.addFixture(mode = UpdateMode.MANUAL)

            h.engine.runScheduledCheck()

            assertNull(h.describe(id), h.state(id).lastCheckedMs)
            assertTrue(h.forge.requests.toString(), h.forge.requests.isEmpty())
            assertNothingWasInstalled(h, id)
        }
    }

    @Test
    fun anAppThatArrivedInAFileAskingForItIsNotInstalledEither() = runBlocking {
        Harness("ask-import").use { h ->
            h.forge.releases = listOf(v2, v1)
            val asking = AppConfig(id = "arrived", source = SourceSpec(SourceTypes.FORGEJO, FakeForge.PROJECT), name = "Fixture", packageName = PKG, updates = UpdateMode.AUTO)
            val summary = h.engine.importReceived(Received.ExportFile("stamp-export.json", StampExport.write(listOf(asking), 1L, "0.1.0").toByteArray()))
            assertEquals(1, summary.added)
            assertEquals("the summary has to say that the file asked for it", listOf("Fixture"), summary.askedToInstallByThemselves)
            val id = "arrived"
            assertEquals(UpdateMode.NOTIFY, h.row(id).config.updates)
            waitUntil(30_000, "the imported app to be checked") { h.row(id).lastCheckedMs != null && !h.row(id).checking }

            h.engine.runScheduledCheck()

            assertEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, h.row(id).status)
            assertEquals(UpdateMode.NOTIFY, h.row(id).config.updates)
            assertNothingWasInstalled(h, id)
        }
    }
}
