package io.github.munzzyy.jackdaw.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.model.UpdateMode
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.verify.Fingerprints
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.engine.EventKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadlineTest {
    @Before
    fun setUp() {
        grantInstallPermissions()
        uninstallFixture()
    }

    @After
    fun tearDown() = uninstallFixture()

    @Test
    fun installsOnceWithAPromptThenUpdatesInTheBackgroundWithout() = runBlocking {
        Harness("headline").use { h ->
            val v1 = asset("apk/app-v1.apk")
            val v2 = asset("apk/app-v2.apk")
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", v1))))

            val found = h.engine.detect(FakeForge.PROJECT) as Detection.Found
            assertEquals(SourceTypes.FORGEJO, found.spec.type)
            assertEquals(PKG, found.verification?.packageName)
            val id = h.engine.add(found, install = true)
            val shownBy = Prompt.confirm()
            android.util.Log.i("EngineTest", "first install confirmed through the $shownBy")
            waitUntil(60_000, "v1 to be installed and settled") { installedVersionCode() == 1L && h.state(id).pending == null }
            waitUntil(10_000, "the row to settle on v1") { h.row(id).status == AppStatus.UP_TO_DATE }
            assertEquals(true, h.row(id).silentUpdate)
            assertTrue(h.row(id).config.pinnedSigners.isNotEmpty())

            h.engine.save(h.row(id).config.copy(updates = UpdateMode.AUTO))
            h.forge.releases = listOf(
                FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", v2))),
                FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", v1))),
            )
            h.engine.runScheduledCheck()
            assertFalse("the system installer asked the user", Prompt.visible())

            assertEquals(2L, installedVersionCode())
            waitUntil(10_000, "the row to settle on v2") { h.row(id).status == AppStatus.UP_TO_DATE && h.row(id).progress == null }
            val row = h.row(id)
            assertEquals(2L, row.installed?.versionCode)
            assertEquals("v2.0", row.latest?.id)
            val record = checkNotNull(h.state(id).record) { "no install record" }
            assertEquals("v2.0", record.releaseId)
            assertEquals(2L, record.versionCode)
            assertEquals(Fingerprints.sha256(v2), record.fileSha256)
            val installs = h.eventsFor(id).filter { it.kind == EventKind.INSTALLED }
            assertEquals(2, installs.size)
            assertTrue(installs.first().message.contains("2"))
            assertEquals(2, h.installer.committed.get())
        }
    }
}
