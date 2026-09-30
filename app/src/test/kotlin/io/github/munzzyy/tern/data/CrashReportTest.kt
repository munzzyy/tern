package io.github.munzzyy.tern.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun whatCouldOpenAnAccountLeavesTheReportAndTheFramesStay() {
        val error = RuntimeException("check failed", IOException("https://u:p@h.example/x?sig=deadbeef password=hunter2 ghp_A1b2C3d4E5f6G7h8I9j0K1l2"))
        val report = CrashReport.text("worker", error, "0.2.0", 34, 0)
        for (secret in listOf("u:p@", "sig=", "deadbeef", "hunter2", "ghp_")) assertFalse(report, secret in report)
        assertTrue(report, "Caused by: java.io.IOException: https://h.example/x" in report)
        assertTrue(report, "at io.github.munzzyy.tern.data.CrashReportTest.whatCouldOpenAnAccountLeavesTheReportAndTheFramesStay(CrashReportTest.kt:" in report)
    }

    @Test
    fun aReportAnOlderTernWroteIsScrubbedBeforeItIsShownOrShared() {
        val old = "Tern 0.1.0 on Android API 34\njava.io.IOException: Authorization: Bearer abc.def\n\tat a.B.c(B.kt:1)"
        assertEquals("Tern 0.1.0 on Android API 34\njava.io.IOException: Authorization: Bearer \u2026\n\tat a.B.c(B.kt:1)", CrashReport.scrubbed(old))
    }
}
