package io.github.munzzyy.tern.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test
    fun everyRouteSurvivesSaving() {
        val routes = listOf(
            Route.Apps, Route.Add(), Route.Add("https://example.org/a?b=c", 42), Route.Add(starters = true), Route.Detail("some:id"),
            Route.Activity, Route.Settings, Route.Import, Route.Look, Route.Handoff,
        )
        for (r in routes) assertEquals(r, decodeRoute(encodeRoute(r)))
    }

    @Test
    fun anAddSavedBeforeTheWellKnownAppsHadARouteStillOpensOnTheField() {
        val saved = "add\u00015\u0001https://example.org/a"
        assertEquals(Route.Add("https://example.org/a", 5), decodeRoute(saved))
        assertEquals(saved, encodeRoute(Route.Add("https://example.org/a", 5)))
        assertEquals(Route.Add(), decodeRoute("add\u00010\u0001"))
        assertFalse((decodeRoute("add") as Route.Add).starters)
    }

    @Test
    fun theWellKnownAppsOpenTheAddTabOverTheList() {
        val stack = BackStack(listOf(Route.Apps, Route.Settings))
        stack.showStarters()
        assertEquals(listOf(Route.Apps, Route.Add(starters = true)), stack.routes)
        assertEquals(Tab.ADD, stack.top.tab())
        assertFalse(stack.addIsOnTop)
        assertEquals(stack.routes, BackStack.decode(stack.encode()).routes)
    }

    @Test
    fun theHandoffBelongsToAdding() {
        assertEquals(Tab.ADD, Route.Handoff.tab())
    }

    @Test
    fun lookingAtALinkGoesOnTopOfTheScreenThatOfferedIt() {
        val stack = BackStack(listOf(Route.Apps, Route.Add(), Route.Handoff))
        stack.lookAt("https://example.org/a", 1)
        assertEquals(listOf(Route.Apps, Route.Add(), Route.Handoff, Route.Add("https://example.org/a", 1)), stack.routes)
        assertTrue(stack.addIsOnTop)

        stack.lookAt("https://example.org/b", 2)
        assertEquals(listOf(Route.Apps, Route.Add(), Route.Handoff, Route.Add("https://example.org/b", 2)), stack.routes)

        assertTrue(stack.pop())
        assertEquals(Route.Handoff, stack.top)
    }

    @Test
    fun theAddTabItselfIsNotOnTopOfAnything() {
        val stack = BackStack(emptyList())
        stack.select(Tab.ADD)
        assertFalse(stack.addIsOnTop)
        stack.openAdd("https://example.org", 7)
        assertFalse(stack.addIsOnTop)
        stack.select(Tab.SETTINGS)
        assertFalse(stack.addIsOnTop)
    }

    @Test
    fun anAppAddedFromTheAddTabOpensOverTheList() {
        val stack = BackStack(emptyList())
        stack.select(Tab.ADD)
        stack.showAdded("sparrow")
        assertEquals(listOf(Route.Apps, Route.Detail("sparrow")), stack.routes)
    }

    @Test
    fun anAppAddedFromALinkThatAScreenOfferedLeavesThatScreenUnderIt() {
        val stack = BackStack(listOf(Route.Apps, Route.Settings))
        stack.lookAt("https://example.org/orbot", 3)
        stack.showAdded("orbot")
        assertEquals(listOf(Route.Apps, Route.Settings, Route.Detail("orbot")), stack.routes)
        assertTrue(stack.pop())
        assertEquals(Route.Settings, stack.top)
    }

    @Test
    fun unknownSavedRoutesAreDropped() {
        assertNull(decodeRoute("nonsense"))
        assertNull(decodeRoute("detail"))
        val stack = BackStack.decode(listOf("nonsense", "settings"))
        assertEquals(listOf(Route.Apps, Route.Settings), stack.routes)
    }

    @Test
    fun appsIsAlwaysAtTheBottom() {
        val stack = BackStack(emptyList())
        assertEquals(Route.Apps, stack.top)
        assertFalse(stack.pop())
        assertEquals(listOf(Route.Apps), stack.routes)
    }

    @Test
    fun tabsResetHistoryAndBackReturnsToApps() {
        val stack = BackStack(emptyList())
        stack.showDetail("a")
        stack.select(Tab.SETTINGS)
        stack.push(Route.Import)
        assertEquals(listOf(Route.Apps, Route.Settings, Route.Import), stack.routes)
        assertTrue(stack.pop())
        assertTrue(stack.pop())
        assertEquals(Route.Apps, stack.top)
    }

    @Test
    fun detailsReplaceEachOther() {
        val stack = BackStack(emptyList())
        stack.showDetail("a")
        stack.showDetail("b")
        assertEquals(listOf(Route.Apps, Route.Detail("b")), stack.routes)
        stack.push(Route.Detail("b"))
        assertEquals(2, stack.routes.size)
    }

    @Test
    fun incomingAddReplacesWhateverWasOpen() {
        val stack = BackStack(listOf(Route.Apps, Route.Settings, Route.Import))
        stack.openAdd("https://example.org", 7)
        assertEquals(listOf(Route.Apps, Route.Add("https://example.org", 7)), stack.routes)
        assertEquals(Tab.ADD, stack.top.tab())
    }
}
