package io.github.munzzyy.tern.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVerifierTest {
    @Test
    fun theTextIsWrittenTheWayAppVerifierWritesItsOwn() {
        val signer = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        assertEquals(
            "org.example.app\n" +
                "A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90:A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90",
            AppVerifier.text("org.example.app", listOf(signer)),
        )
    }

    @Test
    fun eachCertificateHasALineOfItsOwn() {
        val lines = AppVerifier.text("org.example.app", listOf("aa".repeat(32), "bb".repeat(32))).lines()
        assertEquals(listOf("org.example.app", List(32) { "AA" }.joinToString(":"), List(32) { "BB" }.joinToString(":")), lines)
    }
}
