package io.github.munzzyy.tern.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshLinkTest {
    @Test
    fun aRefreshLinkAsksForACheckOfEveryAppOrOne() {
        assertEquals(RefreshLink.All, refreshLink("tern://refresh"))
        assertEquals(RefreshLink.All, refreshLink("obtainium://refresh/"))
        assertEquals(RefreshLink.One("org.example.app"), refreshLink("obtainium://refresh?id=org.example.app"))
        assertEquals(RefreshLink.One("org.example.app"), refreshLink("tern://refresh?id=org%2Eexample%2Eapp"))
    }

    @Test
    fun everyOtherLinkIsNotOne() {
        assertNull(refreshLink("tern://add?url=https%3A%2F%2Fgithub.com%2Fexample%2Fapp"))
        assertNull(refreshLink("https://example.org/refresh"))
        assertNull(refreshLink("obtainium://refreshing"))
        assertNull(refreshLink(null))
        assertNull(refreshLink("tern://refresh" + "?id=" + "x".repeat(MAX_INCOMING_CHARS)))
    }
}
