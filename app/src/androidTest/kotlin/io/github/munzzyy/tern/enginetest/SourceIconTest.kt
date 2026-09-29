package io.github.munzzyy.tern.enginetest

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.engine.real.IconBitmaps
import io.github.munzzyy.tern.engine.real.Icons
import io.github.munzzyy.tern.enginetest.LoopbackServer.Companion.head
import io.github.munzzyy.tern.net.UrlConnectionHttp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Icons from the source, decoded by Android itself. The rules for what is kept and asked again are tested on the JVM. */
@RunWith(AndroidJUnit4::class)
class SourceIconTest {
    private val folder = File(targetContext.cacheDir, "icons")
    private val scratch = File(targetContext.cacheDir, "icontest")
    private val repository = "${FakeForge.BASE}/api/v1/repos/example/app"
    private val avatar = "${FakeForge.BASE}/repo-avatars/app"
    private val owner = "${FakeForge.BASE}/avatars/owner"

    @Before
    fun clean() {
        uninstallFixture()
        folder.deleteRecursively()
        scratch.deleteRecursively()
    }

    @After
    fun tidy() {
        uninstallFixture()
        folder.deleteRecursively()
        scratch.deleteRecursively()
    }

    private fun picture(name: String) = asset("icons/$name")

    private fun routes(avatarBytes: ByteArray?, type: String = "image/png", ownerBytes: ByteArray? = null): Routes {
        val forge = FakeForge()
        forge.releases = listOf(FakeForge.Release("v1", listOf(FakeForge.File("app.apk", asset("apk/app-v1.apk")))))
        val routes = Routes(forge).json(repository, """{"avatar_url":"$avatar","owner":{"avatar_url":"$owner"}}""")
        if (avatarBytes != null) routes.on(avatar) { HttpResponse.of(200, avatarBytes, Headers.of("Content-Type" to type), avatar) }
        if (ownerBytes != null) routes.on(owner) { HttpResponse.of(200, ownerBytes, Headers.of("Content-Type" to "image/png"), owner) }
        return routes
    }

    private fun Routes.to(url: String): Int = requests.count { it.url == url }

    /** Checks the fixture once and asks for its icon the way a row does. */
    private fun iconOf(name: String, routes: Routes, sizePx: Int = 96, before: (Harness) -> Unit = {}): Bitmap? = Harness(name, http = routes).use { h ->
        val id = h.addFixture()
        before(h)
        runBlocking {
            h.engine.check(id)
            h.engine.icon(h.row(id), sizePx)
        }
    }

    private fun assertFits(bitmap: Bitmap?, sizePx: Int) {
        assertNotNull(bitmap)
        assertEquals(sizePx, max(bitmap!!.width, bitmap.height))
    }

    @Test
    fun anAppThatIsNotInstalledShowsThePictureOfItsSource() {
        for ((file, type) in listOf("square.png" to "image/png", "square.jpg" to "image/jpeg", "lossy.webp" to "image/webp", "alpha.webp" to "image/webp")) {
            folder.deleteRecursively()
            val routes = routes(picture(file), type)
            assertFits(iconOf("icon-shown", routes, sizePx = 48), 48)
            assertEquals(file, 1, routes.to(avatar))
            assertEquals("the second address is not needed", 0, routes.to(owner))
        }
    }

    @Test
    fun aPictureThatIsNotSquareKeepsItsShape() {
        val bitmap = iconOf("icon-wide", routes(picture("wide.png")), sizePx = 100)
        assertEquals(100, bitmap!!.width)
        assertEquals(60, bitmap.height)
    }

    @Test
    fun aSmallPictureIsNotBlownUp() {
        val bitmap = iconOf("icon-small", routes(picture("square.png")), sizePx = 512)
        assertEquals(96, bitmap!!.width)
    }

    @Test
    fun thePictureIsAskedForOnceAndKept() {
        val routes = routes(picture("square.png"))
        Harness("icon-kept", http = routes).use { h ->
            val id = h.addFixture()
            runBlocking {
                h.engine.check(id)
                assertFits(h.engine.icon(h.row(id), 96), 96)
                assertFits(h.engine.icon(h.row(id), 40), 40)
            }
            assertEquals(listOf(avatar, owner), h.state(id).iconUrls)
            assertEquals(1, routes.to(avatar))
            assertTrue(folder.listFiles().orEmpty().any { it.name.endsWith(Icons.PICTURE) })
        }
    }

    @Test
    fun noFileThatIsNotAPictureIsShown() {
        val hostile = mapOf(
            "too large" to padded(picture("square.png"), Icons.MAX_BYTES),
            "lies about its type" to picture("picture.gif"),
            "a page" to "<!DOCTYPE html><html><body>Sign in</body></html>".toByteArray(),
            "truncated" to picture("square.png").let { it.copyOf(it.size - 40) },
            "truncated jpeg" to picture("square.jpg").let { it.copyOf(it.size / 2) },
            "five thousand by five thousand" to picture("huge.png"),
            "svg" to picture("vector.svg"),
            "empty" to ByteArray(0),
        )
        for ((what, bytes) in hostile) {
            folder.deleteRecursively()
            val routes = routes(bytes)
            assertNull(what, iconOf("icon-hostile", routes))
            assertEquals(what, 1, routes.to(avatar))
            assertTrue(what, folder.listFiles().orEmpty().none { it.name.endsWith(Icons.PICTURE) })
        }
    }

