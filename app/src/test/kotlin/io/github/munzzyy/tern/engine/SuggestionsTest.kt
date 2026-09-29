package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.suggest.Catalog
import io.github.munzzyy.tern.core.suggest.SuggestedKind
import io.github.munzzyy.tern.engine.real.Suggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    @Test
    fun everyEntryOfTheCatalogHasAStringOfItsOwn() {
        val strings = Catalog.all.map { app ->
            val id = Suggestions.summaryOf(app.summaryKey)
            assertNotNull("${app.name}: no string for ${app.summaryKey}", id)
            id!!
        }
        assertTrue(strings.none { it == 0 })
        assertEquals(strings.size, strings.toSet().size)
    }

    @Test
    fun everyKindHasItsCounterpartInTheContract() {
        assertEquals(SuggestedKind.entries.map { it.name }, SuggestionKind.entries.map { it.name })
        for (kind in SuggestedKind.entries) assertEquals(kind.name, Suggestions.kindOf(kind).name)
    }

    @Test
    fun theWholeCatalogIsOfferedInItsOrderWithItsOwnSummaries() {
        for (television in listOf(false, true)) {
            val offered = Suggestions.list(television) { "text of $it" }
            val expected = Catalog.ordered(television)
            assertEquals(expected.map { it.name }, offered.map { it.name })
            assertEquals(expected.map { it.url }, offered.map { it.url })
            assertEquals(expected.map { it.television }, offered.map { it.forTelevision })
            assertEquals(expected.map { it.kind.name }, offered.map { it.kind.name })
            assertEquals(expected.map { "text of ${Suggestions.summaryOf(it.summaryKey)}" }, offered.map { it.summary })
        }
        assertTrue(Suggestions.list(television = true) { "" }.first().forTelevision)
    }
}
