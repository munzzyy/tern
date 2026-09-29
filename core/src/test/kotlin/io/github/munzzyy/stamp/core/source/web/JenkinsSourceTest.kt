package io.github.munzzyy.stamp.core.source.web

import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.testing.FakeHttp
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
}
