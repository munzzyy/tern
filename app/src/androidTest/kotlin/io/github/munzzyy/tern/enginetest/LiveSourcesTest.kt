package io.github.munzzyy.tern.enginetest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.real.RealEngine
import io.github.munzzyy.tern.net.UrlConnectionHttp
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** One real app from each source, through the real engine on the network. Off unless run with -e live true. */
@RunWith(AndroidJUnit4::class)
class LiveSourcesTest {
    private val args = InstrumentationRegistry.getArguments()
    private val counting = RecordingHttp(UrlConnectionHttp(connectTimeoutMs = 30_000, readTimeoutMs = 60_000))
    private lateinit var engine: RealEngine

    @Before
    fun onlyWhenAsked() {
        assumeTrue("live tests are off; pass -e live true", args.getString("live") == "true")
        targetContext.deleteDatabase(STORE)
        engine = RealEngine(targetContext, counting, storeName = STORE, prefsPrefix = PREFS)
        runBlocking { engine.ready() }
    }

    @After
    fun close() {
        if (::engine.isInitialized) engine.close()
        targetContext.deleteDatabase(STORE)
        for (name in listOf("settings", "kept-pins")) targetContext.getSharedPreferences(PREFS + name, 0).edit().clear().commit()
    }

    private fun stores(on: Boolean) = runBlocking { engine.saveSettings(engine.settings.value.copy(thirdPartyStores = on)) }

    /** A listing has to come back; after it only no file for this device, or a region's refusal where [regional], is let pass. */
    private fun follow(type: String, address: String, regional: Boolean = false) = runBlocking {
        val found = engine.detect(address)
        val line = when (found) {
            is Detection.Found -> {
                assertEquals(address, type, found.spec.type)
                val id = engine.add(found, install = false)
                engine.check(id)
                val row = engine.apps.value.first { it.id == id }
                val said = "listing: ${found.name}, newest ${row.latest?.version ?: found.release?.version}, file ${row.file?.asset?.name ?: found.file?.asset?.name}, " +
                    "package ${row.config.packageName}, status ${row.status}, problem ${row.problem?.kind} ${row.problem?.message ?: ""}"
                val kind = row.problem?.kind
                val allowed = setOf(ProblemKind.NO_FILE_FOR_DEVICE) + if (regional) setOf(ProblemKind.NETWORK, ProblemKind.NOT_FOUND) else emptySet()
                assertTrue("$type: $said", kind == null || kind in allowed)
                said
            }
            is Detection.Failed -> {
                val said = "problem: ${found.problem.kind} ${found.problem.message}, read as ${found.spec?.type}"
                if (!regional || found.problem.kind !in setOf(ProblemKind.NETWORK, ProblemKind.NOT_FOUND)) fail("$type: $said")
                said
            }
            else -> fail("$type: $found").let { "" }
        }
        Log.i(TAG, "$type $address -> $line; ${counting.requests.size} requests to ${counting.hosts()}")
        identity(type)
    }

    /** Every request carried Tern's own User-Agent and nothing that names another client. */
    private fun identity(type: String) {
        for (request in counting.requests.toList()) {
            val agent = request.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            assertTrue("$type sent ${request.url.take(120)} as $agent", agent?.startsWith("Tern/") == true)
            assertTrue("$type: ${request.headers.keys}", request.headers.keys.none { it.startsWith("X-App", ignoreCase = true) || it.startsWith("Ual-", ignoreCase = true) })
        }
    }

    @Test
    fun gitHub() = follow(SourceTypes.GITHUB, "https://github.com/munzzyy/magpie")

    @Test
    fun gitLab() = follow(SourceTypes.GITLAB, "https://gitlab.com/fdroid/fdroidclient")

    @Test
    fun codeberg() = follow(SourceTypes.FORGEJO, "https://codeberg.org/Starfish/TinyWeatherForecastGermany")

    @Test
    fun fDroid() = follow(SourceTypes.FDROID, "https://f-droid.org/en/packages/org.fdroid.fdroid/")

