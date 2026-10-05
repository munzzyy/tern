package io.github.munzzyy.tern.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    private val down = Trouble("a", "Kestrelwort", "The server did not answer.")
    private val noFile = Trouble("b", "Moss Lantern", "No file for this device.")
    private fun said(vararg apps: Trouble) = apps.mapTo(HashSet(), Notifier::fingerprint)

    @Test
    fun aFailureIsKeptAsAFingerprintOfTheAppAndTheReason() {
        val kept = Notifier.fingerprint(down)
        assertEquals(64, kept.length)
        assertEquals(kept, Notifier.fingerprint(down.copy(name = "Renamed")))
        assertNotEquals(kept, Notifier.fingerprint(down.copy(reason = noFile.reason)))
        assertNotEquals(kept, Notifier.fingerprint(down.copy(id = "b")))
    }

    @Test
    fun failuresAlreadySaidAreNotSaidAgainInAnyOrderOrInPart() {
        assertTrue(Notifier.worthSaying(emptySet(), listOf(down)))
        assertFalse(Notifier.worthSaying(said(down, noFile), listOf(noFile, down)))
        // A retry checks only some of the apps again.
        assertFalse(Notifier.worthSaying(said(down, noFile), listOf(noFile)))
        assertFalse(Notifier.worthSaying(said(down), emptyList()))
    }

    @Test
    fun aNewFailureOrANewReasonIsSaid() {
        assertTrue(Notifier.worthSaying(said(down), listOf(down, noFile)))
        assertTrue(Notifier.worthSaying(said(down, noFile), listOf(down, noFile.copy(reason = down.reason))))
    }

    @Test
    fun aFailureThatPassedIsForgottenAndSaidAgainWhenItComesBack() {
        val kept = Notifier.stillSaid(said(down, noFile), listOf(down))
        assertEquals(said(down), kept)
        assertTrue(Notifier.worthSaying(kept, listOf(noFile)))
        assertEquals(emptySet<String>(), Notifier.stillSaid(said(down), listOf(down.copy(reason = noFile.reason))))
        assertEquals(emptySet<String>(), Notifier.stillSaid(said(down, noFile), emptyList()))
    }
}
