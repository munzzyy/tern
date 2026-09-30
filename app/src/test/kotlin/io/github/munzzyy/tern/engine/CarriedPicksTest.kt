package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.interop.ObtainiumLink
import io.github.munzzyy.tern.engine.real.Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link with several apps becomes one pick per app, each a link to that app alone with its settings. */
class CarriedPicksTest {
    private val json = """[
        {"id":"org.example.one","url":"https://github.com/example/one","name":"One","author":"Example","additionalSettings":"{\"trackOnly\":true}"},
        {"url":"ftp://example.org/elsewhere","name":"Elsewhere"},
        {"name":"No address"},
        {"url":"https://codeberg.org/example/two","name":"Two words & more"}
    ]"""

    @Test
    fun eachAppWithAWebAddressBecomesAPick() {
        val results = Detector.carriedPicks(json)!!
        assertEquals(Detection.Results.CARRIED, results.query)
        assertEquals(listOf("One", "Two words & more"), results.hits.map { it.name })
        assertEquals("https://github.com/example/one", results.hits[0].description)
    }

    @Test
    fun aPickOpensAsThatOneAppWithItsSettings() {
        val pick = Detector.carriedPicks(json)!!.hits[1]
        val link = ObtainiumLink.parse(pick.url) as ObtainiumLink.App
        assertTrue(link.json.contains("\"Two words & more\""))
        assertTrue(link.json.contains("https://codeberg.org/example/two"))
    }

    @Test
    fun nothingUsableMeansNoPicks() {
        assertNull(Detector.carriedPicks("not json"))
        assertNull(Detector.carriedPicks("""[{"url":"ftp://example.org/a"},{"name":"x"}]"""))
    }
}
