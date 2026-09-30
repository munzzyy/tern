package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.InstallerReadiness
import io.github.munzzyy.tern.ui.settings.readinessWords
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** How Tern reads what Android and Dhizuku answer, the names and numbers it speaks to Dhizuku with, and the words for each failure. */
class DhizukuTest {
    private val ready = DhizukuFacts(installed = true, owner = DhizukuProtocol.OFFICIAL, ownerInstalls = true, answered = true, granted = true)

    @Test
    fun eachAnswerLeadsToTheStateThePersonIsShown() {
        assertEquals(DhizukuState.READY, ready.state())
        assertEquals(DhizukuState.NOT_ALLOWED, ready.copy(granted = false).state())
        // A profile owner that Android does not let install without asking is of no use.
        assertEquals(DhizukuState.NOT_OWNER, ready.copy(ownerInstalls = false).state())
        assertEquals(DhizukuState.NOT_OWNER, ready.copy(ownerInstalls = false, granted = false).state())
        // Dhizuku is the owner and gave no binder, or the binder did not answer.
        assertEquals(DhizukuState.NOT_ANSWERING, ready.copy(answered = false, granted = false).state())
        // Another app holds the role, or none does.
        assertEquals(DhizukuState.NOT_OWNER, ready.copy(owner = "org.example.mdm", answered = false, granted = false).state())
        assertEquals(DhizukuState.NOT_OWNER, ready.copy(owner = null, ownerInstalls = false, answered = false, granted = false).state())
        assertEquals(DhizukuState.NOT_INSTALLED, ready.copy(installed = false, owner = null, ownerInstalls = false, answered = false, granted = false).state())
        assertEquals(DhizukuState.NOT_INSTALLED, ready.copy(installed = false, owner = "org.example.mdm", answered = false, granted = false).state())
    }

    @Test
    fun anAppThatSpeaksDhizukusProtocolUnderAnotherNameIsUsedAsDhizukuIs() {
        val other = ready.copy(installed = false, owner = "org.example.owner")
        assertEquals(DhizukuState.READY, other.state())
        assertEquals(DhizukuState.NOT_ALLOWED, other.copy(granted = false).state())
        assertEquals("org.example.owner.dhizuku_server.provider", DhizukuProtocol.authority("org.example.owner"))
        assertEquals("org.example.owner.action.REQUEST_DHIZUKU_PERMISSION", DhizukuProtocol.requestAction("org.example.owner"))
    }

    @Test
    fun dhizukuItselfIsReachedAtTheNamesItsLibraryUses() {
        assertEquals("com.rosan.dhizuku.server.provider", DhizukuProtocol.authority("com.rosan.dhizuku"))
        assertEquals("com.rosan.dhizuku.action.request.permission", DhizukuProtocol.requestAction("com.rosan.dhizuku"))
        assertEquals("com.rosan.dhizuku.server", DhizukuProtocol.REMOTE)
        assertEquals("client", DhizukuProtocol.METHOD_CLIENT)
        assertEquals("dhizuku_binder", DhizukuProtocol.EXTRA_SERVER)
    }

    @Test
    fun callsAreNumberedAsDhizukusInterfacesNumberThem() {
        // The first call is 1; isPermissionGranted is 2 after it and a call made on another binder 10.
        assertEquals(3, DhizukuProtocol.IS_PERMISSION_GRANTED)
        assertEquals(11, DhizukuProtocol.REMOTE_TRANSACT)
        assertEquals(1, DhizukuProtocol.CLIENT_GET_VERSION)
        assertEquals(1, DhizukuProtocol.LISTENER_ON_RESULT)
    }

    @Test
    fun everyStateHasWordsOfItsOwnInSettingsAndOnAFailedInstall() {
        assertEquals(DhizukuState.entries.size, DhizukuState.entries.map { it.readiness }.distinct().size)
        assertEquals(InstallerReadiness.READY, DhizukuState.READY.readiness)
        assertNull(readinessWords(DhizukuState.READY.readiness))
        val stopped = DhizukuState.entries - DhizukuState.READY
        val settings = stopped.map { readinessWords(it.readiness) }
        settings.forEach(::assertNotNull)
        assertEquals(stopped.size, settings.distinct().size)
        assertEquals(stopped.size, stopped.map { DhizukuInstaller.problemWords(it) }.distinct().size)
        assertEquals(R.string.installer_dhizuku_unsupported, DhizukuInstaller.problemWords(DhizukuState.UNSUPPORTED))
        assertEquals(R.string.dhizuku_failed_not_allowed, DhizukuInstaller.problemWords(DhizukuState.NOT_ALLOWED))
    }

