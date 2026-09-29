package io.github.munzzyy.tern.ui.suggest

import io.github.munzzyy.tern.engine.Suggestion
import io.github.munzzyy.tern.engine.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StarterGroupsTest {
    private fun app(name: String, television: Boolean) =
        Suggestion(name, "An invented app", "https://example.org/$name", SuggestionKind.TOOLS, television)

    private val mixed = listOf(app("notes", false), app("player", true), app("maps", false), app("launcher", true))

    @Test
    fun onATelevisionTheAppsMadeForOneComeFirstUnderTheirOwnHeading() {
        val groups = starterGroups(mixed, television = true)
        assertEquals(listOf(StarterHeading.TELEVISION, StarterHeading.ANY_DEVICE), groups.map { it.heading })
        assertEquals(listOf("player", "launcher"), groups[0].apps.map { it.name })
        assertEquals(listOf("notes", "maps"), groups[1].apps.map { it.name })
    }

    @Test
    fun anywhereElseTheListIsOneInTheOrderItCameIn() {
        val groups = starterGroups(mixed, television = false)
        assertEquals(listOf<StarterHeading?>(null), groups.map { it.heading })
        assertEquals(listOf("notes", "player", "maps", "launcher"), groups.single().apps.map { it.name })
    }

    @Test
    fun aGroupWithNothingInItIsNotShown() {
        val none = starterGroups(listOf(app("notes", false)), television = true)
        assertEquals(listOf(StarterHeading.ANY_DEVICE), none.map { it.heading })
        assertEquals(emptyList<StarterGroup>(), starterGroups(emptyList(), television = true))
        assertEquals(emptyList<StarterGroup>(), starterGroups(emptyList(), television = false))
    }

    @Test
    fun aListLongerThanTheLimitIsCut() {
        val many = (1..MAX_STARTERS + 25).map { app("app$it", television = it % 2 == 0) }
        assertEquals(MAX_STARTERS, starterGroups(many, television = false).sumOf { it.apps.size })
        assertEquals(MAX_STARTERS, starterGroups(many, television = true).sumOf { it.apps.size })
        assertEquals(3, starterGroups(many, television = true, limit = 3).sumOf { it.apps.size })
    }
}
