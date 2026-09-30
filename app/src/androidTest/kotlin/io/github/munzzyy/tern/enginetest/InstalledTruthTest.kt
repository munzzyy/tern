package io.github.munzzyy.tern.enginetest

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.apk.ApkInspector
import io.github.munzzyy.tern.core.apk.BytesSource
import io.github.munzzyy.tern.core.engine.InstallRecord
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.verify.Fingerprints
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.data.PendingInstall
import io.github.munzzyy.tern.data.StoredApp
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.real.RealEngine
import io.github.munzzyy.tern.install.Gate
import io.github.munzzyy.tern.install.GatePass
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed means Android said so. Every test here gives the engine a reason to believe in a
 * version the device does not have, and the row has to show what the device has.
 */
@RunWith(AndroidJUnit4::class)
class InstalledTruthTest {
    private val v1 = asset("apk/app-v1.apk")
    private val v2 = asset("apk/app-v2.apk")
    private val release1 = FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", v1)))
    private val release2 = FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", v2)))

    @Before
    fun setUp() {
        prepareDevice()
        uninstallFixture()
        assertTrue(shellInstall(v1).contains("Success"))
        assertEquals(1L, installedVersionCode())
    }

    @After
    fun tearDown() {
        Prompt.dismiss()
        uninstallFixture()
    }

    private fun assertStillOnTheOldVersion(h: Harness, id: String) {
        assertEquals(h.describe(id), 1L, installedVersionCode())
        val row = h.row(id)
        assertEquals(h.describe(id), 1L, row.installed?.versionCode)
        assertNotEquals(h.describe(id), AppStatus.UP_TO_DATE, row.status)
        assertNull(h.describe(id), h.state(id).record)
        assertTrue(h.describe(id), h.eventsFor(id).none { it.kind == EventKind.INSTALLED })
    }

    @Test
    fun aDownloadThatWasNeverConfirmedLeavesTheRowOnTheOldVersion() = runBlocking {
        Harness("truth-unconfirmed").use { h ->
            h.forge.releases = listOf(release2, release1)
            val id = h.addFixture()
            h.engine.check(id)
            assertEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, h.row(id).status)

            h.engine.install(id)
            waitUntil(30_000, "the install to wait for the user") { h.row(id).progress?.phase == Phase.WAITING_FOR_USER }

            assertTrue(h.describe(id), h.eventsFor(id).any { it.kind == EventKind.DOWNLOADED })
            assertTrue(h.describe(id), h.eventsFor(id).any { it.kind == EventKind.VERIFIED })
            assertEquals(2L, h.state(id).pending?.versionCode)
            assertStillOnTheOldVersion(h, id)
            assertEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, h.row(id).status)

            h.engine.check(id)
            assertStillOnTheOldVersion(h, id)

