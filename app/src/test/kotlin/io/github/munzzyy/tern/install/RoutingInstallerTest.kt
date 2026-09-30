package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.engine.InstallerMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each install goes to the installer chosen when it starts, and stays with it to the end. */
class RoutingInstallerTest {
    private class Recording(val name: String, private val firstSession: Int) : Installer {
        val log = ArrayList<String>()
        private val live = HashSet<Int>()
        private var next = firstSession

        override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int =
            next++.also { live += it; log += "prepare $it" }

        override fun commit(appId: String, sessionId: Int) {
            log += "commit $sessionId"
        }

        override fun abandon(sessionId: Int) {
            live -= sessionId
            log += "abandon $sessionId"
        }

        override fun liveSessionIds(): Set<Int> = live.toSet()

        override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int = 0
    }

    private val apk = listOf(File("base.apk"))

    @Test
    fun aSessionStaysWithTheInstallerThatMadeItWhenTheSettingChanges() {
        val system = Recording("system", 100)
        val shizuku = Recording("shizuku", 200)
        var mode = InstallerMode.SHIZUKU
        val routing = RoutingInstaller(system, mapOf(InstallerMode.SHIZUKU to shizuku), mode = { mode })
        val session = routing.prepare("org.example.app", apk, false)
        mode = InstallerMode.SYSTEM
        routing.commit("app", session)
        assertEquals(listOf("prepare 200", "commit 200"), shizuku.log)
        assertEquals(emptyList<String>(), system.log)
    }

    @Test
    fun aModeWithoutItsOwnInstallerUsesAndroidsOwn() {
        val system = Recording("system", 100)
        val routing = RoutingInstaller(system, emptyMap(), mode = { InstallerMode.ROOT })
        routing.commit("app", routing.prepare("org.example.app", apk, false))
        assertEquals(listOf("prepare 100", "commit 100"), system.log)
    }

    @Test
    fun afterARestartTheInstallerThatStillListsASessionIsTheOneAsked() {
        val system = Recording("system", 100)
        val other = Recording("other", -5)
        val before = RoutingInstaller(system, mapOf(InstallerMode.OTHER_APP to other), mode = { InstallerMode.OTHER_APP })
        val session = before.prepare("org.example.app", apk, false)
        val after = RoutingInstaller(system, mapOf(InstallerMode.OTHER_APP to other), mode = { InstallerMode.SYSTEM })
        after.abandon(session)
        assertEquals(listOf("prepare -5", "abandon -5"), other.log)
        assertEquals(setOf<Int>(), after.liveSessionIds())
    }
}
