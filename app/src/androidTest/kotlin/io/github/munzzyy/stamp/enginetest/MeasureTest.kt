package io.github.munzzyy.stamp.enginetest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.HttpClient
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.HttpResponse
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceOptions
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.engine.Detection
import io.github.munzzyy.stamp.engine.OrbotState
import io.github.munzzyy.stamp.engine.ProxyMode
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.engine.real.RealEngine
import io.github.munzzyy.stamp.net.ProxyChoice
import io.github.munzzyy.stamp.net.ProxyDoor
import io.github.munzzyy.stamp.net.UrlConnectionHttp
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measurements that reach outside the emulator. They run only when asked for with the instrumentation
 * argument engineMeasure (dns or network); otherwise they are skipped.
 */
@RunWith(AndroidJUnit4::class)
class MeasureTest {
    private val args = InstrumentationRegistry.getArguments()

    /**
     * With a real Orbot on the device and the proxy set to Orbot: what Orbot answers, which port it
     * names, whether the Tor project's own page sees the request come out of Tor, and whether a
     * project can be read that way. Without Orbot running it shows that nothing is reached.
     */
    @Test
    fun throughARealOrbot() = runBlocking<Unit> {
        assumeTrue(args.getString("engineMeasure") == "orbot")
        val store = "enginetest-orbot.db"
        targetContext.deleteDatabase(store)
        val door = ProxyDoor()
        val engine = RealEngine(targetContext, UrlConnectionHttp(proxy = door::proxy, connectTimeoutMs = 30_000, readTimeoutMs = 60_000), storeName = store, prefsPrefix = "enginetest-orbot-")
        door.follow(engine::proxy)
        try {
            engine.ready()
            engine.saveSettings(engine.settings.value.copy(proxy = ProxyMode.ORBOT))
            engine.askOrbot()
            val waitMs = args.getString("orbotWaitMs")?.toLong() ?: 120_000
            val end = System.currentTimeMillis() + waitMs
            var last: OrbotState? = null
            while (System.currentTimeMillis() < end && engine.orbot.value != OrbotState.ON) {
                if (engine.orbot.value != last) {
                    last = engine.orbot.value
                    Log.i(TAG, "Orbot says: $last")
                }
                delay(1_000)
            }
            Log.i(TAG, "Orbot says: ${engine.orbot.value}, proxy in use: ${engine.proxy()}")
            for (url in listOf("https://check.torproject.org/api/ip", "https://codeberg.org/api/v1/version")) {
                val outcome = try {
                    engine.http.execute(HttpRequest(url)).use { "HTTP ${it.status} ${it.text().take(200)}" }
                } catch (e: IOException) {
                    "${e.javaClass.simpleName}: ${e.message}"
                }
                Log.i(TAG, "with the proxy on Orbot, $url -> $outcome")
            }
            val url = args.getString("detectUrl") ?: "https://codeberg.org/forgejo/forgejo"
            val found = engine.detect(url)
            Log.i(TAG, "with the proxy on Orbot, detect $url -> ${found.javaClass.simpleName} ${(found as? Detection.Failed)?.problem ?: ""}")
        } finally {
            engine.close()
            targetContext.deleteDatabase(store)
        }
    }

    @Test
    fun namesGoToTheSocksProxyUnresolved() {
        assumeTrue(args.getString("engineMeasure") == "dns")
        val port = args.getString("socksPort")?.toInt() ?: 19050
        val settings = Settings(proxy = ProxyMode.CUSTOM, proxyHost = "10.0.2.2", proxyPort = port)
        val http = UrlConnectionHttp(proxy = { ProxyChoice.of(settings) }, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
        for (url in listOf("https://github.com/", "https://stamp-dns-probe.invalid/")) {
            val outcome = try {
                http.execute(HttpRequest(url)).use { "HTTP ${it.status}" }
            } catch (e: IOException) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, "through SOCKS 10.0.2.2:$port, $url -> $outcome")
        }
    }

