package io.github.munzzyy.tern.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    @Test
    fun theReportSaysWhereAndWhat() {
        val report = CrashReport.text("main", IllegalStateException("the list had no end"), "0.2.0", 34, 0)
        assertTrue(report, report.startsWith("Tern 0.2.0 on Android API 34\n1970-01-01T00:00:00Z, thread main\n"))
        assertTrue(report, "IllegalStateException: the list had no end" in report)
        assertTrue(report, "CrashReportTest" in report)
    }

    @Test
    fun aVeryLongReportIsCut() {
        val deep = RuntimeException("x".repeat(40_000))
        assertEquals(CrashReport.MAX_CHARS, CrashReport.text("worker", deep, "0.2.0", 29, 0).length)
    }
}
