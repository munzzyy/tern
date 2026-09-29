package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Detection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BareLinkTest {
    private val link = "https://dl.test/android/latest"

    @Test
    fun aDownloadButtonWithoutAFileNameIsFoundWithoutDownloadingTheFile() = runBlocking {
        val bytes = asset("apk/app-v1.apk")
        val routes = Routes(FakeForge()).file(link, bytes, listOf("Content-Type" to "application/octet-stream", "Content-Disposition" to "attachment; filename=\"Example.apk\""))
        Harness("barelink", http = routes).use { h ->
            val found = h.engine.detect(link) as Detection.Found
            assertEquals(SourceTypes.DIRECT, found.spec.type)
            assertEquals(link, found.spec.url)
            assertEquals("Example.apk", found.file?.asset?.name)
            assertEquals(PKG, found.verification?.packageName)
            val toLink = routes.requests.filter { it.url == link }
            assertEquals("HEAD", toLink.first().method)
            assertTrue(toLink.none { it.method == "GET" && it.headers["Range"] == null })
        }
    }

    @Test
    fun anOrdinaryPageStillGoesToThePageReader() = runBlocking {
        val page = "https://dl.test/downloads"
        val html = "<html><body><a href=\"https://dl.test/files/app-1.0.apk\">Get it</a></body></html>"
        val routes = Routes(FakeForge()).on(page) { io.github.munzzyy.tern.core.net.HttpResponse.of(200, html, io.github.munzzyy.tern.core.net.Headers.of("Content-Type" to "text/html"), page) }
        Harness("barepage", http = routes).use { h ->
            val result = h.engine.detect(page)
            val type = (result as? Detection.Found)?.spec?.type
            assertTrue("detected as $result", type == null || type == SourceTypes.HTML)
        }
    }
}
