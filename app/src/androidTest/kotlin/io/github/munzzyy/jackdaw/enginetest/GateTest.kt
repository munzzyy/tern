package io.github.munzzyy.jackdaw.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.apk.ApkInspector
import io.github.munzzyy.jackdaw.core.apk.BytesSource
import io.github.munzzyy.jackdaw.core.verify.Fingerprints
import io.github.munzzyy.jackdaw.data.FileFacts
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.ChecksumState
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.install.Gate
import io.github.munzzyy.jackdaw.install.GatePass
import io.github.munzzyy.jackdaw.install.Installer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GateTest {
    @Before
    fun setUp() {
        grantInstallPermissions()
        uninstallFixture()
    }

    @After
    fun tearDown() {
        Prompt.dismiss()
        uninstallFixture()
    }

    @Test
    fun anUpdateSignedByAnotherKeyNeverReachesTheInstaller() = runBlocking {
        assertTrue(shellInstall(asset("apk/app-v1.apk")).contains("Success"))
        Harness("signer").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v2.0", listOf(FakeForge.File("app-v2.apk", asset("apk/app-v2-otherkey.apk")))))
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(30_000, "the gate to decide") { h.state(id).block != null || h.state(id).installProblem != null }

            assertEquals(ProblemKind.SIGNER_MISMATCH, h.state(id).block?.problem?.kind)
            assertEquals(0, h.installer.prepared.get())
            assertEquals(1L, installedVersionCode())
            waitUntil(5_000, "the row to show the block") { h.row(id).progress == null }
            assertEquals(AppStatus.BLOCKED, h.row(id).status)
            assertEquals(ProblemKind.SIGNER_MISMATCH, h.row(id).problem?.kind)
            assertTrue(h.eventsFor(id).none { it.kind == EventKind.INSTALLED })
            assertTrue(h.eventsFor(id).any { it.kind == EventKind.BLOCKED })
        }
    }

    @Test
    fun aRefusedInstallLeavesTheRowTellingTheTruth() = runBlocking {
        assertTrue(shellInstall(asset("apk/app-v2.apk")).contains("Success"))
        val v1Facts = FileFacts.of(ApkInspector.inspect(BytesSource(asset("apk/app-v1.apk"))), android.os.Build.VERSION.SDK_INT)
        val passEverything = Gate { request -> GatePass(listOf(request.file), v1Facts.copy(verified = true), split = false) }
        Harness("refused", gate = passEverything).use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(30_000, "the session to be committed") { h.installer.committed.get() == 1 }
            try {
                Prompt.confirm(timeoutMs = 15_000)
            } catch (e: AssertionError) {
                android.util.Log.i("EngineTest", "no confirmation was asked for the downgrade: ${e.message}")
            }
            waitUntil(60_000, "the installer's answer") { h.state(id).pending == null && h.state(id).installProblem != null }
            android.util.Log.i("EngineTest", "installer answered: ${h.state(id).installProblem}")
            waitUntil(5_000, "the row to show the answer") { h.row(id).progress == null && h.row(id).problem != null }

            assertEquals(2L, installedVersionCode())
            val row = h.row(id)
            android.util.Log.i("EngineTest", "refused install reads: ${row.status} ${row.problem}")
            assertEquals(2L, row.installed?.versionCode)
            assertNull(h.state(id).record)
            assertNotEquals(AppStatus.UPDATE_AVAILABLE, row.status)
            assertTrue(row.problem?.kind == ProblemKind.DOWNGRADE || row.problem?.kind == ProblemKind.INSTALL_FAILED)
            assertTrue(h.eventsFor(id).none { it.kind == EventKind.INSTALLED })
            assertTrue(h.eventsFor(id).any { it.kind == EventKind.FAILED })
        }
    }

    @Test
    fun aChecksumMismatchDeletesTheFileAndBlocks() = runBlocking {
        val v1 = asset("apk/app-v1.apk")
        Harness("sum-bad").use { h ->
            val wrong = "0".repeat(64)
            h.forge.releases = listOf(
                FakeForge.Release(
                    "v1.0",
                    listOf(FakeForge.File("app-v1.apk", v1), FakeForge.File("app-v1.apk.sha256", "$wrong  app-v1.apk\n".toByteArray())),
                ),
            )
            val id = h.addFixture()
            h.engine.check(id)
            assertEquals(ChecksumState.PENDING, h.row(id).verification?.checksum)
            h.engine.install(id)
            waitUntil(30_000, "the gate to decide") { h.state(id).block != null }

            assertEquals(ProblemKind.CHECKSUM_MISMATCH, h.state(id).block?.problem?.kind)
            assertNull(h.engine.downloader.kept(id, "${FakeForge.FILES}v1.0/app-v1.apk"))
            assertTrue(h.engine.downloader.folder(id).listFiles().orEmpty().none { it.name.endsWith(".bin") || it.name.endsWith(".part") })
            waitUntil(5_000, "the row to show the block") { h.row(id).progress == null }
            assertEquals(AppStatus.BLOCKED, h.row(id).status)
            assertEquals(ChecksumState.MISMATCH, h.row(id).verification?.checksum)
            assertEquals(0, h.installer.prepared.get())
        }
    }

    @Test
    fun aMatchingChecksumIsReportedWithItsSource() = runBlocking {
        val v1 = asset("apk/app-v1.apk")
        Harness("sum-good", installer = NeverCommits).use { h ->
            h.forge.releases = listOf(
                FakeForge.Release(
                    "v1.0",
                    listOf(FakeForge.File("app-v1.apk", v1), FakeForge.File("app-v1.apk.sha256", "${Fingerprints.sha256(v1)}  app-v1.apk\n".toByteArray())),
                ),
            )
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(30_000, "the file to pass the gate") { h.state(id).pending != null }
            waitUntil(5_000, "the row to show the checksum") { h.row(id).verification?.checksum == ChecksumState.MATCHED }

            val verification = h.row(id).verification!!
            assertEquals("checksum file app-v1.apk.sha256", verification.checksumSource)
            assertTrue(verification.signersVerified)
            assertEquals(PKG, verification.packageName)
        }
    }

    @Test
    fun aSplitBundleInstallsAsOneSession() = runBlocking {
        Harness("split").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v3.0", listOf(FakeForge.File("app-3.0.xapk", asset("apk/bundle.xapk")))))
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(30_000, "the session to be committed") { h.installer.committed.get() == 1 || h.state(id).block != null }
            assertNull(h.state(id).block?.problem?.message, h.state(id).block)
            Prompt.confirm()
            waitUntil(60_000, "the bundle to be installed") { installedVersionCode() == 3L && h.state(id).pending == null }

            assertEquals(1, h.installer.prepared.get())
            val files = synchronized(h.installer.lastFiles) { h.installer.lastFiles.toList() }
            assertTrue("expected a base and its splits, got ${files.size}", files.size > 1)
            val splits = targetContext.packageManager.getApplicationInfo(PKG, 0).splitNames.orEmpty().toList()
            android.util.Log.i("EngineTest", "installed splits: $splits")
            assertTrue(splits.contains("config.x86_64"))
            assertTrue(splits.none { it == "config.arm64_v8a" || it == "config.armeabi_v7a" })
            assertEquals(3L, h.state(id).record?.versionCode)
        }
    }

    /** Accepts sessions and never hands them to Android, for checks that stop before the system installer. */
    private object NeverCommits : Installer {
        override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean) = 4242

        override fun commit(appId: String, sessionId: Int) = Unit

        override fun abandon(sessionId: Int) = Unit

        override fun liveSessionIds(): Set<Int> = setOf(4242)

        override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long) = 0
    }
}
