package io.github.munzzyy.jackdaw.enginetest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.munzzyy.jackdaw.core.net.HttpRequest
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

    private companion object {
        const val TAG = "EngineMeasure"
    }
}
