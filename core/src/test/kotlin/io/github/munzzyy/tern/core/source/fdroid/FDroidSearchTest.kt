package io.github.munzzyy.tern.core.source.fdroid

import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FDroidSearchTest {
    private val source = FDroidSource()

    private fun search(query: String, http: FakeHttp): List<Hit> = source.search(query, CheckContext(http, InMemoryValidatorStore()))

    @Test
    fun isSearchableAsFDroid() {
        assertEquals("F-Droid", source.origin)
        assertTrue(SourceRegistry.standard().searchable.any { it is FDroidSource })
    }

    @Test
    fun readsTheHitsOfTheSearchPage() {
        val http = FakeHttp().resource("https://search.f-droid.org/?q=notes&lang=en", "fdroid/search.html")
        val hits = search("notes", http)
        assertEquals(
            listOf(
                Hit("Example Notes", null, "Notes that stay on the phone & sync", "https://f-droid.org/packages/org.example.notes"),
                Hit("Example Player", null, null, "https://f-droid.org/packages/org.example.player"),
                Hit("Example Maps", null, "Maps for walking, kept on the phone", "https://f-droid.org/packages/org.example.maps"),
            ),
            hits,
        )
        for (hit in hits) {
            val spec = source.match(hit.url)
            assertEquals(hit.url, spec?.url)
            assertEquals(hit.url.substringAfterLast('/'), spec?.option(SourceOptions.PACKAGE))
        }
    }

    @Test
    fun theQueryIsSentAsOneEncodedParameter() {
        val url = "https://search.f-droid.org/?q=music%20player%20%26%20more&lang=en"
        val http = FakeHttp().text(url, "<html><body>No results</body></html>")
        assertEquals(emptyList<Hit>(), search("  music player & more ", http))
        assertEquals(listOf(url), http.requests.map { it.url })
    }

    @Test
    fun aBlankQueryAsksNothing() {
        val http = FakeHttp()
        assertEquals(emptyList<Hit>(), search("   ", http))
        assertEquals(0, http.requests.size)
    }

    @Test
    fun givesAtMostTwentyFiveHits() {
        val page = (1..40).joinToString("\n", "<html><body>", "</body></html>") { i ->
            """<a class="package-header" href="https://f-droid.org/en/packages/org.example.app$i"><h4 class="package-name">App $i</h4></a>"""
        }
        val hits = search("app", FakeHttp().text("https://search.f-droid.org/?q=app&lang=en", page))
        assertEquals(25, hits.size)
        assertEquals("App 1", hits.first().name)
        assertEquals("https://f-droid.org/packages/org.example.app25", hits.last().url)
    }

    @Test
    fun aFailingSearchIsANetworkProblem() {
        val http = FakeHttp().text("https://search.f-droid.org/?q=notes&lang=en", "", status = 502)
        try {
            search("notes", http)
            fail("expected a SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NETWORK, e.kind)
        }
    }
}
