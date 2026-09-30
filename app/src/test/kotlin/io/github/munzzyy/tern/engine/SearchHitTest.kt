package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.engine.real.Search
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchHitTest {
    private val search = Search(
        object : HttpClient {
            override fun execute(request: HttpRequest): HttpResponse = throw IOException("no network in this test")
        },
        TokenProvider.NONE,
    )

    private val turn = "‮"
    private val hidden = "​"

    @Test
    fun aHitIsFitToBeShown() {
        val repo = Json.obj("description" to "An app$turn that\nlooks$hidden  like another\u0007")
        val hit = search.hit(repo, "GitHub", "Sig${hidden}nal$turn", "⁦signal  app⁩", "https://github.com/example/app", 12)!!
        assertEquals("Signal", hit.name)
        assertEquals("signal app", hit.owner)
        assertEquals("An app that looks like another", hit.description)
        assertEquals("https://github.com/example/app", hit.url)
        assertEquals("GitHub", hit.origin)
        assertEquals(12, hit.stars)
    }

    @Test
    fun aStoresHitIsCleanedAsAForgesIs() {
        val hit = search.hit(Hit("Sig${hidden}nal", "Signal$turn", "Private\nmessages", "https://m.apkpure.com/signal/org.thoughtcrime.securesms", -3), "APKPure")!!
        assertEquals("Signal", hit.name)
        assertEquals("Private messages", hit.description)
        assertEquals("APKPure", hit.origin)
        assertEquals(0, hit.stars)
        assertNull(search.hit(Hit("Plain", null, null, "http://example.com/app", null), "APKPure"))
    }

    @Test
    fun onlyThePlacesPickedAreAsked() = runBlocking {
        val asked = ArrayList<String>()
        var storesOn = true
        val store = object : Searchable {
            override val origin = "Aptoide"
            override fun search(query: String, context: CheckContext): List<Hit> {
                asked += query
                return listOf(Hit("Found", null, null, "https://found.en.aptoide.com/app", null))
            }
        }
        val both = Search(
            object : HttpClient {
                override fun execute(request: HttpRequest): HttpResponse = throw IOException("no network in this test")
            },
            TokenProvider.NONE,
            { if (storesOn) listOf(store) else emptyList() },
        )
        assertEquals(listOf("GitHub", "Codeberg", "GitLab", "Aptoide"), both.origins)
        assertEquals(emptyList<SearchHit>(), both.search("maps", setOf("Somewhere else")).hits)
        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf("Found"), both.search("maps", setOf("Aptoide")).hits.map { it.name })
        assertEquals(listOf("maps"), asked)

        storesOn = false
        assertEquals("a store switched off is no place to search", listOf("GitHub", "Codeberg", "GitLab"), both.origins)
        assertEquals(emptyList<SearchHit>(), both.search("maps", setOf("Aptoide")).hits)
        assertEquals(listOf("maps"), asked)
    }

    @Test
    fun anOrdinaryHitComesThroughAsItIs() {
        val repo = Json.obj("description" to "A maps app for hikers.")
        val hit = search.hit(repo, "Codeberg", "Organic Maps", "organicmaps", "https://codeberg.org/example/maps", null)!!
        assertEquals(SearchHit("Organic Maps", "organicmaps", "A maps app for hikers.", "https://codeberg.org/example/maps", "Codeberg", null), hit)
    }

    @Test
    fun aHitWhoseNameHasNothingToShowIsNoHit() {
        assertNull(search.hit(Json.obj(), "GitHub", "$turn$hidden ", "example", "https://github.com/example/app", 1))
        assertNull(search.hit(Json.obj(), "GitHub", null, "example", "https://github.com/example/app", 1))
    }

    @Test
    fun textsAreCutToTheirLengths() {
        val long = "x".repeat(3000)
        val hit = search.hit(Json.obj("description" to long), "GitHub", long, long, "https://github.com/example/app", 1)!!
        assertEquals(200, hit.name.length)
        assertEquals(200, hit.owner?.length)
        assertEquals(500, hit.description?.length)
    }
}
