package io.github.munzzyy.tern.enginetest

import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.RunStop
import io.github.munzzyy.tern.enginetest.LoopbackServer.Companion.head
import io.github.munzzyy.tern.install.Downloader
import io.github.munzzyy.tern.install.Installer
import io.github.munzzyy.tern.engine.real.Texts
import io.github.munzzyy.tern.net.ProxyDoor
import io.github.munzzyy.tern.net.ProxyProbe
import io.github.munzzyy.tern.net.UrlConnectionHttp
import io.github.munzzyy.tern.work.Scheduler
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A proxy that is set is never gone round. The engine runs over its real transport here, and the
 * forge stands behind a socket of this device, so a request that went direct would arrive and be
 * counted. Every test also makes the same request without a proxy and sees it arrive, because on
 * a device that is offline nothing arrives whatever the proxy is.
 */
@RunWith(AndroidJUnit4::class)
class ProxyTest {
    private val icons = File(targetContext.cacheDir, "icons")
    private val repository = "${FakeForge.BASE}/api/v1/repos/example/app"
    private val avatar = "${FakeForge.BASE}/repo-avatars/app"
    private val file = "${FakeForge.FILES}v1.0/app-v1.apk"

    @Before
    fun setUp() {
        uninstallFixture()
        icons.deleteRecursively()
    }

    @After
    fun tearDown() {
        uninstallFixture()
        icons.deleteRecursively()
    }

    /** The forge behind a real socket, and the transport the app ships with in front of it. */
    private class Wire : Closeable {
        val forge = FakeForge()
        val routes = Routes(forge)
        val door = ProxyDoor()
        private val server = LoopbackServer { request, out -> answer(request, out) }
        private val local = "http://127.0.0.1:${server.port}"
        private val real = UrlConnectionHttp(door::proxy, cleartextHostsForTests = setOf("127.0.0.1"), connectTimeoutMs = 5_000, readTimeoutMs = 10_000, proxyAnswers = ProxyProbe::answers)

        /** Sends what is meant for the forge to the socket on this device. Nothing else about the request changes. */
        val http = HttpClient { request ->
            val response = real.execute(request.copy(url = request.url.replaceFirst(FakeForge.BASE, local)))
            HttpResponse(response.status, response.headers, response.body, response.url.replaceFirst(local, FakeForge.BASE))
        }

        /** What arrived, counted at the socket and again at the forge behind it. */
        val arrived: Int get() = server.requests.size + routes.requests.size

        fun arrivedAt(url: String): Int = routes.requests.count { it.url == url }

        fun forget() {
            server.requests.clear()
            routes.requests.clear()
            forge.requests.clear()
        }

        private fun answer(request: LoopbackServer.Request, out: OutputStream) {
            val headers = request.headers.mapKeys { (name, _) -> KNOWN.firstOrNull { it.equals(name, ignoreCase = true) } ?: name }
            val response = routes.execute(HttpRequest(FakeForge.BASE + request.target, request.method, headers))
            val body = response.bytes()
            val sent = response.headers.toList().filterNot { it.first.equals("Connection", ignoreCase = true) }.toMutableList()
            if (sent.none { it.first.equals("Content-Length", ignoreCase = true) }) sent += "Content-Length" to body.size.toString()
            head(out, "${response.status} Answer", *sent.toTypedArray())
            if (request.method != "HEAD" && response.status != 304) out.write(body)
        }

        override fun close() = server.close()

        private companion object {
            val KNOWN = listOf("Range", "If-Range", "If-None-Match", "Accept-Encoding", "Authorization", "Accept")
        }
    }

    /** A SOCKS endpoint that writes down what it is asked to connect to, and refuses. */
    private class SocksWitness : Closeable {
        class Asked(val kind: Int, val host: String, val port: Int) {
            override fun toString() = "kind $kind, $host, port $port"
        }

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val asked = CopyOnWriteArrayList<Asked>()

