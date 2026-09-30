package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface DayName {
    data object Today : DayName
    data object Yesterday : DayName
    data class On(val date: LocalDate) : DayName
}

private val PROBLEM_KINDS = setOf(EventKind.BLOCKED, EventKind.FAILED, EventKind.CHECK_FAILED)

fun isProblem(event: Event): Boolean = event.kind in PROBLEM_KINDS

fun dayName(date: LocalDate, today: LocalDate): DayName = when (date) {
    today -> DayName.Today
    today.minusDays(1) -> DayName.Yesterday
    else -> DayName.On(date)
}

/**
 * The log as plain text to share, newest first as on screen: one line an entry, as
 * "2026-09-30 14:03 · App · what happened". The time is written the same in every language, so
 * that whoever reads it can tell the order.
 */
fun activityText(events: List<Event>, zone: ZoneId, problemsOnly: Boolean): String {
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    return events.asSequence()
        .filter { !problemsOnly || isProblem(it) }
        .sortedByDescending { it.atMs }
        .joinToString("\n") { e ->
            val at = stamp.format(Instant.ofEpochMilli(e.atMs).atZone(zone))
            listOfNotNull(at, e.appName?.takeIf { it.isNotBlank() }, e.message.takeIf { it.isNotBlank() } ?: e.kind.name.lowercase(Locale.ROOT))
                .joinToString(" \u00b7 ")
        }
}
