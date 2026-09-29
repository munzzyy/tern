package io.github.munzzyy.jackdaw.enginetest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpClient
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import java.io.FilterInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.engine.ProxyMode
import io.github.munzzyy.jackdaw.engine.Settings
import io.github.munzzyy.jackdaw.engine.real.RealEngine
import io.github.munzzyy.jackdaw.net.ProxyChoice
import io.github.munzzyy.jackdaw.net.UrlConnectionHttp
import java.io.IOException
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

    @Test
    fun namesGoToTheSocksProxyUnresolved() {
        assumeTrue(args.getString("engineMeasure") == "dns")
        val port = args.getString("socksPort")?.toInt() ?: 19050
        val settings = Settings(proxy = ProxyMode.CUSTOM, proxyHost = "10.0.2.2", proxyPort = port)
        val http = UrlConnectionHttp(proxy = { ProxyChoice.of(settings) }, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
        for (url in listOf("https://github.com/", "https://jackdaw-dns-probe.invalid/")) {
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
