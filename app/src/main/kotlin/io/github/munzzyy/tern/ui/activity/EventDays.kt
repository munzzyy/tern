package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.isOwn
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

private val PROBLEM_KINDS = setOf(EventKind.BLOCKED, EventKind.FAILED, EventKind.CHECK_FAILED, EventKind.OWN_WARNING, EventKind.OWN_ERROR)

fun isProblem(event: Event): Boolean = event.kind in PROBLEM_KINDS

/** How far back the log can be cut, as in Obtainium; null keeps all of it. */
val RANGES: List<Int?> = listOf(null, 1, 2, 3, 4, 5, 7)

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** The events of the last [days] days before [nowMs], or all of them for null. */
fun within(events: List<Event>, days: Int?, nowMs: Long): List<Event> =
    if (days == null) events else events.filter { it.atMs > nowMs - days * DAY_MS }

fun dayName(date: LocalDate, today: LocalDate): DayName = when (date) {
    today -> DayName.Today
    today.minusDays(1) -> DayName.Yesterday
    else -> DayName.On(date)
}

/**
 * The log as plain text to share, newest first as on screen: one line an entry, as
 * "2026-09-30 14:03 · App · what happened". The time is written the same in every language, so
 * that whoever reads it can tell the order. [own] takes in Tern's own messages: each is timed to
 * the second and named by its [marks] where an app's name would be, and the lines of an error
 * follow it, indented.
 */
fun activityText(events: List<Event>, zone: ZoneId, problemsOnly: Boolean, own: Boolean = true, marks: Map<EventKind, String> = emptyMap()): String {
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    val second = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    return events.asSequence()
        .filter { !problemsOnly || isProblem(it) }
        .filter { own || !it.kind.isOwn }
        .sortedByDescending { it.atMs }
        .joinToString("\n") { e ->
            val at = (if (e.kind.isOwn) second else stamp).format(Instant.ofEpochMilli(e.atMs).atZone(zone))
            val who = if (e.kind.isOwn) marks[e.kind] else e.appName
            listOfNotNull(at, who?.takeIf { it.isNotBlank() }, e.message.takeIf { it.isNotBlank() }?.replace("\n", "\n  ") ?: e.kind.name.lowercase(Locale.ROOT))
                .joinToString(" \u00b7 ")
        }
}
