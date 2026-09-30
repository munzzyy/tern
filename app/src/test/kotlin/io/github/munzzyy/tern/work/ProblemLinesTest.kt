package io.github.munzzyy.tern.work

import org.junit.Assert.assertEquals
import org.junit.Test

class ProblemLinesTest {
    @Test
    fun appsThatFailedAlikeShareOneLine() {
        val apps = listOf(
            Trouble("a", "Kestrelwort", "The server did not answer."),
            Trouble("b", "Moss Lantern", "No file for this device."),
            Trouble("c", "Quillfern", "The server did not answer."),
        )
        assertEquals(
            listOf(
                listOf("Kestrelwort", "Quillfern") to "The server did not answer.",
                listOf("Moss Lantern") to "No file for this device.",
            ),
            Notifier.grouped(apps),
        )
    }

    @Test
    fun eachReasonComesInTheOrderItWasFirstMet() {
        val apps = listOf(Trouble("a", "One", "second"), Trouble("b", "Two", "first"), Trouble("c", "Three", "second"))
        assertEquals(listOf("second", "first"), Notifier.grouped(apps).map { it.second })
        assertEquals(emptyList<Pair<List<String>, String>>(), Notifier.grouped(emptyList()))
    }
}
