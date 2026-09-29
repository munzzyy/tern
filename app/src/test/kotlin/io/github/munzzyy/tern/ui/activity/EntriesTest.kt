package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class EntriesTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val day = 86_400_000L
    private val minute = 60_000L
    private var nextId = 1L

    private fun e(at: Long, kind: EventKind, app: String? = "a", message: String = "m") =
        Event(nextId++, at, app, app?.uppercase(), kind, message)

    private fun install(start: Long, app: String = "a", version: String = "1.5.0") = listOf(
        e(start, EventKind.DOWNLOADED, app, "Downloaded 8.4 MB"),
        e(start + 20_000, EventKind.VERIFIED, app, "Verified: package org.example.$app, version code 1005000, signed by ab12"),
        e(start + 40_000, EventKind.INSTALLED, app, "Installed version $version (version code 1005000)"),
    )

    @Test
    fun theStepsOfOneInstallAreOneEntryNamedAfterItsOutcome() {
        val entry = entriesOf(install(day)).single()
        assertEquals(listOf(EventKind.DOWNLOADED, EventKind.VERIFIED, EventKind.INSTALLED), entry.steps.map { it.kind })
        assertEquals(Headline.INSTALLED, entry.headline)
        assertEquals("1.5.0", entry.version)
        assertEquals(day + 40_000, entry.atMs)
    }

    @Test
    fun anInstallTheUserCancelledIsOneEntryAndNoProblem() {
        val found = e(day - 60 * minute, EventKind.UPDATE_FOUND, message = "Version 1.5.0 is available")
        val steps = listOf(
            e(day, EventKind.DOWNLOADED, message = "Downloaded 8.4 MB"),
            e(day + 20_000, EventKind.VERIFIED, message = "Verified: package org.example.a, version code 1005000, signed by ab12"),
            e(day + 40_000, EventKind.CANCELLED, message = "The install was cancelled."),
        )
        val entry = entriesOf(listOf(found) + steps).first()
        assertEquals(Headline.CANCELLED, entry.headline)
        assertEquals(listOf(EventKind.DOWNLOADED, EventKind.VERIFIED, EventKind.CANCELLED), entry.steps.map { it.kind })
        assertEquals("1.5.0", entry.version)
        assertEquals(false, entry.isProblem)
    }

    @Test
    fun anInstallAfterAnUpdateWasFoundIsAnUpdate() {
        val found = e(day - 60 * minute, EventKind.UPDATE_FOUND, message = "Version 1.5.0 is available")
        val entries = entriesOf(listOf(found) + install(day))
        assertEquals(listOf(Headline.UPDATED, Headline.UPDATE_WAITS), entries.map { it.headline })
        assertEquals(listOf("1.5.0", "1.5.0"), entries.map { it.version })
        assertEquals(3, entries[0].steps.size)
    }

    @Test
    fun aSecondInstallOfTheSameAppIsAnUpdate() {
        val entries = entriesOf(install(day, version = "1.0") + install(day + 60 * minute, version = "1.1"))
        assertEquals(listOf(Headline.UPDATED, Headline.INSTALLED), entries.map { it.headline })
        assertEquals(listOf("1.1", "1.0"), entries.map { it.version })
    }

    @Test
    fun anUpdateFoundAndInstalledWithinTwoMinutesIsOneEntry() {
        val found = e(day - 30_000, EventKind.UPDATE_FOUND, message = "Version 1.5.0 is available")
        val entry = entriesOf(listOf(found) + install(day)).single()
        assertEquals(Headline.UPDATED, entry.headline)
        assertEquals(4, entry.steps.size)
    }

    @Test
    fun stepsFurtherApartThanTwoMinutesAreTwoOperations() {
        val events = listOf(
            e(day, EventKind.DOWNLOADED),
            e(day + OPERATION_GAP_MS, EventKind.VERIFIED),
            e(day + 2 * OPERATION_GAP_MS + 1, EventKind.INSTALLED, message = "Installed version 2.0 (version code 2)"),
        )
        val entries = entriesOf(events)
        assertEquals(listOf(1, 2), entries.map { it.steps.size })
        assertEquals(listOf(Headline.INSTALLED, Headline.INSTALL_WAITS), entries.map { it.headline })
    }

    @Test
    fun twoAppsAtTheSameTimeStayApart() {
        val events = install(day, "a") + install(day + 5_000, "b")
        val entries = entriesOf(events)
        assertEquals(2, entries.size)
        assertEquals(listOf("b", "a"), entries.map { it.outcome.appId })
        assertTrue(entries.all { entry -> entry.steps.map { it.appId }.distinct().size == 1 && entry.steps.size == 3 })
    }

    @Test
    fun aRefusedFileIsBlockedWithTheVersionThatWasOffered() {
        val events = listOf(
            e(day - 10 * minute, EventKind.UPDATE_FOUND, message = "Version 4.1.0 is available"),
            e(day, EventKind.DOWNLOADED, message = "Downloaded 14 MB"),
            e(day + 1_000, EventKind.VERIFIED),
            e(day + 2_000, EventKind.BLOCKED, message = "This file is signed with a different key than the app you have."),
        )
        val blocked = entriesOf(events).first()
        assertEquals(Headline.BLOCKED, blocked.headline)
        assertEquals("4.1.0", blocked.version)
        assertEquals(3, blocked.steps.size)
        assertTrue(blocked.isProblem)
    }

    @Test
    fun aFailureWithoutAnythingBeforeItStandsAloneAndNamesNoVersion() {
        val entry = entriesOf(listOf(e(day, EventKind.FAILED, message = "The download failed."))).single()
        assertEquals(Headline.NOT_INSTALLED, entry.headline)
        assertNull(entry.version)
    }

    @Test
    fun aStepThatDoesNotFollowTheLastOneStartsANewOperation() {
        val events = listOf(e(day, EventKind.DOWNLOADED), e(day + 1_000, EventKind.DOWNLOADED), e(day + 2_000, EventKind.VERIFIED))
        assertEquals(listOf(2, 1), entriesOf(events).map { it.steps.size })
    }

    @Test
    fun nothingJoinsAnOperationThatHasItsOutcome() {
        val events = install(day) + e(day + 41_000, EventKind.FAILED, message = "The install failed.")
        assertEquals(listOf(Headline.NOT_INSTALLED, Headline.INSTALLED), entriesOf(events).map { it.headline })
    }

    @Test
    fun whatIsNoStepOfAnInstallIsAnEntryOfItsOwn() {
        val events = listOf(
            e(day, EventKind.ADDED, message = "Added from example.org"),
            e(day + 1, EventKind.CHECK_FAILED, message = "The source asked Tern to wait."),
            e(day + 2, EventKind.REMOVED),
            e(day + 3, EventKind.IMPORTED, app = null, message = "Imported 14 apps"),
            e(day + 4, EventKind.MOVED, message = "Followed the project"),
        )
        val entries = entriesOf(events)
        assertEquals(listOf(Headline.PLAIN, Headline.PLAIN, Headline.REMOVED, Headline.CHECK_FAILED, Headline.ADDED), entries.map { it.headline })
        assertTrue(entries.all { it.steps.size == 1 && it.version == null })
    }

    @Test
    fun anEventOfNoAppNeverJoinsAnother() {
        val events = listOf(e(day, EventKind.DOWNLOADED, app = null), e(day + 1, EventKind.VERIFIED, app = null))
        assertEquals(2, entriesOf(events).size)
    }

    @Test
    fun theVersionIsReadFromTheWordsOfTheEngine() {
        assertEquals("2.3.1", versionIn("Installed version 2.3.1 (version code 2003001)"))
        assertEquals("2.3.1", versionIn("Installed version 2.3.1."))
        assertEquals("v0.4.4-beta2", versionIn("Version v0.4.4-beta2 is available"))
        assertNull(versionIn("Installed version 42 (version code 42)"))
        assertEquals("42", versionIn("Version 42 is available", loose = true))
        assertNull(versionIn("The install was cancelled."))
        assertNull(versionIn("", loose = true))
    }

    @Test
    fun entriesLieUnderTheDayOfTheirOutcomeNewestFirst() {
        val late = install(11 * day - 30_000)
        val events = late + install(10 * day + 5, "b") + e(12 * day + 9, EventKind.ADDED, "c")
        val days = entriesByDay(events, zone, problemsOnly = false)
        assertEquals(listOf(LocalDate.ofEpochDay(12), LocalDate.ofEpochDay(11), LocalDate.ofEpochDay(10)), days.map { it.date })
        assertEquals(3, days[1].entries.single().steps.size)
    }

    @Test
    fun zoneDecidesTheDay() {
        val late = e(10 * day + 23 * 3_600_000L, EventKind.ADDED)
        assertEquals(LocalDate.ofEpochDay(11), entriesByDay(listOf(late), ZoneOffset.ofHours(2), false).single().date)
    }

    @Test
    fun problemsOnlyKeepsWhatEndedBadly() {
        val events = install(day) + listOf(
            e(2 * day, EventKind.DOWNLOADED, "b"),
            e(2 * day + 1_000, EventKind.BLOCKED, "b"),
            e(3 * day, EventKind.CHECK_FAILED, "c"),
            e(4 * day, EventKind.FAILED, "d"),
            e(5 * day, EventKind.UPDATE_FOUND, "e"),
        )
        val kept = entriesByDay(events, zone, problemsOnly = true).flatMap { it.entries }
        assertEquals(listOf(Headline.NOT_INSTALLED, Headline.CHECK_FAILED, Headline.BLOCKED), kept.map { it.headline })
        assertEquals(2, kept.last().steps.size)
        assertFalse(entriesOf(install(day)).single().isProblem)
    }

    @Test
    fun dayNames() {
        val today = LocalDate.of(2026, 9, 28)
        assertEquals(DayName.Today, dayName(today, today))
        assertEquals(DayName.Yesterday, dayName(today.minusDays(1), today))
        assertEquals(DayName.On(today.minusDays(2)), dayName(today.minusDays(2), today))
    }
}
