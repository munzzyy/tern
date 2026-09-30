package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.engine.real.ExportNames
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
        assertEquals("tern-apps-2026-09-29.json", name())
        assertEquals("tern-apps-2026-09-29.json", name("tern-apps-2026-09-28.json", "tern-apps-2026-09-28-2.json", "notes.json"))
    }

    @Test
    fun theDayIsTheOneOnThisDevice() {
        assertEquals("tern-apps-2026-09-30.json", ExportNames.forDay(noon, ZoneId.of("Pacific/Kiritimati"), emptyList()))
        assertEquals("tern-apps-2026-09-29.json", ExportNames.forDay(noon, ZoneId.of("Pacific/Pago_Pago"), emptyList()))
        assertEquals("tern-apps-2026-09-28.json", ExportNames.forDay(noon - 12 * 3_600_000, ZoneId.of("Pacific/Pago_Pago"), emptyList()))
    }

    @Test
    fun aLaterExportOfTheSameDayGetsTheNextFreeNumber() {
        assertEquals("tern-apps-2026-09-29-2.json", name("tern-apps-2026-09-29.json"))
        assertEquals("tern-apps-2026-09-29-3.json", name("tern-apps-2026-09-29.json", "tern-apps-2026-09-29-2.json"))
        assertEquals("tern-apps-2026-09-29-2.json", name("tern-apps-2026-09-29.json", "tern-apps-2026-09-29-3.json"))
        assertEquals("tern-apps-2026-09-29-2.json", name("Tern-Apps-2026-09-29.JSON"))
        assertEquals("tern-apps-2026-09-29.json", name("tern-apps-2026-09-29-2.json"))
    }

    @Test
    fun anExportForObtainiumIsNamedAsObtainiumNamesItsOwn() {
        val prefix = ExportNames.prefixOf(ExportFormat.OBTAINIUM)
        assertEquals("obtainium-export-2026-09-29.json", ExportNames.forDay(noon, ZoneOffset.UTC, emptyList(), prefix))
        assertEquals("obtainium-export-2026-09-29-2.json", ExportNames.forDay(noon, ZoneOffset.UTC, listOf("obtainium-export-2026-09-29.json"), prefix))
        assertEquals("tern-apps-", ExportNames.prefixOf(ExportFormat.TERN))
    }

    @Test
    fun theNumbersEndAndTheNameIsThenLeftToAndroid() {
        val taken = listOf("tern-apps-2026-09-29.json") + (2..500).map { "tern-apps-2026-09-29-$it.json" }
        assertEquals("tern-apps-2026-09-29.json", ExportNames.forDay(noon, ZoneOffset.UTC, taken))
        assertEquals("tern-apps-2026-09-29-500.json", ExportNames.forDay(noon, ZoneOffset.UTC, taken - "tern-apps-2026-09-29-500.json"))
    }
}