    @Test
    fun detectAProjectOnTheRealNetwork() = runBlocking<Unit> {
        assumeTrue(args.getString("engineMeasure") == "network")
        val store = "enginetest-network.db"
        targetContext.deleteDatabase(store)
        val engine = RealEngine(targetContext, UrlConnectionHttp(), storeName = store, prefsPrefix = "enginetest-network-")
        try {
            val url = args.getString("detectUrl") ?: "https://github.com/munzzyy/magpie"
            val found = engine.detect(url)
            Log.i(TAG, "detect $url -> ${found.javaClass.simpleName}")
            if (found is Detection.Found) {
                Log.i(TAG, "source: ${found.spec.type} ${found.spec.url}")
                Log.i(TAG, "offered release: ${found.release?.id} version ${found.release?.version}")
                Log.i(TAG, "chosen file: ${found.file?.asset?.name} ${found.file?.asset?.size} bytes, reasons ${found.file?.reasons}")
                Log.i(TAG, "other files: ${found.otherFiles.map { it.asset.name }}")
                val cost = engine.inspector.lastCost
                Log.i(TAG, "remote inspection: ${cost?.requests} requests, ${cost?.bytes} bytes for ${cost?.url}")
                val facts = found.file?.asset?.let { engine.inspector.cached(it, found.release!!.id) }
                Log.i(TAG, "read: package ${facts?.packageName}, version code ${facts?.versionCode}, version ${facts?.versionName}, certificate ${facts?.signers}")
                Log.i(TAG, "verification: ${found.verification}")
                Log.i(TAG, "warnings: ${found.warnings}")
            } else {
                Log.i(TAG, "result: $found")
            }
        } finally {
            engine.close()
            targetContext.deleteDatabase(store)
        }
    }

    @Test
    fun readTheFDroidRepositoryOnTheDevice() = runBlocking<Unit> {
        assumeTrue(args.getString("engineMeasure") == "fdroid")
        val store = "enginetest-fdroid.db"
        targetContext.deleteDatabase(store)
        val counting = CountingHttp(UrlConnectionHttp())
        val engine = RealEngine(targetContext, counting, storeName = store, prefsPrefix = "enginetest-fdroid-")
        try {
            engine.ready()
            val spec = SourceSpec(
                SourceTypes.FDROID_REPO, "https://f-droid.org/repo",
                mapOf(SourceOptions.PACKAGE to "org.fdroid.fdroid", SourceOptions.FINGERPRINT to F_DROID_FINGERPRINT),
            )
            repeat(2) { round ->
                val before = counting.bytes.get()
                val requests = counting.requests.get()
                val start = System.nanoTime()
                val outcome = try {
                    when (val result = engine.registry.check(spec, CheckContext(engine.http, engine.store, engine.tokens, engine.nowMs, engine.device.profile))) {
                        is CheckResult.Listing -> "listing: ${result.listing.releases.size} releases, newest ${result.listing.releases.firstOrNull()?.version} code ${result.listing.releases.firstOrNull()?.versionCode}, first file sha256 ${result.listing.releases.firstOrNull()?.assets?.firstOrNull()?.sha256}"
                        CheckResult.Unchanged -> "unchanged"
                    }
                } catch (e: SourceException) {
                    "SourceException ${e.kind}: ${e.message} (${e.cause?.javaClass?.simpleName}: ${e.cause?.message})"
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                Log.i(TAG, "F-Droid round ${round + 1}: $outcome; ${counting.requests.get() - requests} requests, ${counting.bytes.get() - before} body bytes after decoding, $ms ms")
            }
        } finally {
            engine.close()
            targetContext.deleteDatabase(store)
        }
    }

    /** Counts requests and the bytes the caller read from response bodies. */
    private class CountingHttp(private val real: HttpClient) : HttpClient {
        val requests = AtomicInteger()
        val bytes = AtomicLong()

        override fun execute(request: HttpRequest): HttpResponse {
            requests.incrementAndGet()
            Log.i(TAG, "request ${request.method} ${request.url.substringBefore('?')}")
            val response = real.execute(request)
            val counted = object : FilterInputStream(response.body) {
                override fun read(): Int = super.read().also { if (it >= 0) bytes.incrementAndGet() }

                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytes.addAndGet(it.toLong()) }
            }
            return HttpResponse(response.status, response.headers, counted, response.url)
        }
    }

    private companion object {
        const val TAG = "EngineMeasure"
        const val F_DROID_FINGERPRINT = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
    }
}