    @Test
    fun whenTheFirstAddressFailsTheSecondIsShown() {
        val routes = routes(picture("vector.svg"), ownerBytes = picture("square.png"))
        assertFits(iconOf("icon-second", routes), 96)
        assertEquals(1, routes.to(avatar))
        assertEquals(1, routes.to(owner))
    }

    @Test
    fun theDecoderRefusesWhatItIsHandedDirectly() {
        assertNull(IconBitmaps.decode(picture("huge.png"), 96))
        assertNull(IconBitmaps.decode(picture("picture.gif"), 96))
        assertNull(IconBitmaps.decode(picture("vector.svg"), 96))
        assertNull(IconBitmaps.decode(ByteArray(0), 96))
        assertNull(IconBitmaps.decode(ByteArray(4096), 96))
        assertFits(IconBitmaps.decode(picture("square.png"), 96), 96)
    }

    @Test
    fun theSettingTurnsItOff() {
        val routes = routes(picture("square.png"))
        val icon = iconOf("icon-off", routes) { h -> runBlocking { h.engine.saveSettings(h.engine.settings.value.copy(sourceIcons = false)) } }
        assertNull(icon)
        assertEquals(0, routes.to(avatar))
    }

    @Test
    fun anInstalledAppShowsItsOwnIconAndNothingIsAskedFor() {
        shellInstall(asset("apk/app-v1.apk"))
        val routes = routes(picture("square.png"))
        Harness("icon-installed", http = routes).use { h ->
            val id = h.addFixture()
            runBlocking {
                h.engine.check(id)
                assertNotNull(h.row(id).installed)
                h.engine.icon(h.row(id), 96)
            }
            assertEquals(0, routes.to(avatar))
            assertEquals(0, routes.to(owner))
        }
    }

    @Test
    fun aRowDrawnBeforeItsFirstCheckStillGetsItsPicture() {
        val routes = routes(picture("square.png"))
        Harness("icon-early", http = routes).use { h ->
            val id = h.addFixture()
            runBlocking {
                val early = async(Dispatchers.Default) { h.engine.icon(h.row(id), 96) }
                delay(300)
                h.engine.check(id)
                assertFits(early.await(), 96)
            }
        }
    }

    @Test
    fun aRedirectToPlainHttpIsNotFollowed() {
        val bytes = picture("square.png")
        LoopbackServer { request, out ->
            if (request.target == "/icon.png") {
                head(out, "302 Found", "Location" to "http://localhost:${request.header("Host")!!.substringAfter(':')}/plain.png")
            } else {
                head(out, "200 OK", "Content-Type" to "image/png", "Content-Length" to "${bytes.size}")
                out.write(bytes)
            }
        }.use { server ->
            val icons = Icons(UrlConnectionHttp(cleartextHostsForTests = setOf("127.0.0.1")), scratch)
            val shown = runBlocking { icons.load(listOf("http://127.0.0.1:${server.port}/icon.png"), mayFetch = true) { IconBitmaps.decode(it, 96) } }
            assertNull(shown)
            assertEquals(listOf("/icon.png"), server.requests.map { it.target })
        }
    }

    @Test
    fun aBodyOverTheLimitIsCutOff() {
        val bytes = padded(picture("square.png"), Icons.MAX_BYTES)
        LoopbackServer { _, out ->
            head(out, "200 OK", "Content-Type" to "image/png")
            out.write(bytes)
        }.use { server ->
            val icons = Icons(UrlConnectionHttp(cleartextHostsForTests = setOf("127.0.0.1")), scratch)
            val shown = runBlocking { icons.load(listOf("http://127.0.0.1:${server.port}/icon.png"), mayFetch = true) { IconBitmaps.decode(it, 96) } }
            assertNull(shown)
            assertTrue(scratch.listFiles().orEmpty().none { it.name.endsWith(Icons.PICTURE) })
        }
    }

    /** The same picture with [extra] bytes of text in it, which a decoder skips. */
    private fun padded(png: ByteArray, extra: Int): ByteArray {
        val name = "tEXt".toByteArray(Charsets.ISO_8859_1)
        val data = "Comment".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + ByteArray(extra) { 'x'.code.toByte() }
        val crc = CRC32().apply {
            update(name)
            update(data)
        }
        val end = png.size - 12
        val out = ByteArrayOutputStream()
        out.write(png, 0, end)
        out.write(int(data.size))
        out.write(name)
        out.write(data)
        out.write(int(crc.value.toInt()))
        out.write(png, end, 12)
        return out.toByteArray()
    }

    private fun int(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
