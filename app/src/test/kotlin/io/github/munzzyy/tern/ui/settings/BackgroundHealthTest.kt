package io.github.munzzyy.tern.ui.settings

import io.github.munzzyy.tern.engine.BackgroundFacts
import io.github.munzzyy.tern.engine.RunStop
import io.github.munzzyy.tern.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings says what keeps the background check from running or from being heard, and nothing when all is well. */
class BackgroundHealthTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val sixHours = Settings(checkEveryMinutes = 360)
    private val healthy = BackgroundFacts(lastRunMs = now - hour, sinceMs = now - 48 * hour)

    private fun notes(facts: BackgroundFacts, s: Settings = sixHours, television: Boolean = false) = BackgroundHealth.notes(s, facts, now, television)

    @Test
    fun allWellSaysNothing() {
        assertEquals(emptyList<BackgroundNote>(), notes(healthy))
        assertEquals(emptyList<BackgroundNote>(), notes(healthy, television = true))
    }

    @Test
    fun eachTroubleAloneHasItsNote() {
        assertEquals(listOf(BackgroundNote.RESTRICTED), notes(healthy.copy(restricted = true)))
        assertEquals(listOf(BackgroundNote.QUIET), notes(healthy.copy(notificationsOn = false)))
        assertEquals(listOf(BackgroundNote.QUIET), notes(healthy.copy(updatesChannelOn = false)))
        assertEquals(listOf(BackgroundNote.NOT_SET), notes(healthy.copy(scheduled = false)))
        assertEquals(listOf(BackgroundNote.STALE), notes(healthy.copy(lastRunMs = now - 19 * hour)))
    }

    @Test
    fun aRunThatReachedNothingSaysWhyFirst() {
        assertEquals(listOf(BackgroundNote.PROXY_SILENT), notes(healthy.copy(lastRunStopped = RunStop.PROXY_SILENT)))
        assertEquals(listOf(BackgroundNote.OFFLINE), notes(healthy.copy(lastRunStopped = RunStop.OFFLINE)))
        assertEquals(listOf(BackgroundNote.OFFLINE), notes(healthy.copy(lastRunStopped = RunStop.OFFLINE), television = true))
        assertEquals(
            listOf(BackgroundNote.PROXY_SILENT, BackgroundNote.RESTRICTED),
            notes(healthy.copy(lastRunStopped = RunStop.PROXY_SILENT, restricted = true)),
        )
    }

    @Test
    fun aRunThatReachedNothingStillCountsAsARun() {
        assertEquals(
            "Android did start the job, so it is held back only when it has not since",
            listOf(BackgroundNote.PROXY_SILENT, BackgroundNote.STALE),
            notes(healthy.copy(lastRunStopped = RunStop.PROXY_SILENT, lastRunMs = now - 19 * hour)),
        )
    }

    @Test
    fun aCheckThatIsOffHasNoNotes() {
        val everything = BackgroundFacts(lastRunMs = now - 900 * hour, lastRunStopped = RunStop.PROXY_SILENT, restricted = true, notificationsOn = false, scheduled = false)
        assertEquals(emptyList<BackgroundNote>(), notes(everything, sixHours.copy(checkEveryMinutes = 0)))
    }

    @Test
    fun heldBackMeansMoreThanThreeIntervals() {
        val interval = 6 * hour
        assertEquals(emptyList<BackgroundNote>(), notes(healthy.copy(lastRunMs = now - interval * 29 / 10)))
        assertEquals(listOf(BackgroundNote.STALE), notes(healthy.copy(lastRunMs = now - interval * 31 / 10)))
    }

    @Test
    fun theClockCountsFromTheLaterOfTheLastRunAndTheJobSetAnew() {
        val longAgo = now - 100 * hour
        assertEquals("a job set an hour ago has had no time to run", emptyList<BackgroundNote>(), notes(BackgroundFacts(lastRunMs = longAgo, sinceMs = now - hour)))
        assertEquals("a job that never ran in three of its intervals is held back", listOf(BackgroundNote.STALE), notes(BackgroundFacts(lastRunMs = null, sinceMs = now - 19 * hour)))
        assertEquals("with no time noted at all nothing can be said", emptyList<BackgroundNote>(), notes(BackgroundFacts(lastRunMs = null, sinceMs = null)))
    }

    @Test
    fun aMonthIntervalDoesNotOverflow() {
        val month = Settings(checkEveryMinutes = 30 * 24 * 60)
        val day = 24 * hour
        assertEquals(emptyList<BackgroundNote>(), notes(BackgroundFacts(lastRunMs = now - 80 * day, sinceMs = now - 200 * day), month))
        assertEquals(listOf(BackgroundNote.STALE), notes(BackgroundFacts(lastRunMs = now - 91 * day, sinceMs = now - 200 * day), month))
    }

    @Test
    fun aTelevisionIsToldNothingAboutNotifications() {
        assertEquals(emptyList<BackgroundNote>(), notes(healthy.copy(notificationsOn = false, updatesChannelOn = false), television = true))
        assertEquals(listOf(BackgroundNote.RESTRICTED), notes(healthy.copy(restricted = true, notificationsOn = false), television = true))
    }

    @Test
    fun notificationsSwitchedOffInTernAreNoTrouble() {
        assertEquals(emptyList<BackgroundNote>(), notes(healthy.copy(notificationsOn = false), sixHours.copy(notifyUpdates = false)))
    }

    @Test
    fun aCheckThatWaitsForWiFiOrAChargerIsNotSaidToBeHeldBack() {
        val old = healthy.copy(lastRunMs = now - 100 * hour)
        assertEquals(emptyList<BackgroundNote>(), notes(old, sixHours.copy(checkOnlyOnUnmetered = true)))
        assertEquals(emptyList<BackgroundNote>(), notes(old, sixHours.copy(checkOnlyWhileCharging = true)))
        assertEquals(listOf(BackgroundNote.STALE), notes(old))
    }

    @Test
    fun oneNoteSaysWhyAndNoSecondRepeatsIt() {
        val old = now - 100 * hour
        assertEquals("a restricted app is not also said to be held back", listOf(BackgroundNote.RESTRICTED), notes(healthy.copy(restricted = true, lastRunMs = old)))
        assertEquals("a job that is not set is not also said to be held back", listOf(BackgroundNote.NOT_SET), notes(healthy.copy(scheduled = false, lastRunMs = old)))
        assertEquals(
            listOf(BackgroundNote.RESTRICTED, BackgroundNote.QUIET, BackgroundNote.NOT_SET),
            notes(BackgroundFacts(restricted = true, notificationsOn = false, scheduled = false)),
        )
    }
}
