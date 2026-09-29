package io.github.munzzyy.stamp.ui.activity

import io.github.munzzyy.stamp.engine.Event
import io.github.munzzyy.stamp.engine.EventKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class EventDaysTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val day = 86_400_000L
    private fun e(id: Long, at: Long, kind: EventKind = EventKind.INSTALLED) = Event(id, at, "a", "A", kind, "m")

    @Test
    fun groupsByLocalDayNewestFirst() {
        val events = listOf(e(1, 10 * day + 5), e(2, 12 * day + 1), e(3, 12 * day + 9), e(4, 10 * day + 100))
        val days = groupByDay(events, zone, problemsOnly = false)
        assertEquals(listOf(LocalDate.ofEpochDay(12), LocalDate.ofEpochDay(10)), days.map { it.date })
        assertEquals(listOf(3L, 2L), days[0].events.map { it.id })
        assertEquals(listOf(4L, 1L), days[1].events.map { it.id })
    }

    @Test
    fun zoneDecidesTheDay() {
        val late = e(1, 10 * day + 23 * 3_600_000L)
        assertEquals(LocalDate.ofEpochDay(11), groupByDay(listOf(late), ZoneOffset.ofHours(2), false).single().date)
    }

    @Test
    fun problemsOnlyKeepsFailuresAndBlocks() {
        val events = listOf(
            e(1, day, EventKind.BLOCKED), e(2, day, EventKind.INSTALLED), e(3, day, EventKind.CHECK_FAILED),
            e(4, day, EventKind.FAILED), e(5, day, EventKind.UPDATE_FOUND),
        )
        assertEquals(setOf(1L, 3L, 4L), groupByDay(events, zone, true).flatMap { it.events }.map { it.id }.toSet())
    }

    @Test
    fun dayNames() {
        val today = LocalDate.of(2026, 9, 28)
        assertEquals(DayName.Today, dayName(today, today))
        assertEquals(DayName.Yesterday, dayName(today.minusDays(1), today))
        assertEquals(DayName.On(today.minusDays(2)), dayName(today.minusDays(2), today))
    }
}
