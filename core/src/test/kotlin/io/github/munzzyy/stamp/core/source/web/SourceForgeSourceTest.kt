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

    private fun files(vararg links: String): List<String> {
        val projectUrl = "https://sourceforge.net/projects/app"
        val feed = "<rss><channel>" + links.joinToString("") { "<item><link>$it</link></item>" } + "</channel></rss>"
        val http = FakeHttp().text("$projectUrl/rss?path=/", feed)
        val result = source.check(SourceSpec(source.type, projectUrl), CheckContext(http, InMemoryValidatorStore()))
        return (result as CheckResult.Listing).listing.releases.flatMap { release -> release.assets.map { it.url } }
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        assertEquals(
            listOf("https://sourceforge.net/projects/app/files/app-2.0.0.apk"),
            files(
                "https://sourceforge.net/projects/app/files/app-2.0.0.apk/download",
                "https://files.example.org/app-3.0.0.apk/download",
                "https://sourceforge.net.example.org/projects/app/files/app-4.0.0.apk/download",
                "https://user@sourceforge.net/projects/app/files/app-5.0.0.apk/download",
            ),
        )
    }

    @Test
    fun aFileThatIsNotServedOverHttpsIsLeftOut() {
        assertEquals(
            listOf("https://sourceforge.net/projects/app/files/app-2.0.0.apk"),
            files(
                "http://sourceforge.net/projects/app/files/app-3.0.0.apk/download",
                "file:///data/app-4.0.0.apk",
                "https://sourceforge.net/projects/app/files/app-2.0.0.apk/download",
            ),
        )
    }
}
