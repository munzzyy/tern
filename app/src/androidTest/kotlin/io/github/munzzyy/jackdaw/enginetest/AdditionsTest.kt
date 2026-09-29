package io.github.munzzyy.jackdaw.enginetest

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdditionsTest {
    private val alias = ComponentName(targetContext, "io.github.munzzyy.jackdaw.ObtainiumLinks")
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
        grantInstallPermissions()
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
            val session = h.state(id).pending!!.sessionId
            targetContext.packageManager.packageInstaller.abandonSession(session)
            Prompt.dismiss()

            assertFalse(h.engine.resumeInstall(id))
            waitUntil(10_000, "the phase to settle") { h.state(id).pending == null && h.row(id).progress == null }
            assertNull(h.state(id).pending)
            assertNull(h.row(id).progress)
            assertEquals(ProblemKind.INSTALL_FAILED, h.row(id).problem?.kind)
        }
    }
}
