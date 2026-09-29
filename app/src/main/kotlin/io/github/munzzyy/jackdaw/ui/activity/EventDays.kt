package io.github.munzzyy.jackdaw.ui.activity

import io.github.munzzyy.jackdaw.engine.Event
import io.github.munzzyy.jackdaw.engine.EventKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class EventDay(val date: LocalDate, val events: List<Event>)

sealed interface DayName {
    data object Today : DayName
    data object Yesterday : DayName
    data class On(val date: LocalDate) : DayName
}

private val PROBLEM_KINDS = setOf(EventKind.BLOCKED, EventKind.FAILED, EventKind.CHECK_FAILED)

fun isProblem(event: Event): Boolean = event.kind in PROBLEM_KINDS

/** Keeps the engine's newest-first order inside each day and across days. */
fun groupByDay(events: List<Event>, zone: ZoneId, problemsOnly: Boolean): List<EventDay> {
    val days = LinkedHashMap<LocalDate, MutableList<Event>>()
    for (e in events.sortedByDescending { it.atMs }) {
        if (problemsOnly && !isProblem(e)) continue
        val day = Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate()
        days.getOrPut(day) { mutableListOf() }.add(e)
    }
    return days.map { (date, list) -> EventDay(date, list) }
}

fun dayName(date: LocalDate, today: LocalDate): DayName = when (date) {
    today -> DayName.Today
    today.minusDays(1) -> DayName.Yesterday
    else -> DayName.On(date)
}
