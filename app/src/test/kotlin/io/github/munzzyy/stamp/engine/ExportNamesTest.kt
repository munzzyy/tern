package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.engine.real.ExportNames
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamesTest {
    private val noon = ZonedDateTime.of(2026, 9, 29, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun name(vararg taken: String) = ExportNames.forDay(noon, ZoneOffset.UTC, taken.toList())

    @Test
    fun theFirstExportOfADayCarriesTheDay() {
        assertEquals("stamp-apps-2026-09-29.json", name())
        assertEquals("stamp-apps-2026-09-29.json", name("stamp-apps-2026-09-28.json", "stamp-apps-2026-09-28-2.json", "notes.json"))
    }

    @Test
    fun theDayIsTheOneOnThisDevice() {
        assertEquals("stamp-apps-2026-09-30.json", ExportNames.forDay(noon, ZoneId.of("Pacific/Kiritimati"), emptyList()))
        assertEquals("stamp-apps-2026-09-29.json", ExportNames.forDay(noon, ZoneId.of("Pacific/Pago_Pago"), emptyList()))
        assertEquals("stamp-apps-2026-09-28.json", ExportNames.forDay(noon - 12 * 3_600_000, ZoneId.of("Pacific/Pago_Pago"), emptyList()))
    }

    @Test
    fun aLaterExportOfTheSameDayGetsTheNextFreeNumber() {
        assertEquals("stamp-apps-2026-09-29-2.json", name("stamp-apps-2026-09-29.json"))
        assertEquals("stamp-apps-2026-09-29-3.json", name("stamp-apps-2026-09-29.json", "stamp-apps-2026-09-29-2.json"))
        assertEquals("stamp-apps-2026-09-29-2.json", name("stamp-apps-2026-09-29.json", "stamp-apps-2026-09-29-3.json"))
        assertEquals("stamp-apps-2026-09-29-2.json", name("Stamp-Apps-2026-09-29.JSON"))
        assertEquals("stamp-apps-2026-09-29.json", name("stamp-apps-2026-09-29-2.json"))
    }

    @Test
    fun theNumbersEndAndTheNameIsThenLeftToAndroid() {
        val taken = listOf("stamp-apps-2026-09-29.json") + (2..500).map { "stamp-apps-2026-09-29-$it.json" }
        assertEquals("stamp-apps-2026-09-29.json", ExportNames.forDay(noon, ZoneOffset.UTC, taken))
        assertEquals("stamp-apps-2026-09-29-500.json", ExportNames.forDay(noon, ZoneOffset.UTC, taken - "stamp-apps-2026-09-29-500.json"))
    }
}
