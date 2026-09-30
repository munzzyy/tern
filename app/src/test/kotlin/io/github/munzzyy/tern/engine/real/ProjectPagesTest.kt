package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.engine.NoteBlock
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPagesTest {
    private val asked = ArrayList<HttpRequest>()
    private val http = object : HttpClient {
        override fun execute(request: HttpRequest): HttpResponse {
            asked += request
            val body = "<p align=\"center\"><img src=\"logo.png\"></p>\n\n# Maps\n\n[![build](https://ci/badge.svg)](https://ci)\n\nOffline maps.<!-- hidden -->"
            return HttpResponse(200, Headers.of(emptyMap()), ByteArrayInputStream(body.toByteArray()), request.url)
        }
    }

    @Test
    fun theReadmeOfAGithubProjectIsReadFromItsApiAsText() {
        val blocks = ProjectPages(http, TokenProvider.NONE).read(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/maps"))!!
        assertEquals("https://api.github.com/repos/example/maps/readme", asked.single().url)
        assertTrue(blocks.first() is NoteBlock.Heading)
    }

    @Test
    fun aForgejoReadmeGoesWithTheTokenForItsHostAsAuthorization() {
        val forgejo = object : HttpClient {
            override fun execute(request: HttpRequest): HttpResponse {
                asked += request
                val body = "{\"content\":\"${java.util.Base64.getEncoder().encodeToString("# Maps".toByteArray())}\"}"
                return HttpResponse(200, Headers.of(emptyMap()), ByteArrayInputStream(body.toByteArray()), request.url)
            }
        }
        val tokens = TokenProvider { host -> if (host == "codeberg.org") "T" else null }
        val blocks = ProjectPages(forgejo, tokens).read(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/maps"))!!
        assertTrue(blocks.first() is NoteBlock.Heading)
        val request = asked.single()
        assertEquals("https://codeberg.org/api/v1/repos/example/maps/contents/README.md", request.url)
        assertEquals("token T", request.authorization)
        assertTrue(request.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun aSourceWithoutAProjectPageAsksNothing() {
        assertNull(ProjectPages(http, TokenProvider.NONE).read(SourceSpec(SourceTypes.APKPURE, "https://apkpure.com/maps/org.example.maps")))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun theHtmlAForgeWouldDrawIsTakenOut() {
        assertEquals("# Maps\n\n[build](https://ci)\n\nOffline maps.", ProjectPages.withoutHtml("<p align=\"center\"><img src=\"x\"></p>\n\n# Maps\n\n\n\n[![build](https://ci/badge.svg)](https://ci)\n\nOffline maps.<!-- a\nb -->"))
        assertEquals("a < b and c > d", ProjectPages.withoutHtml("a < b and c > d"))
    }
}
