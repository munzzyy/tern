package io.github.munzzyy.tern.work

import io.github.munzzyy.tern.engine.ProblemKind
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

    @Test
    fun aRateLimitOrALostNetworkIsSaidOnceThoughItsWordsChange() {
        val limited = Trouble("a", "Kestrelwort", "The source asked Tern to wait. It will not be asked again before 13:05.", ProblemKind.RATE_LIMITED)
        val later = limited.copy(reason = "The source asked Tern to wait. It will not be asked again before 14:05.")
        assertFalse(Notifier.worthSaying(said(limited), listOf(later)))
        assertEquals(said(limited), Notifier.stillSaid(said(limited), listOf(later)))

        val unreachable = Trouble("b", "Moss Lantern", "The source could not be reached: failed to connect from /10.0.2.15 (port 40112)", ProblemKind.NETWORK)
        assertFalse(Notifier.worthSaying(said(unreachable), listOf(unreachable.copy(reason = "The source could not be reached: failed to connect from /10.0.2.15 (port 40598)"))))
        assertTrue(Notifier.worthSaying(said(unreachable), listOf(unreachable.copy(reason = limited.reason, kind = ProblemKind.RATE_LIMITED))))

        val gone = Trouble("c", "Quillfern", "The source has nothing at this address any more: 404", ProblemKind.NOT_FOUND)
        assertTrue(Notifier.worthSaying(said(gone), listOf(gone.copy(reason = "The source has nothing at this address any more: 410"))))
    }
}
