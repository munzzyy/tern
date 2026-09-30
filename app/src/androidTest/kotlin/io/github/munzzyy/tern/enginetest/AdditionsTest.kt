package io.github.munzzyy.tern.enginetest

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdditionsTest {
    private val alias = ComponentName(targetContext, "io.github.munzzyy.tern.ObtainiumLinks")
    private val pm = targetContext.packageManager

    @After
    fun tearDown() {
        shell("cmd connectivity airplane-mode disable")
        Prompt.dismiss()
        uninstallFixture()
    }

    @Test
    fun theObtainiumAliasFollowsTheStoredSetting() = runBlocking {
        Harness("alias", keepPrefs = true).use { h ->
            h.engine.saveSettings(h.engine.settings.value.copy(openObtainiumLinks = true))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(alias))
            h.engine.saveSettings(h.engine.settings.value.copy(openObtainiumLinks = false))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(alias))
            h.engine.saveSettings(h.engine.settings.value.copy(openObtainiumLinks = true))
        }
        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        Harness("alias", freshPrefs = false).use { h ->
            h.engine.ready()
            assertTrue(h.engine.settings.value.openObtainiumLinks)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(alias))
            h.engine.saveSettings(h.engine.settings.value.copy(openObtainiumLinks = false))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(alias))
        }
    }

    /** Orbot publishes release candidates only, and the forge does not mark them: only the version says what they are. */
    @Test
    fun anAppThatOnlyPublishesPreReleasesIsAddedTheWayItWasShown() = runBlocking {
        Harness("only-candidates").use { h ->
            h.forge.releases = listOf(FakeForge.Release("2.0-RC-1", listOf(FakeForge.File("app-v2.apk", asset("apk/app-v2.apk")))))
            val found = h.engine.detect(FakeForge.PROJECT) as Detection.Found
            assertEquals("2.0-RC-1", found.release?.id)
            assertTrue("the preview has to say that this is a pre-release: ${found.warnings}", found.warnings.isNotEmpty())
            assertTrue(h.engine.proposedConfig(found).releases.includePrereleases)

            val id = h.engine.add(found, install = false)
            assertTrue(h.row(id).config.releases.includePrereleases)
            assertEquals(h.describe(id), AppStatus.NOT_INSTALLED, h.row(id).status)
            assertEquals(h.describe(id), "2.0-RC-1", h.row(id).latest?.id)
            assertEquals(h.describe(id), null, h.row(id).problem)
        }
    }

    @Test
    fun whatIsProposedIsWhatGetsStored() = runBlocking {
        Harness("proposal").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val settings = Json.write(Json.obj("apkFilterRegEx" to "app-v\\d", "filterReleaseTitlesByRegEx" to "^Version", "minimumUpdateAgeDays" to 0))
            val app = Json.write(
                Json.obj(
                    "id" to PKG, "url" to FakeForge.PROJECT, "name" to "Linked", "author" to "example",
                    "overrideSource" to "Codeberg", "additionalSettings" to settings,
                ),
            )
            val found = h.engine.detect("obtainium://app/${Uri.encode(app)}") as Detection.Found
            assertEquals("app-v\\d", found.carried?.assets?.include)
            assertEquals(0, h.engine.stored.size)
            val proposed = h.engine.proposedConfig(found)
            assertEquals("app-v\\d", proposed.assets.include)
            assertEquals("^Version", proposed.releases.titleFilter)
            assertEquals("Linked", proposed.name)
            assertEquals(proposed, h.engine.proposedConfig(found))

            val id = h.engine.add(found, install = false)
            assertEquals(proposed.id, id)
            assertEquals(proposed, h.engine.store.app(id)!!.config)
            assertEquals(proposed, h.row(id).config)
        }
    }

    @Test
    fun aCheckStartedOfflineFailsAtOnceWithOneProblem() = runBlocking {
        Harness("offline").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            repeat(3) { h.addFixture(id = "offline-$it") }
            assertTrue(h.engine.online.value)
            shell("cmd connectivity airplane-mode enable")
            val system = targetContext.getSystemService(ConnectivityManager::class.java)
            val gone = runCatching { waitUntil(10_000, "Android to lose its network") { system.activeNetwork == null } }.isSuccess
            Assume.assumeTrue("airplane mode leaves this device's wired network up, as on a television", gone)
            waitUntil(20_000, "the engine to see the network go") { !h.engine.online.value }

            val start = System.nanoTime()
            h.engine.check()
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("offline check took $ms ms", ms < 1_000)
            assertEquals(ProblemKind.NETWORK, h.engine.lastRunProblem.value?.kind)
            assertTrue(h.forge.requests.isEmpty())
            assertEquals(1, h.engine.events.value.count { it.kind == EventKind.CHECK_FAILED })
            assertTrue(h.engine.apps.value.all { it.problem == null })

            shell("cmd connectivity airplane-mode disable")
            waitUntil(60_000, "the engine to see the network return") { h.engine.online.value }
            h.engine.check()
            assertNull(h.engine.lastRunProblem.value)
            assertEquals(3, h.forge.requests.count { it.url.contains("/releases") })
        }
    }

    @Test
    fun aWaitingInstallCanBeReopenedAndSettlesWhenItsSessionIsGone() = runBlocking {
        prepareDevice()
        uninstallFixture()
        Harness("resume").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            h.engine.check(id)
            assertFalse(h.engine.resumeInstall(id))
            h.engine.install(id)
            waitUntil(30_000, "the install to wait for the user") { h.row(id).progress?.phase == Phase.WAITING_FOR_USER }
            assertTrue(h.engine.resumeInstall(id))
            assertTrue("the confirmation did not reopen", Prompt.appears(10_000))
            val pending = h.state(id).pending!!
            targetContext.packageManager.packageInstaller.abandonSession(pending.sessionId)
            Prompt.dismiss()

            // Android answers an abandoned session as aborted, as it answers a No in its installer: a cancel, not a problem.
            waitUntil(10_000, "Android's answer to the abandoned session") { h.eventsFor(id).any { it.kind == EventKind.CANCELLED } }
            assertFalse(h.engine.resumeInstall(id))
            waitUntil(10_000, "the phase to settle") { h.state(id).pending == null && h.row(id).progress == null }
            assertNull(h.describe(id), h.row(id).problem)

            h.engine.saveState(id) { it.copy(pending = pending.copy(sessionId = GONE_SESSION)) }
            assertFalse(h.engine.resumeInstall(id))
            assertNull(h.describe(id), h.state(id).pending)
            assertNull(h.describe(id), h.row(id).progress)
            assertEquals(h.describe(id), ProblemKind.INSTALL_FAILED, h.row(id).problem?.kind)
            assertEquals(h.describe(id), 1, h.eventsFor(id).count { it.kind == EventKind.CANCELLED })
        }
    }

    @Test
    fun anAppWithOnlyAPreReleaseFollowsPreReleasesEvenWithoutAFileForThisDevice() = runBlocking {
        val foreign = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a").first { it !in Build.SUPPORTED_ABIS }
        Harness("prerelease-only").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v2.0-beta", listOf(FakeForge.File("app-$foreign.apk", asset("apk/app-v1.apk"))), prerelease = true))
            val found = h.engine.detect(FakeForge.PROJECT) as Detection.Found
            val id = h.engine.add(found, install = false)
            assertTrue(h.describe(id), h.row(id).config.releases.includePrereleases)
            assertNotEquals(h.describe(id), h.engine.texts.onlyPrereleases(), h.row(id).problem?.message)
        }
    }

    private companion object {
        /** A session Android never had, as one is after Tern stopped before Android answered. */
        const val GONE_SESSION = 987_654
    }
}
