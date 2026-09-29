package io.github.munzzyy.stamp.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.core.model.UpdateMode
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.verify.Fingerprints
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.Detection
import io.github.munzzyy.stamp.engine.EventKind
import io.github.munzzyy.stamp.engine.Phase
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
        prepareDevice()
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
            val shownBy = h.confirm(id)
            android.util.Log.i("EngineTest", "first install confirmed through the $shownBy")
            waitUntil(60_000, "v1 to be installed and settled") { installedVersionCode() == 1L && h.state(id).pending == null }
            waitUntil(10_000, "the row to settle on v1") {
                val row = h.row(id)
                row.status == AppStatus.UP_TO_DATE && row.silentUpdate == true && row.config.pinnedSigners.isNotEmpty()
            }

            h.engine.configure(id) { it.copy(updates = UpdateMode.AUTO) }
            h.forge.releases = listOf(
                FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", v2))),
                FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", v1))),
            )
            h.engine.runScheduledCheck()
            assertFalse("the system installer asked the user", Prompt.visible())

            assertEquals(h.describe(id), 2L, installedVersionCode())
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

    @Test
    fun whenAndroidAsksAnywayTheBackgroundUpdateWaitsForTheUserAndThenFinishes() = runBlocking {
        throttleSilentUpdates(600)
        Harness("throttled").use { h ->
            val v1 = FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk"))))
            val v2 = FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", asset("apk/app-v2.apk"))))
            h.forge.releases = listOf(v1)
            val id = h.engine.add(h.engine.detect(FakeForge.PROJECT) as Detection.Found, install = true)
            h.confirm(id)
            waitUntil(60_000, "v1 to be installed and settled") { h.settledOn(id, 1) }
            h.engine.configure(id) { it.copy(updates = UpdateMode.AUTO) }
            h.forge.releases = listOf(v2, v1)
            h.engine.runScheduledCheck()
            assertEquals(h.describe(id), 2L, installedVersionCode())

            uninstallFixture()
            waitUntil(10_000, "the row to notice the uninstall") { h.row(id).installed == null }
            h.forge.releases = listOf(v1)
            h.engine.check(id)
            h.engine.install(id)
            h.confirm(id)
            waitUntil(60_000, "v1 to be installed again") { h.settledOn(id, 1) }

            h.forge.releases = listOf(v2, v1)
            h.engine.runScheduledCheck()

            assertFalse("the installer's dialog opened over whatever the user was doing", Prompt.visible())
            assertEquals(h.describe(id), 1L, installedVersionCode())
            val waiting = h.row(id)
            assertEquals(h.describe(id), Phase.WAITING_FOR_USER, waiting.progress?.phase)
            assertEquals(h.describe(id), true, h.state(id).pending?.waitingForUser)
            assertEquals(h.describe(id), null, waiting.problem)
            assertTrue(h.describe(id), h.eventsFor(id).none { it.kind == EventKind.FAILED })

            assertEquals("notification", h.confirm(id))
            waitUntil(60_000, "v2 to be installed once confirmed") {
                installedVersionCode() == 2L && h.state(id).pending == null && h.row(id).status == AppStatus.UP_TO_DATE && h.row(id).progress == null
            }
            assertEquals("v2.0", h.state(id).record?.releaseId)
        }
    }
}
