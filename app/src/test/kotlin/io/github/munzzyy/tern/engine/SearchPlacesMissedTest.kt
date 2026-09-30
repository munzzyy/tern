package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.engine.real.Search
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlacesMissedTest {
    private val asked = ArrayList<HttpRequest>()

    private fun search(tokens: TokenProvider = TokenProvider.NONE, stores: List<Searchable> = emptyList(), answer: (HttpRequest) -> HttpResponse) = Search(
        HttpClient { request ->
            asked += request
            answer(request)
        },
        tokens,
        { stores },
        log = {},
    )

    private fun store(origin: String, names: List<String>) = object : Searchable {
        override val origin = origin
        override fun search(query: String, context: CheckContext): List<Hit> = names.map { Hit(it, null, null, "https://$it.en.aptoide.com/app", null) }
    }

    @Test
    fun aPlaceThatFailsIsNamedWithWhyAndTheOthersStillAnswer() = runBlocking {
        val outcome = search(stores = listOf(store("Aptoide", listOf("maps")))) { request ->
            when {
                request.url.startsWith("https://api.github.com/") -> throw RateLimitedException("api.github.com", 1234L)
                request.url.startsWith("https://codeberg.org/") -> HttpResponse.of(503, "", url = request.url)
                request.url.startsWith("https://gitlab.com/") -> throw IOException("no route")
                else -> error("unexpected ${request.url}")
            }
        }.search("maps", setOf("GitHub", "Codeberg", "GitLab", "Aptoide"))
        assertEquals(listOf("maps"), outcome.hits.map { it.name })
        assertEquals(
            listOf(
                Search.Miss("GitHub", Search.Why.WAIT, retryAtMs = 1234L),
                Search.Miss("Codeberg", Search.Why.STATUS, status = 503),
                Search.Miss("GitLab", Search.Why.UNREACHABLE),
            ),
            outcome.missed,
        )
    }

    @Test
    fun aRefusalAndAnAnswerThatIsNotReadAreToldApart() = runBlocking {
        val outcome = search { request ->
            if (request.url.startsWith("https://api.github.com/")) HttpResponse.of(403, "", url = request.url) else HttpResponse.of(200, "not json", url = request.url)
        }.search("maps", setOf("GitHub", "GitLab"))
        assertEquals(listOf(Search.Why.REFUSED, Search.Why.UNREADABLE), outcome.missed.map { it.why })
        assertTrue(outcome.hits.isEmpty())
    }

    @Test
    fun theForgejoNamedIsAskedWithItsOwnTokenAndOnlyThatOne() = runBlocking {
        val tokens = TokenProvider { host -> if (host == "git.example.org") "secret" else null }
        val body = """{"data":[
            {"name":"few","owner":{"login":"a"},"html_url":"https://git.example.org/a/few","stars_count":2},
            {"name":"many","owner":{"login":"a"},"html_url":"https://git.example.org/a/many","stars_count":40}]}"""
        val outcome = search(tokens) { request -> HttpResponse.of(200, body, url = request.url) }
            .search("maps", setOf("Codeberg"), Search.Scope(forgejo = "https://git.example.org/", minStars = 10))
        val request = asked.single()
        assertTrue(request.url, request.url.startsWith("https://git.example.org/api/v1/repos/search?q=maps&limit=100"))
        assertEquals("token secret", request.authorization)
        assertEquals(listOf("many"), outcome.hits.map { it.name })
        assertEquals("git.example.org", outcome.hits.single().origin)
        // Another Forgejo's projects are read as ones; Codeberg's addresses say so by themselves.
        assertEquals(SourceTypes.FORGEJO, outcome.hits.single().type)
    }

    @Test
    fun codebergIsAskedWithoutATokenOfAnotherHost() = runBlocking {
        val tokens = TokenProvider { host -> if (host == "api.github.com") "secret" else null }
        val body = """{"data":[{"name":"app","owner":{"login":"a"},"html_url":"https://codeberg.org/a/app","stars_count":0}]}"""
        val outcome = search(tokens) { request -> HttpResponse.of(200, body, url = request.url) }.search("maps", setOf("Codeberg"))
        assertNull(asked.single().authorization)
        assertEquals("Codeberg", outcome.hits.single().origin)
        assertNull(outcome.hits.single().type)
    }

    @Test
    fun gitHubIsAskedForAsManyAsObtainiumAndLeavesOutTooFewStarsItself() = runBlocking {
        search { request -> HttpResponse.of(200, """{"items":[]}""", url = request.url) }
            .search("maps app", setOf("GitHub"), Search.Scope(minStars = 50))
        assertEquals("https://api.github.com/search/repositories?q=maps%20app%20stars%3A%3E%3D50&per_page=100", asked.single().url)
    }

    @Test
    fun thePlacesTakeTurnsSoNoneIsLeftOutAtTheTop() {
        assertEquals(listOf(1, 10, 100, 2, 20, 3), Search.takingTurns(listOf(listOf(1, 2, 3), listOf(10, 20), listOf(100))))
        assertEquals(emptyList<Int>(), Search.takingTurns(listOf(emptyList<Int>(), emptyList())))
    }
}