    private val words = { state: DhizukuState -> "words for $state" }

    @Test
    fun whateverStopsAnInstallReachesItAsWordsAndNeverAsACrash() {
        val stopped = DhizukuInstaller.told(DhizukuException(DhizukuState.NOT_OWNER), { DhizukuState.READY }, words)
        assertTrue(stopped is IOException)
        assertEquals("words for NOT_OWNER", stopped.message)

        // Dhizuku turned Tern away: asked again, it says so.
        val refused = DhizukuInstaller.told(SecurityException("Permission Denial: remote_transact"), { DhizukuState.NOT_ALLOWED }, words)
        assertTrue(refused is IOException)
        assertEquals("words for NOT_ALLOWED", refused.message)

        // Dhizuku still lets Tern in, so Android refused, and its own reason stands.
        val android = SecurityException("User restriction prevents installing")
        assertSame(android, DhizukuInstaller.told(android, { DhizukuState.READY }, words))

        val full = DhizukuInstaller.told(IllegalStateException("Too many active sessions for UID 10123"), { DhizukuState.READY }, words)
        assertTrue(full is IOException)
        assertEquals("Too many active sessions for UID 10123", full.message)

        val disk = IOException("No suitable internal storage available")
        assertSame(disk, DhizukuInstaller.told(RuntimeException(disk), { DhizukuState.READY }, words))

        // Installs takes an IOException or a SecurityException as a failure in words; nothing else comes out.
        for (thrown in listOf(DhizukuException(DhizukuState.UNSUPPORTED), SecurityException(), IllegalArgumentException(), NullPointerException())) {
            for (now in DhizukuState.entries) {
                val told = DhizukuInstaller.told(thrown, { now }, words)
                assertTrue("$thrown with $now", told is IOException || told is SecurityException)
            }
        }
    }

    @Test
    fun aMemberThisAndroidLacksIsSaidToBeUnsupported() {
        assertEquals(DhizukuState.UNSUPPORTED, (NonSdkCalls.unwrapped(NoSuchMethodException("openSession")) as DhizukuException).state)
        assertEquals(DhizukuState.UNSUPPORTED, (NonSdkCalls.unwrapped(ClassNotFoundException(NonSdk.SERVICE_MANAGER)) as DhizukuException).state)
        assertEquals(DhizukuState.UNSUPPORTED, (NonSdkCalls.unwrapped(InvocationTargetException(Exception("checked"))) as DhizukuException).state)
        // What the member itself threw comes out as it is, for the words above to take.
        val refused = SecurityException("Session does not belong to uid 10123")
        assertSame(refused, NonSdkCalls.unwrapped(InvocationTargetException(refused)))
        val gone = DhizukuException(DhizukuState.NOT_ANSWERING)
        assertSame(gone, NonSdkCalls.unwrapped(InvocationTargetException(gone)))
    }

    /** An installer that says what it was asked, or refuses to make a session. */
    private class Recording(private val first: Int, private val refuses: Boolean = false) : Installer {
        val log = ArrayList<String>()
        private var next = first

        override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
            if (refuses) throw IOException("Dhizuku is not the device owner.")
            return next++.also { log += "prepare $it" }
        }

        override fun commit(appId: String, sessionId: Int) {
            log += "commit $sessionId"
        }

        override fun abandon(sessionId: Int) {
            log += "abandon $sessionId"
        }

        override fun liveSessionIds(): Set<Int> = emptySet()

        override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int = 0
    }

    @Test
    fun anInstallThatDhizukuCannotTakeStopsThereAndGoesNowhereElse() {
        val system = Recording(100)
        val dhizuku = Recording(300, refuses = true)
        val routing = RoutingInstaller(system, mapOf(InstallerMode.DHIZUKU to dhizuku), mode = { InstallerMode.DHIZUKU })
        try {
            routing.prepare("org.example.app", listOf(File("base.apk")), claimUpdateOwnership = false)
            fail("An install Dhizuku cannot take must stop")
        } catch (e: IOException) {
            assertEquals("Dhizuku is not the device owner.", e.message)
        }
        assertEquals(emptyList<String>(), system.log)
    }
}