        init {
            thread(isDaemon = true, name = "socks-witness") {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) = client.use {
            try {
                it.soTimeout = 5_000
                val input = DataInputStream(it.getInputStream())
                val out = it.getOutputStream()
                if (input.readUnsignedByte() != VERSION) return
                input.readFully(ByteArray(input.readUnsignedByte()))
                out.write(byteArrayOf(VERSION.toByte(), 0))
                out.flush()
                if (input.readUnsignedByte() != VERSION) return
                input.readUnsignedByte()
                input.readUnsignedByte()
                val kind = input.readUnsignedByte()
                val host = when (kind) {
                    IPV4 -> ByteArray(4).also(input::readFully).joinToString(".") { b -> (b.toInt() and 0xFF).toString() }
                    NAME -> String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.US_ASCII)
                    IPV6 -> ByteArray(16).also(input::readFully).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
                    else -> "?"
                }
                asked += Asked(kind, host, input.readUnsignedShort())
                out.write(byteArrayOf(VERSION.toByte(), REFUSED, 0, IPV4.toByte(), 0, 0, 0, 0, 0, 0))
                out.flush()
            } catch (_: IOException) {
            }
        }

        override fun close() = socket.close()

        companion object {
            const val VERSION = 5
            const val IPV4 = 1
            const val NAME = 3
            const val IPV6 = 4
            const val REFUSED: Byte = 5
        }
    }

    /** Takes sessions and never hands them to Android, so a download that arrives ends before the system installer. */
    private object NeverCommits : Installer {
        override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean) = 4243

        override fun commit(appId: String, sessionId: Int) = Unit

        override fun abandon(sessionId: Int) = Unit

        override fun liveSessionIds(): Set<Int> = setOf(4243)

