package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.work.Notifier
import io.github.munzzyy.tern.work.Offered
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfLastTest {
    @Test
    fun ternsOwnUpdateComesLastAndTheRestKeepTheirOrder() {
        assertEquals(listOf("a", "c", "d", "tern"), Installs.selfLast(listOf("a", "tern", "c", "d")) { it == "tern" })
        assertEquals(listOf("b", "a"), Installs.selfLast(listOf("b", "a")) { false })
    }

    @Test
    fun anInstalledAppLeavesTheUpdatesNotificationAndTheRestStay() {
        val alpha = Offered("a", "Alpha", "v2")
        val gamma = Offered("c", "Gamma", "v7")
        val waiting = listOf(alpha, gamma).associateBy { it.id }
        assertEquals(listOf(alpha, gamma), Notifier.stillWaiting(listOf("a", "b", "c"), "b") { waiting[it] })
        // An app updated some other way in the meantime is not named again.
        assertEquals(listOf(gamma), Notifier.stillWaiting(listOf("b", "c", "x"), "b") { waiting[it] })
        assertEquals(emptyList<Offered>(), Notifier.stillWaiting(listOf("b"), "b") { waiting[it] })
    }
}