    @Test
    fun izzyOnDroid() = follow(SourceTypes.FDROID, "https://apt.izzysoft.de/fdroid/index/apk/io.github.muntashirakon.AppManager")

    @Test
    fun webPage() = follow(SourceTypes.HTML, "https://download.kiwix.org/release/kiwix-android/")

    @Test
    fun sourceForge() = follow(SourceTypes.SOURCEFORGE, "https://sourceforge.net/projects/newpipe.mirror/")

    @Test
    fun itchIo() = follow(SourceTypes.ITCHIO, "https://xlaucifer.itch.io/remember")

    @Test
    fun telegram() = follow(SourceTypes.TELEGRAM, "https://telegram.org/android")

    @Test
    fun neutronCode() = follow(SourceTypes.NEUTRONCODE, "https://neutroncode.com/downloads/file/2-neutronmpapkarm64")

    @Test
    fun apkPure() {
        stores(true)
        follow(SourceTypes.APKPURE, "https://apkpure.com/vlc-for-android/org.videolan.vlc", regional = true)
    }

    @Test
    fun aptoide() {
        stores(true)
        follow(SourceTypes.APTOIDE, "https://videolabs-vlc.en.aptoide.com/app", regional = true)
    }

    @Test
    fun apkCombo() {
        stores(true)
        follow(SourceTypes.APKCOMBO, "https://apkcombo.com/vlc/org.videolan.vlc", regional = true)
    }

    @Test
    fun apkMirror() {
        stores(true)
        follow(SourceTypes.APKMIRROR, "https://www.apkmirror.com/apk/mozilla/firefox/", regional = true)
    }

    @Test
    fun tencent() {
        stores(true)
        follow(SourceTypes.TENCENT, "https://sj.qq.com/appdetail/com.tencent.mm", regional = true)
    }

    @Test
    fun huaweiAppGallery() {
        stores(true)
        follow(SourceTypes.HUAWEI, "https://appgallery.huawei.com/app/C100336075", regional = true)
    }

    @Test
    fun galaxyStore() {
        stores(true)
        follow(SourceTypes.SAMSUNG, "https://galaxystore.samsung.com/detail/com.sec.android.app.sbrowser", regional = true)
    }

    @Test
    fun vivo() {
        stores(true)
        follow(SourceTypes.VIVO, "https://h5.appstore.vivo.com.cn/#/details?appId=40413", regional = true)
    }

    @Test
    fun refusedSitesAreNeverAsked() = runBlocking {
        stores(true)
        for (address in listOf("https://liteapks.com/spotify-music.html", "https://vlc.en.uptodown.com/android")) {
            counting.requests.clear()
            val found = engine.detect(address)
            Log.i(TAG, "$address -> $found; ${counting.requests.size} requests")
            assertTrue("$address -> $found", found is Detection.Failed && found.problem.kind == ProblemKind.UNSUPPORTED)
            assertEquals("requests for $address: ${counting.hosts()}", 0, counting.requests.size)
        }
    }

    @Test
    fun aStoreWhileStoresAreOffIsNeverAsked() = runBlocking {
        stores(false)
        counting.requests.clear()
        val found = engine.detect("https://apkpure.com/vlc-for-android/org.videolan.vlc")
        Log.i(TAG, "APKPure with the stores off -> $found; ${counting.requests.size} requests")
        assertEquals(Detection.StoresOff(SourceTypes.APKPURE), found)
        assertEquals("requests: ${counting.hosts()}", 0, counting.requests.size)
    }

    /** Keeps every request that reached the network, after Tern added its own headers. */
    private class RecordingHttp(private val real: HttpClient) : HttpClient {
        val requests: MutableList<HttpRequest> = Collections.synchronizedList(ArrayList())

        fun hosts(): List<String> = requests.toList().map { Urls.host(it.url) }.distinct()

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return real.execute(request)
        }
    }

    private companion object {
        const val TAG = "LiveSources"
        const val STORE = "enginetest-live.db"
        const val PREFS = "enginetest-live-"
    }
}