        override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long) = 0
    }

    private fun nobodyListens(): Int = ServerSocket(0).use { it.localPort }

    private fun Harness.throughProxyAt(port: Int) = runBlocking {
        engine.saveSettings(engine.settings.value.copy(proxy = ProxyMode.CUSTOM, proxyHost = "127.0.0.1", proxyPort = port))
    }

    private fun Harness.direct() = runBlocking { engine.saveSettings(engine.settings.value.copy(proxy = ProxyMode.NONE)) }

    private fun Harness.assumeOnline() =
        Assume.assumeTrue("this device is offline, and then no request leaves whatever the proxy is", engine.online.value)

    private fun v1() = FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk"))))

    @Test
    fun aCheckThroughAProxyThatDoesNotAnswerFailsAndReachesNobody() = runBlocking {
        Wire().use { wire ->
            Harness("proxy-check", http = wire.http).use { h ->
                wire.door.follow(h.engine::proxy)
                h.assumeOnline()
                wire.forge.releases = listOf(v1())
                val id = h.addFixture()

                h.throughProxyAt(nobodyListens())
                h.engine.check(id)
                assertEquals(h.describe(id), AppStatus.ERROR, h.row(id).status)
                assertEquals(h.describe(id), ProblemKind.NETWORK, h.row(id).problem?.kind)
                assertEquals(h.describe(id), Texts(targetContext).proxySilent(), h.row(id).problem?.message)
                assertEquals("requests arrived although the proxy did not answer", 0, wire.arrived)

                h.direct()
                h.engine.check(id)
                assertEquals(h.describe(id), AppStatus.NOT_INSTALLED, h.row(id).status)
                assertTrue("without a proxy the same check has to arrive", wire.arrived > 0)
            }
        }
    }

    @Test
    fun anIconThroughAProxyThatDoesNotAnswerIsNotFetched() = runBlocking {
        Wire().use { wire ->
            wire.routes.json(repository, """{"avatar_url":"$avatar"}""")
            val picture = asset("icons/square.png")
            wire.routes.on(avatar) { HttpResponse.of(200, picture, Headers.of("Content-Type" to "image/png"), avatar) }
            Harness("proxy-icon", http = wire.http).use { h ->
                wire.door.follow(h.engine::proxy)
                h.assumeOnline()
                wire.forge.releases = listOf(v1())
                val id = h.addFixture()
                h.engine.check(id)
                assertEquals(h.describe(id), listOf(avatar), h.state(id).iconUrls)

                h.throughProxyAt(nobodyListens())
                wire.forget()
                assertNull(h.engine.icon(h.row(id), 96))
                assertEquals("requests arrived although the proxy did not answer", 0, wire.arrived)

                h.direct()
                icons.deleteRecursively()
                assertNotNull("without a proxy the same icon has to arrive", h.engine.icon(h.row(id), 96))
                assertEquals(1, wire.arrivedAt(avatar))
            }
        }
    }

    @Test
    fun aDownloadThroughAProxyThatDoesNotAnswerFailsAndReachesNobody() = runBlocking {
        Wire().use { wire ->
            Harness("proxy-download", installer = NeverCommits, http = wire.http).use { h ->
                wire.door.follow(h.engine::proxy)
                h.assumeOnline()
                wire.forge.releases = listOf(v1())
                val id = h.addFixture()
                h.engine.check(id)
                assertEquals(h.describe(id), file, h.row(id).file?.asset?.url)

                h.throughProxyAt(nobodyListens())
                wire.forget()
                h.engine.install(id)
                waitUntil(30_000, "the download to give up") { h.state(id).installProblem != null && h.row(id).progress == null }
                assertEquals(h.describe(id), ProblemKind.NETWORK, h.state(id).installProblem?.kind)
                assertEquals(h.describe(id), Texts(targetContext).proxySilent(), h.state(id).installProblem?.message)
                assertEquals(h.describe(id), ProblemKind.NETWORK, h.row(id).problem?.kind)
                assertEquals("requests arrived although the proxy did not answer", 0, wire.arrived)
                assertNull(h.engine.downloader.kept(id, Downloader.key("v1.0", file)))
                assertTrue(h.eventsFor(id).none { it.kind == EventKind.DOWNLOADED })
                assertEquals(0, h.installer.prepared.get())

                h.direct()
                h.engine.install(id)
                waitUntil(30_000, "the same download to arrive without a proxy") { h.eventsFor(id).any { it.kind == EventKind.DOWNLOADED } }
                assertTrue(wire.arrivedAt(file) > 0)
            }
        }
    }

    @Test
    fun theNameOfTheHostIsHandedToTheProxyAndNotLookedUpHere() = runBlocking {
        SocksWitness().use { witness ->
            val door = ProxyDoor()
            Harness("proxy-name", http = UrlConnectionHttp(door::proxy, connectTimeoutMs = 5_000, readTimeoutMs = 10_000)).use { h ->
                door.follow(h.engine::proxy)
                h.assumeOnline()
                val id = h.addFixture()

                h.throughProxyAt(witness.port)
                h.engine.check(id)

                assertEquals(h.describe(id), ProblemKind.NETWORK, h.row(id).problem?.kind)
                val asked = witness.asked.toList()
                assertTrue("the proxy was never asked, so the name was looked up on this device or the request went elsewhere", asked.isNotEmpty())
                for (one in asked) {
                    assertEquals("the proxy was handed an address and not a name: $one", SocksWitness.NAME, one.kind)
                    assertEquals("forge.test", one.host)
                    assertEquals(443, one.port)
                }
            }
        }
    }

    @Test
    fun aBackgroundRunThroughASilentProxyStopsOnceAndMarksNoApp() = runBlocking {
        val scheduler = targetContext.getSystemService(JobScheduler::class.java)
        try {
            Harness("proxy-gate").use { h ->
                h.assumeOnline()
                h.forge.releases = listOf(v1())
                val id = h.addFixture()
                h.engine.saveSettings(h.engine.settings.value.copy(checkEveryMinutes = 360))
                // A periodic job is due as soon as it is set, and a run of its own would add to the counts below.
                scheduler.cancel(Scheduler.JOB_ID)
                scheduler.cancel(Scheduler.RETRY_JOB_ID)

                h.throughProxyAt(nobodyListens())
                val failedBefore = h.engine.events.value.count { it.kind == EventKind.CHECK_FAILED }
                h.engine.runScheduledCheck()
                assertEquals(Texts(targetContext).proxySilent(), h.engine.lastRunProblem.value?.message)
                assertNull(h.describe(id), h.state(id).checkProblem)
                assertEquals("requests reached the forge although the proxy did not answer", 0, h.forge.requests.size)
                assertEquals("the run says so once", failedBefore + 1, h.engine.events.value.count { it.kind == EventKind.CHECK_FAILED })
                assertNotNull("the run is tried again later", scheduler.getPendingJob(Scheduler.RETRY_JOB_ID))
                assertEquals("settings says why the run reached nothing", RunStop.PROXY_SILENT, h.engine.background().lastRunStopped)

                SocksWitness().use { witness ->
                    h.throughProxyAt(witness.port)
                    h.engine.runScheduledCheck()
                    assertNull(h.engine.lastRunProblem.value)
                    assertTrue("a proxy that answers lets the same run through", h.forge.requests.isNotEmpty())
                    assertNull("a run that went through takes the reason away", h.engine.background().lastRunStopped)
                }
            }
        } finally {
            scheduler.cancel(Scheduler.RETRY_JOB_ID)
            scheduler.cancel(Scheduler.JOB_ID)
        }
    }
}
