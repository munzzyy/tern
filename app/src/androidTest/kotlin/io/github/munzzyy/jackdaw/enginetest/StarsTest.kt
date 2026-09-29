package io.github.munzzyy.jackdaw.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonArray
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import io.github.munzzyy.jackdaw.engine.ProblemException
import io.github.munzzyy.jackdaw.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarsTest {
    private val page1 = "https://api.github.com/users/example/starred?per_page=100&page=1"

    private val body = Json.write(
        JsonArray(
            listOf(
                Json.obj("name" to "app", "owner" to Json.obj("login" to "example"), "description" to "An example app.", "stargazers_count" to 12),
                Json.obj("name" to "tool", "owner" to Json.obj("login" to "example"), "stargazers_count" to 3),
            ),
        ),
    )

    private suspend fun problemOf(block: suspend () -> Unit): ProblemException {
        try {
            block()
        } catch (e: ProblemException) {
            return e
        }
        fail("expected a ProblemException")
        throw AssertionError()
    }

    @Test
    fun listsStarsWithTheTokenForTheApiHostAndAddsNothing() = runBlocking {
        val routes = Routes(FakeForge()).json(page1, body)
        Harness("stars", http = routes).use { h ->
            h.engine.setToken("api.github.com", "tok")
            val hits = h.engine.starredBy(" @example ")
            assertEquals(listOf("app", "tool"), hits.map { it.name })
            assertEquals("https://github.com/example/app", hits[0].url)
            assertEquals("An example app.", hits[0].description)
            assertEquals(12, hits[0].stars)
            assertEquals("Bearer tok", routes.requests.single { it.url == page1 }.authorization)
            assertTrue(h.engine.apps.value.isEmpty())
            assertTrue(h.engine.store.apps().isEmpty())
        }
    }

    @Test
    fun badNamesAndMissingUsersAreProblemsInPlainWords() = runBlocking {
        val missing = "https://api.github.com/users/nobody/starred?per_page=100&page=1"
        val routes = Routes(FakeForge()).on(missing) { HttpResponse.of(404, "{}", url = missing) }
        Harness("stars-bad", http = routes).use { h ->
            val bad = problemOf { h.engine.starredBy("../repos") }
            assertEquals(ProblemKind.NOT_FOUND, bad.problem.kind)
            assertTrue(routes.requests.none { it.url.contains("api.github.com") })

            val gone = problemOf { h.engine.starredBy("nobody") }
            assertEquals(ProblemKind.NOT_FOUND, gone.problem.kind)
            assertEquals("GitHub has no user named nobody.", gone.problem.message)
        }
    }
}
