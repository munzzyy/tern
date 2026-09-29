package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class JenkinsSourceTest {
    private val source = JenkinsSource()

    @Test
    fun matchesJobPath() {
        val spec = source.match("https://ci.example.com/job/app/job/main")
        assertEquals("https://ci.example.com/job/app/job/main", spec?.url)
    }

    @Test
    fun doesNotMatchWithoutJobSegment() {
        assertNull(source.match("https://ci.example.com/view/app"))
    }

    @Test
    fun parsesLastSuccessfulBuild() {
        val jobUrl = "https://ci.example.com/job/app"
        val apiUrl = "$jobUrl/lastSuccessfulBuild/api/json"
        val body = """
            {"number": 42, "timestamp": 1700000000000,
             "artifacts": [{"fileName": "app.apk", "relativePath": "build/outputs/app.apk"}]}
        """.trimIndent()
        val http = FakeHttp().text(apiUrl, body)
        val result = source.check(SourceSpec(source.type, jobUrl), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("42", listing.releases[0].id)
        assertEquals("$jobUrl/lastSuccessfulBuild/artifact/build/outputs/app.apk", listing.releases[0].assets[0].url)
    }

    @Test
    fun noSuccessfulBuildThrowsNotFound() {
        val jobUrl = "https://ci.example.com/job/app"
        val apiUrl = "$jobUrl/lastSuccessfulBuild/api/json"
        val http = FakeHttp().text(apiUrl, "", status = 404)
        try {
            source.check(SourceSpec(source.type, jobUrl), CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    private fun artifactUrls(relativePath: String): List<String> {
        val jobUrl = "https://ci.example.com/job/app"
        val body = """{"number": 42, "artifacts": [{"fileName": "app.apk", "relativePath": "$relativePath"}]}"""
        val http = FakeHttp().text("$jobUrl/lastSuccessfulBuild/api/json", body)
        val result = source.check(SourceSpec(source.type, jobUrl), CheckContext(http, InMemoryValidatorStore()))
        return (result as CheckResult.Listing).listing.releases[0].assets.map { it.url }
    }

    @Test
    fun aPathThatClimbsOutOfTheBuildIsLeftOut() {
        assertEquals(emptyList<String>(), artifactUrls("../../../../job/other/lastSuccessfulBuild/artifact/app.apk"))
        assertEquals(emptyList<String>(), artifactUrls("build/./app.apk"))
        assertEquals(emptyList<String>(), artifactUrls("/etc/app.apk"))
        assertEquals(emptyList<String>(), artifactUrls("build//app.apk"))
    }

    @Test
    fun aPathWithALineBreakIsLeftOut() {
        assertEquals(emptyList<String>(), artifactUrls("build/app.apk\\r\\nHost: other.example.org"))
    }

    @Test
    fun whatAPathHoldsStaysInsideItsPart() {
        assertEquals(
            listOf("https://ci.example.com/job/app/lastSuccessfulBuild/artifact/out/my%20app%3Fx%3D1%23top%252e.apk"),
            artifactUrls("out/my app?x=1#top%2e.apk"),
        )
    }
}
