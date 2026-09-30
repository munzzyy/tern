package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.testing.FakeHttp
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
    fun keepsTheFolderAnAddressNames() {
        assertEquals("https://sourceforge.net/projects/app/files/Android", source.match("https://sourceforge.net/projects/app/files/Android/")?.url)
        assertEquals("https://sourceforge.net/projects/app/files/Android/Stable%20builds", source.match("https://www.sourceforge.net/projects/app/files/Android/Stable%20builds/")?.url)
        // The address of a file stands for its folder, and the link to the newest file for the whole project.
        assertEquals("https://sourceforge.net/projects/app/files/Android", source.match("https://sourceforge.net/projects/app/files/Android/app-2.0.0.apk/download")?.url)
        assertEquals("https://sourceforge.net/projects/app", source.match("https://sourceforge.net/projects/app/files/latest/download")?.url)
    }

    @Test
    fun takesTheShortAddressOfAProject() {
        assertEquals("https://sourceforge.net/projects/app", source.match("https://sourceforge.net/p/app/")?.url)
        assertEquals("https://sourceforge.net/projects/app", source.match("https://sourceforge.net/p/app/wiki/Home/")?.url)
        assertNull(source.match("https://sourceforge.net/p/"))
        assertNull(source.match("https://sourceforge.net/directory/android/"))
    }

    @Test
    fun readsOnlyTheFilesOfTheFolderFollowed() {
        val spec = source.match("https://sourceforge.net/projects/app/files/Android/")!!
        val feed = """
            <rss><channel>
              <item><link>https://sourceforge.net/projects/app/files/Android/app-2.0.0.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/app/files/Desktop/app-3.0.0.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/app/files/Android-old/app-1.0.0.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/other/files/Android/app-4.0.0.apk/download</link></item>
            </channel></rss>
        """.trimIndent()
        val http = FakeHttp().text("https://sourceforge.net/projects/app/rss?path=/Android", feed)
        val listing = (source.check(spec, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing
        assertEquals(listOf("2.0.0"), listing.releases.map { it.version })
        assertEquals("app", listing.name)
    }

    @Test
    fun takesTheFolderForTheVersionWhenTheNameHasNone() {
        val projectUrl = "https://sourceforge.net/projects/app"
        val feed = """
            <rss><channel>
              <item><link>https://sourceforge.net/projects/app/files/v1.3.0/app-arm64.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/app/files/v1.3.0/app-universal.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/app/files/Android/1.2.0/app.apk/download</link></item>
              <item><link>https://sourceforge.net/projects/app/files/app-latest.apk/download</link></item>
            </channel></rss>
        """.trimIndent()
        val http = FakeHttp().text("$projectUrl/rss?path=/", feed)
        val listing = (source.check(SourceSpec(source.type, projectUrl), CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing
        assertEquals(listOf("1.3.0", "1.2.0"), listing.releases.map { it.version })
        assertEquals(listOf("app-arm64.apk", "app-universal.apk"), listing.releases[0].assets.map { it.name })
        // A folder without a dotted version is the version as it is; a file right in the folder followed has none.
        assertEquals("nightly/2026-09-30", SourceForgeSource.versionOf("app.apk", "nightly/2026-09-30"))
        assertEquals("2.1.0", SourceForgeSource.versionOf("app-2.1.0.apk", "1.0.0"))
        assertNull(SourceForgeSource.versionOf("app.apk", ""))
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
