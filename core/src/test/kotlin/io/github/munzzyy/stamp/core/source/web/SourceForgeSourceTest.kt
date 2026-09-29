package io.github.munzzyy.stamp.core.source.web

import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceForgeSourceTest {
    private val source = SourceForgeSource()

    @Test
    fun matchesProjectPath() {
        val spec = source.match("https://sourceforge.net/projects/app/files/")
        assertEquals("https://sourceforge.net/projects/app", spec?.url)
    }

    @Test
    fun doesNotMatchOtherHost() {
        assertNull(source.match("https://example.com/projects/app"))
    }

    @Test
    fun groupsFilesByVersionAndStripsDownloadSuffix() {
        val projectUrl = "https://sourceforge.net/projects/app"
        val feedUrl = "$projectUrl/rss?path=/"
        val feed = """
            <rss><channel>
              <item><link>https://sourceforge.net/projects/app/files/app-2.0.0.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/app/files/app-1.0.0.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/app/files/readme.txt/download</link></item>
            </channel></rss>
        """.trimIndent()
        val http = FakeHttp().text(feedUrl, feed)
        val result = source.check(SourceSpec(source.type, projectUrl), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(2, listing.releases.size)
        assertEquals("2.0.0", listing.releases[0].version)
        assertEquals("app-2.0.0.apk", listing.releases[0].assets[0].name)
    }
}
