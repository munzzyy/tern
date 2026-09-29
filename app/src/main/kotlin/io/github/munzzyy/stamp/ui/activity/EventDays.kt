package io.github.munzzyy.stamp.ui.activity

import io.github.munzzyy.stamp.engine.Event
import io.github.munzzyy.stamp.engine.EventKind
import java.time.LocalDate

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