            h.engine.cancel(id)
            waitUntil(10_000, "the row to settle after the cancel") { h.row(id).progress == null && h.state(id).pending == null }
            assertStillOnTheOldVersion(h, id)
            assertEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, h.row(id).status)
        }
    }

    @Test
    fun noInTheSystemInstallerIsACancelAndNoProblem() = runBlocking {
        Harness("truth-cancelled").use { h ->
            h.forge.releases = listOf(release2, release1)
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            h.cancel(id)
            waitUntil(30_000, "the answer to the cancel") { h.eventsFor(id).any { it.kind == EventKind.CANCELLED } }
            waitUntil(10_000, "the row to settle") { h.row(id).progress == null && h.state(id).pending == null }
            assertStillOnTheOldVersion(h, id)
            assertNull(h.describe(id), h.state(id).installProblem)
            assertEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, h.row(id).status)
            assertNull(h.describe(id), h.row(id).problem)
            assertTrue(h.eventsFor(id).none { it.kind == EventKind.FAILED })
            assertNotNull("the file stays for the next try", h.engine.downloader.kept(id, h.row(id).file!!.asset.url))
        }
    }

    /** What a process that died between the download and the answer leaves behind: a pending install whose session is gone. */
    @Test
    fun anInstallLeftWaitingIsNotCountedWhenTernStartsAgain() = runBlocking {
        val name = "truth-restart"
        val store = "enginetest-$name.db"
        val downloads = File(targetContext.filesDir, "enginetest-$name")
        val forge = FakeForge().also { it.releases = listOf(release2, release1) }
        fun engine() = RealEngine(targetContext, forge, storeName = store, prefsPrefix = "enginetest-$name-", downloadsDir = downloads)
        targetContext.deleteDatabase(store)
        try {
            val id = "fixture"
            engine().use { first ->
                first.saveSettings(first.settings.value.copy(checkEveryMinutes = 0))
                val config = AppConfig(id = id, source = SourceSpec(SourceTypes.FORGEJO, FakeForge.PROJECT), name = "Fixture", packageName = PKG)
                first.store.putApp(config, AppState())
                first.stored[id] = StoredApp(config, AppState())
                first.check(id)
                assertEquals(AppStatus.UPDATE_AVAILABLE, first.apps.value.first { it.id == id }.status)
                first.saveState(id) {
                    it.copy(
                        pending = PendingInstall(
                            sessionId = 987_654, packageName = PKG, releaseId = "v2.0", version = "2.0", versionCode = 2,
                            fileSha256 = Fingerprints.sha256(v2), fileSize = v2.size.toLong(), assetUrl = "${FakeForge.FILES}v2.0/app-v2.apk", startedAtMs = 1,
                            waitingForUser = true,
                        ),
                    )
                }
            }

            engine().use { again ->
                again.ready()
                val row = again.apps.value.first { it.id == id }
                val state = again.store.app(id)!!.state
                assertEquals(1L, installedVersionCode())
                assertEquals(1L, row.installed?.versionCode)
                assertNull(state.pending)
                assertNull(state.record)
                assertNull(row.progress)
                assertEquals(AppStatus.UPDATE_AVAILABLE, row.status)
                assertEquals(ProblemKind.INSTALL_FAILED, row.problem?.kind)
                assertTrue(again.events.value.none { it.appId == id && it.kind == EventKind.INSTALLED })
            }
        } finally {
            targetContext.deleteDatabase(store)
            targetContext.deleteSharedPreferences("enginetest-$name-settings")
            targetContext.deleteSharedPreferences("enginetest-$name-tokens")
            downloads.deleteRecursively()
        }
    }

    @Test
    fun anInstallAndroidRefusedForItsSignerLeavesTheRowOnTheOldVersion() = runBlocking {
        val otherKey = asset("apk/app-v2-otherkey.apk")
        val facts = FileFacts.of(ApkInspector.inspect(BytesSource(otherKey)), Build.VERSION.SDK_INT)
        val passEverything = Gate { request -> GatePass(listOf(request.file), facts.copy(verified = true), split = false) }
        Harness("truth-refused", gate = passEverything).use { h ->
            h.forge.releases = listOf(FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", otherKey))))
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(30_000, "the session to be committed") { h.installer.committed.get() == 1 }
            try {
                h.confirm(id, timeoutMs = 15_000)
            } catch (e: AssertionError) {
                android.util.Log.i("EngineTest", "no confirmation was asked for the other signer: ${e.message}")
            }
            waitUntil(60_000, "the installer's answer") { h.state(id).pending == null && h.state(id).installProblem != null }
            waitUntil(5_000, "the row to show the answer") { h.row(id).progress == null && h.row(id).problem != null }

            assertStillOnTheOldVersion(h, id)
            assertTrue(h.describe(id), h.eventsFor(id).any { it.kind == EventKind.FAILED })
            assertNotEquals("the installed app is signed with the key of the refused file", facts.signers, h.row(id).installed?.signers)
        }
    }

    @Test
    fun anAppRemovedOutsideTernReadsAsNotInstalledAtTheNextLook() = runBlocking {
        Harness("truth-removed").use { h ->
            h.forge.releases = listOf(release1)
            val id = h.addFixture()
            h.engine.saveState(id) { it.copy(record = InstallRecord("v1.0", "1.0", 1, Fingerprints.sha256(v1), v1.size.toLong())) }
            h.engine.check(id)
            assertEquals(h.describe(id), AppStatus.UP_TO_DATE, h.row(id).status)
            assertEquals(1L, h.row(id).installed?.versionCode)

            uninstallFixture()
            assertNull(installedVersionCode())
            h.engine.check(id)

            val row = h.row(id)
            assertNull(h.describe(id), row.installed)
            assertEquals(h.describe(id), AppStatus.NOT_INSTALLED, row.status)
            assertNotNull("the record of the earlier install is history, and stays", h.state(id).record)
        }
    }

    @Test
    fun aRemovalIsNoticedWithoutACheckToo() = runBlocking {
        Harness("truth-removed-quietly").use { h ->
            h.forge.releases = listOf(release1)
            val id = h.addFixture()
            h.engine.check(id)
            assertEquals(1L, h.row(id).installed?.versionCode)
            val asked = h.forge.requests.size

            uninstallFixture()
            waitUntil(10_000, "the row to notice the removal") { h.row(id).installed == null }
            assertEquals(h.describe(id), AppStatus.NOT_INSTALLED, h.row(id).status)
            assertEquals("the removal was read off the device, not asked of the source", asked, h.forge.requests.size)
        }
    }

    @Test
    fun aStoredStateThatClaimsANewerVersionChangesNothingOnTheRow() = runBlocking {
        Harness("truth-claim").use { h ->
            h.forge.releases = listOf(release2, release1)
            val id = h.addFixture()
            h.engine.saveState(id) {
                it.copy(
                    record = InstallRecord("v2.0", "2.0", 2, Fingerprints.sha256(v2), v2.size.toLong()),
                    pending = PendingInstall(
                        sessionId = 987_654, packageName = PKG, releaseId = "v2.0", version = "2.0", versionCode = 2,
                        fileSha256 = Fingerprints.sha256(v2), fileSize = v2.size.toLong(), assetUrl = "${FakeForge.FILES}v2.0/app-v2.apk", startedAtMs = 1,
                    ),
                )
            }
            h.engine.check(id)

            val row = h.row(id)
            assertEquals(1L, installedVersionCode())
            assertEquals(h.describe(id), 1L, row.installed?.versionCode)
            assertEquals(h.describe(id), AppStatus.UPDATE_AVAILABLE, row.status)
            assertEquals("v2.0", row.latest?.id)
            assertFalse(h.describe(id), h.settledOn(id, 2))
        }
    }

    @Test
    fun aClaimWithoutAFileToReadStillDoesNotCountAsInstalled() = runBlocking {
        val unreadable = Routes(FakeForge().also { it.releases = listOf(release2, release1) })
        unreadable.on("${FakeForge.FILES}v2.0/app-v2.apk") { request -> HttpResponse.of(503, "busy", url = request.url) }
        Harness("truth-claim-unread", http = unreadable).use { h ->
            val id = h.addFixture()
            h.engine.saveState(id) { it.copy(record = InstallRecord("v2.0", "2.0", 2, Fingerprints.sha256(v2), v2.size.toLong())) }
            h.engine.check(id)

            val row = h.row(id)
            assertEquals(h.describe(id), 1L, row.installed?.versionCode)
            assertNotEquals(h.describe(id), AppStatus.UP_TO_DATE, row.status)
            assertNull("the file was read after all, so this test proved nothing", row.verification?.signers?.takeIf { it.isNotEmpty() })
        }
    }
}
