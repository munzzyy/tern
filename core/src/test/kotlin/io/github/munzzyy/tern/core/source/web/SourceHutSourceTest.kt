package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHutSourceTest {
    private val source = SourceHutSource()

    @Test
    fun matchesRepoPath() {
        val spec = source.match("https://git.sr.ht/~user/repo")
        assertEquals("https://git.sr.ht/~user/repo", spec?.url)
    }

    @Test
    fun doesNotMatchWrongHost() {
        assertNull(source.match("https://example.com/~user/repo"))
    }

    @Test
    fun readsThreeNewestRefsAndCollectsArtifacts() {
        val repoUrl = "https://git.sr.ht/~user/repo"
        val feedUrl = "$repoUrl/refs/rss.xml"
        val feed = """
            <rss><channel>
              <item><title>v1.2.0</title><link>https://git.sr.ht/~user/repo/refs/v1.2.0</link></item>
              <item><title>v1.1.0</title><link>https://git.sr.ht/~user/repo/refs/v1.1.0</link></item>
              <item><title>v1.0.0</title><link>https://git.sr.ht/~user/repo/refs/v1.0.0</link></item>
              <item><title>v0.9.0</title><link>https://git.sr.ht/~user/repo/refs/v0.9.0</link></item>
            </channel></rss>
        """.trimIndent()
        val http = FakeHttp().text(feedUrl, feed)
        http.text("https://git.sr.ht/~user/repo/refs/v1.2.0", "<html><body><a href=\"/dl/app-1.2.0.apk\">apk</a></body></html>")
        http.text("https://git.sr.ht/~user/repo/refs/v1.1.0", "<html><body>no artifacts</body></html>")
        http.text("https://git.sr.ht/~user/repo/refs/v1.0.0", "<html><body><a href=\"/dl/app-1.0.0.apk\">apk</a></body></html>")

        val result = source.check(SourceSpec(source.type, repoUrl), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(3, listing.releases.size)
        assertEquals("v1.2.0", listing.releases[0].id)
        assertTrue(listing.releases[0].assets[0].url.endsWith("app-1.2.0.apk"))
        assertTrue(listing.releases[1].assets.isEmpty())
    }
}
