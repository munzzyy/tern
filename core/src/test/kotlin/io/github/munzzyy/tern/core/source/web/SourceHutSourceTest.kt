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
    fun aRefsAddressNamesTheRepository() {
        assertEquals("https://git.sr.ht/~user/repo", source.match("https://git.sr.ht/~user/repo/refs")?.url)
    }

    @Test
    fun readsTheSixNewestRefsWithTheirDatesAndCollectsArtifacts() {
        val repoUrl = "https://git.sr.ht/~user/repo"
        val feedUrl = "$repoUrl/refs/rss.xml"
        val items = (7 downTo 1).joinToString("") { n ->
            "<item><title>v1.$n.0</title><link>https://git.sr.ht/~user/repo/refs/v1.$n.0</link><author>~user</author><pubDate>0$n Sep 2026 10:00:00 +0000</pubDate></item>"
        }
        val http = FakeHttp().text(feedUrl, "<rss><channel>$items<item><title>elsewhere</title><link>https://example.com/refs/x</link></item></channel></rss>")
        http.text("https://git.sr.ht/~user/repo/refs/v1.7.0", "<html><body><a href=\"/dl/app-1.7.0.apk\">apk</a></body></html>")
        http.text("https://git.sr.ht/~user/repo/refs/v1.6.0", "<html><body>no artifacts</body></html>")

        val result = source.check(SourceSpec(source.type, repoUrl), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(listOf("v1.7.0", "v1.6.0", "v1.5.0", "v1.4.0", "v1.3.0", "v1.2.0"), listing.releases.map { it.id })
        assertTrue(listing.releases[0].assets[0].url.endsWith("app-1.7.0.apk"))
        assertTrue(listing.releases[1].assets.isEmpty())
        assertEquals(java.time.Instant.parse("2026-09-07T10:00:00Z").toEpochMilli(), listing.releases[0].publishedAtMs)
        assertEquals("~user", listing.author)
        assertEquals("repo", listing.name)
        assertTrue(http.requests.none { it.url.startsWith("https://example.com") })
    }
}
