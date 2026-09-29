package io.github.munzzyy.jackdaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test
    fun everyRouteSurvivesSaving() {
        val routes = listOf(
            Route.Apps, Route.Add(), Route.Add("https://example.org/a?b=c", 42), Route.Detail("some:id"),
            Route.Activity, Route.Settings, Route.Import,
        )
        for (r in routes) assertEquals(r, decodeRoute(encodeRoute(r)))
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
