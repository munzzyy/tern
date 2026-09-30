package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.work.Notifier
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
        val waiting = mapOf("a" to "Alpha", "c" to "Gamma")
        assertEquals(listOf("a" to "Alpha", "c" to "Gamma"), Notifier.stillWaiting(listOf("a", "b", "c"), "b") { waiting[it] })
        // An app updated some other way in the meantime is not named again.
        assertEquals(listOf("c" to "Gamma"), Notifier.stillWaiting(listOf("b", "c", "x"), "b") { waiting[it] })
        assertEquals(emptyList<Pair<String, String>>(), Notifier.stillWaiting(listOf("b"), "b") { waiting[it] })
    }
}
