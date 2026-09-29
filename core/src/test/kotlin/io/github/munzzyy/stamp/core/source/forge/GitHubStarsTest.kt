package io.github.munzzyy.stamp.core.source.forge

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonArray
import io.github.munzzyy.stamp.core.net.Headers
import io.github.munzzyy.stamp.core.net.HttpResponse
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.net.RateLimitedException
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.TokenProvider
import io.github.munzzyy.stamp.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubStarsTest {
    private fun page(n: Int) = "https://api.github.com/users/example/starred?per_page=100&page=$n"

    private fun context(http: FakeHttp, token: String? = null) =
        CheckContext(http, InMemoryValidatorStore(), TokenProvider { host -> token?.takeIf { host == "api.github.com" } })

    private fun repos(from: Int, count: Int): String = Json.write(
        JsonArray((from until from + count).map { Json.obj("name" to "app-$it", "owner" to Json.obj("login" to "example"), "stargazers_count" to it) }),
    )

    private fun failure(block: () -> Unit): SourceException {
        try {
            block()
        } catch (e: SourceException) {
            return e
        }
        fail("expected a SourceException")
        throw AssertionError()
    }

    @Test
    fun readsTheListShape() {
        val http = FakeHttp().resource(page(1), "forge/github_starred.json")
        val stars = GitHubStars.list("example", context(http))
        assertEquals(listOf("app", "notes-cli"), stars.map { it.name })
        val first = stars[0]
        assertEquals("example", first.owner)
        assertEquals("https://github.com/example/app", first.url)
        assertEquals("An example Android app.", first.description)
        assertEquals(120, first.stars)
        assertNull(stars[1].description)
        assertEquals(1, http.requests.size)
        assertNull(http.requests[0].authorization)
        assertEquals("application/vnd.github+json", http.requests[0].headers["Accept"])
    }

    @Test
    fun sendsTheTokenStoredForTheApiHostOnly() {
        val http = FakeHttp().text(page(1), "[]")
        GitHubStars.list("@example", context(http, token = "secret"))
        assertEquals("Bearer secret", http.requests.single().authorization)
    }

    @Test
    fun readsAtMostThreePages() {
        val http = FakeHttp().text(page(1), repos(0, 100)).text(page(2), repos(100, 100)).text(page(3), repos(200, 100))
        val stars = GitHubStars.list("example", context(http), limit = 5000)
        assertEquals(300, stars.size)
        assertEquals(3, http.requests.size)

        val repeating = FakeHttp().text(page(1), repos(0, 100)).text(page(2), repos(0, 100)).text(page(3), repos(0, 100))
        assertEquals(100, GitHubStars.list("example", context(repeating)).size)
        assertEquals(3, repeating.requests.size)
    }

    @Test
    fun stopsAtTheLimitAndAtAShortPage() {
        val limited = FakeHttp().text(page(1), repos(0, 100)).text(page(2), repos(100, 100))
        assertEquals(150, GitHubStars.list("example", context(limited), limit = 150).size)
        assertEquals(2, limited.requests.size)

        val short = FakeHttp().text(page(1), repos(0, 40))
        assertEquals(40, GitHubStars.list("example", context(short)).size)
        assertEquals(1, short.requests.size)
    }

    @Test
    fun refusesNamesThatAreNotGitHubUserNames() {
        val http = FakeHttp()
        for (bad in listOf("", "-example", "example-", "a/b", "../users", "a b", "x".repeat(40), "exa‮mple", "example?page=9")) {
            assertEquals(bad, SourceErrorKind.NOT_FOUND, failure { GitHubStars.list(bad, context(http)) }.kind)
        }
        assertTrue(http.requests.isEmpty())
        assertTrue(GitHubStars.isValidUser("x".repeat(39)))
        assertTrue(GitHubStars.isValidUser("a-b-c"))
    }

    @Test
    fun turnsRefusalsIntoTheirKinds() {
        val missing = FakeHttp().text(page(1), "{}", status = 404)
        assertEquals(SourceErrorKind.NOT_FOUND, failure { GitHubStars.list("example", context(missing)) }.kind)

        val limited = FakeHttp().on(page(1)) {
            HttpResponse.of(403, "{}", Headers.of("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1790000000"), page(1))
        }
        val e = failure { GitHubStars.list("example", context(limited)) }
        assertEquals(SourceErrorKind.RATE_LIMITED, e.kind)
        assertEquals(1_790_000_000_000L, e.retryAtMs)

        val politely = FakeHttp().on(page(1)) { throw RateLimitedException("api.github.com", 5L) }
        assertEquals(5L, failure { GitHubStars.list("example", context(politely)) }.retryAtMs)

        val tooMany = FakeHttp().on(page(1)) { HttpResponse.of(429, "", Headers.of("X-RateLimit-Reset" to "99999999999999999999"), page(1)) }
        val many = failure { GitHubStars.list("example", context(tooMany)) }
        assertEquals(SourceErrorKind.RATE_LIMITED, many.kind)
        assertNull(many.retryAtMs)

        val rejected = FakeHttp().text(page(1), "{}", status = 401)
        assertEquals(SourceErrorKind.AUTH, failure { GitHubStars.list("example", context(rejected, token = "old")) }.kind)
    }

    @Test
    fun aFailureOnALaterPageIsNotHidden() {
        val http = FakeHttp().text(page(1), repos(0, 100)).text(page(2), "{}", status = 500)
        assertEquals(SourceErrorKind.NETWORK, failure { GitHubStars.list("example", context(http)) }.kind)
    }

    @Test
    fun hostileEntriesAreCleanedOrDropped() {
        val body = Json.write(
            JsonArray(
                listOf(
                    Json.obj("name" to "app", "owner" to Json.obj("login" to "example"), "html_url" to "http://evil.example.net/x", "stargazers_count" to -5),
                    Json.obj("name" to "app", "owner" to Json.obj("login" to "example")),
                    Json.obj("name" to "../../etc", "owner" to Json.obj("login" to "example")),
                    Json.obj("name" to "tool", "owner" to Json.obj("login" to "a/b")),
                    Json.obj("name" to "tool"),
                    Json.obj("owner" to Json.obj("login" to "example")),
                    Json.obj("name" to "big", "owner" to Json.obj("login" to "example"), "stargazers_count" to 5_000_000_000L, "description" to "‮x\u0000y" + "z".repeat(10_000)),
                    Json.of("not an object"),
                ),
            ),
        )
        val stars = GitHubStars.list("example", context(FakeHttp().text(page(1), body)))
        assertEquals(listOf("app", "big"), stars.map { it.name })
        assertEquals("https://github.com/example/app", stars[0].url)
        assertEquals(0, stars[0].stars)
        assertEquals(Int.MAX_VALUE, stars[1].stars)
        val description = stars[1].description!!
        assertEquals(500, description.length)
        assertTrue(description.startsWith("xyz"))
    }

    @Test
    fun brokenAnswersAreParseOrNetworkFailures() {
        for (text in listOf("{}", "[", "[{\"name\":", "\u0000", "")) {
            val http = FakeHttp().text(page(1), text)
            assertEquals(text, SourceErrorKind.PARSE, failure { GitHubStars.list("example", context(http)) }.kind)
        }
        val huge = FakeHttp().on(page(1)) { HttpResponse.of(200, ByteArray(5 * 1024 * 1024) { ' '.code.toByte() }, Headers.EMPTY, page(1)) }
        assertEquals(SourceErrorKind.NETWORK, failure { GitHubStars.list("example", context(huge)) }.kind)
        val deep = FakeHttp().text(page(1), "[".repeat(100_000))
        assertEquals(SourceErrorKind.PARSE, failure { GitHubStars.list("example", context(deep)) }.kind)
    }
}
