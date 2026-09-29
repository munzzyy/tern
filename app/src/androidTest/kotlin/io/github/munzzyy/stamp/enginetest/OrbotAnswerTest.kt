package io.github.munzzyy.stamp.enginetest

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.OrbotState
import io.github.munzzyy.stamp.engine.ProxyMode
import io.github.munzzyy.stamp.engine.real.RealEngine
import io.github.munzzyy.stamp.net.Orbot
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answers that Orbot did not send. Any app can send one, and these come from the test itself.
 * The device must have no Orbot, so that the request Stamp sends reaches nobody. One engine is
 * told that Orbot is there, which is what lets an answer in at all; the other asks Android.
 */
@RunWith(AndroidJUnit4::class)
class OrbotAnswerTest {
    private val names = listOf("orbot-there", "orbot-absent")

    private fun engine(name: String, orbotInstalled: (() -> Boolean)?) = RealEngine(
        targetContext, FakeForge(), storeName = "enginetest-$name.db", prefsPrefix = "enginetest-$name-",
        downloadsDir = File(targetContext.filesDir, "enginetest-$name"), orbotInstalled = orbotInstalled,
    )

    private fun clean() {
        for (name in names) {
            targetContext.deleteDatabase("enginetest-$name.db")
            targetContext.deleteSharedPreferences("enginetest-$name-settings")
            targetContext.deleteSharedPreferences("enginetest-$name-tokens")
            File(targetContext.filesDir, "enginetest-$name").deleteRecursively()
        }
    }

    @Before
    fun setUp() {
        val orbotIsHere = try {
            targetContext.packageManager.getPackageInfo(Orbot.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        Assume.assumeFalse("Orbot is installed on this device, and this test must not talk to it", orbotIsHere)
        clean()
    }

    @After
    fun tearDown() = clean()

    private fun send(status: String, host: String?, port: Int?) {
        val answer = Intent(Orbot.ACTION_STATUS).setPackage(targetContext.packageName).putExtra(Orbot.EXTRA_STATUS, status)
        if (host != null) answer.putExtra(Orbot.EXTRA_SOCKS_PROXY_HOST, host)
        if (port != null) answer.putExtra(Orbot.EXTRA_SOCKS_PROXY_PORT, port)
        targetContext.sendBroadcast(answer)
    }

    private fun proxyOf(engine: RealEngine): String {
        val proxy = engine.proxy()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val at = proxy.address() as InetSocketAddress
        return "${at.hostString}:${at.port}"
    }

    /** Sends OFF and waits for it, then sends the answer and waits for ON, so that each answer is known to have arrived. */
    private fun RealEngine.afterAnswer(host: String?, port: Int?): String {
        send(Orbot.STATUS_OFF, null, null)
        waitUntil(10_000, "the answer that says off") { orbot.value == OrbotState.OFF }
        send(Orbot.STATUS_ON, host, port)
        waitUntil(10_000, "the answer that says on") { orbot.value == OrbotState.ON }
        return proxyOf(this)
    }

    @Test
    fun aForgedAnswerCannotMoveTheProxyOffThisDevice() = runBlocking {
        engine("orbot-there") { true }.use { engine ->
            engine.saveSettings(engine.settings.value.copy(checkEveryHours = 0, proxy = ProxyMode.ORBOT))
            assertEquals("127.0.0.1:9050", proxyOf(engine))

            assertEquals("127.0.0.1:9050", engine.afterAnswer("10.0.0.1", 9999))
            assertEquals("127.0.0.1:9050", engine.afterAnswer("127.0.0.1", 0))
            assertEquals("127.0.0.1:9050", engine.afterAnswer("127.0.0.1", 65536))
            assertEquals("127.0.0.1:9050", engine.afterAnswer("localhost", 9150))
            assertEquals("127.0.0.1:9050", engine.afterAnswer(null, 9150))
            assertEquals("127.0.0.1:9050", engine.afterAnswer("127.0.0.1", null))

            assertEquals("an answer as Orbot sends it has to be taken, or the lines above prove nothing", "127.0.0.1:9150", engine.afterAnswer("127.0.0.1", 9150))

            assertEquals("127.0.0.1:9050", engine.afterAnswer("10.0.0.1", 9999))
        }
    }

    @Test
    fun aForgedAnswerCannotMakeARequestGoDirect() = runBlocking {
        engine("orbot-there") { true }.use { engine ->
            engine.saveSettings(engine.settings.value.copy(checkEveryHours = 0, proxy = ProxyMode.ORBOT))
            for (status in listOf(Orbot.STATUS_OFF, Orbot.STATUS_STOPPING, Orbot.STATUS_STARTS_DISABLED)) {
                engine.afterAnswer("127.0.0.1", 9150)
                send(status, "127.0.0.1", 9150)
                waitUntil(10_000, "the answer that says $status") { engine.orbot.value == OrbotState.OFF }
                assertEquals(status, "127.0.0.1:9050", proxyOf(engine))
            }
            engine.afterAnswer("127.0.0.1", 9150)
            send("DIRECT", "127.0.0.1", 9150)
            send(Orbot.STATUS_STARTING, "", -1)
            waitUntil(10_000, "the answer that says starting") { engine.orbot.value == OrbotState.STARTING }
            assertEquals("127.0.0.1:9050", proxyOf(engine))
        }
    }

    @Test
    fun withoutOrbotOnTheDeviceNoAnswerIsTaken() = runBlocking {
        engine("orbot-there") { true }.use { witness ->
            engine("orbot-absent", null).use { engine ->
                for (one in listOf(witness, engine)) one.saveSettings(one.settings.value.copy(checkEveryHours = 0, proxy = ProxyMode.ORBOT))
                waitUntil(10_000, "the engine to find no Orbot") { engine.orbot.value == OrbotState.NOT_INSTALLED }

                assertEquals("127.0.0.1:9150", witness.afterAnswer("127.0.0.1", 9150))
                Thread.sleep(1_000)

                assertEquals(OrbotState.NOT_INSTALLED, engine.orbot.value)
                assertEquals("127.0.0.1:9050", proxyOf(engine))
                assertNotEquals(proxyOf(witness), proxyOf(engine))
            }
        }
    }

    @Test
    fun anAnswerDoesNothingToAnotherSetting() = runBlocking {
        engine("orbot-there") { true }.use { engine ->
            engine.saveSettings(engine.settings.value.copy(checkEveryHours = 0, proxy = ProxyMode.ORBOT))
            engine.afterAnswer("127.0.0.1", 9150)

            engine.saveSettings(engine.settings.value.copy(proxy = ProxyMode.CUSTOM, proxyHost = "127.0.0.1", proxyPort = 1080))
            assertEquals("127.0.0.1:1080", proxyOf(engine))

            engine.saveSettings(engine.settings.value.copy(proxy = ProxyMode.NONE))
            assertEquals(Proxy.Type.DIRECT, engine.proxy().type())
            assertNull(engine.proxy().address())
        }
    }
}
