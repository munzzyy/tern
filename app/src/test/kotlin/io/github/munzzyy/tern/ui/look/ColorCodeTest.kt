package io.github.munzzyy.tern.ui.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorCodeTest {
    @Test
    fun aCodeIsSixDigitsWithOrWithoutItsMark() {
        assertEquals(0xFF3D5A80.toInt(), colorOfHex("#3D5A80"))
        assertEquals(0xFF3D5A80.toInt(), colorOfHex(" 3d5a80 "))
        assertEquals(0xFF3D5A80.toInt(), colorOfHex("#803D5A80"))
        assertNull(colorOfHex("#3D5A8"))
        assertNull(colorOfHex("#GG5A80"))
        assertNull(colorOfHex(""))
    }

    @Test
    fun aColourIsWrittenAsItsCode() {
        assertEquals("#3D5A80", hexOf(0xFF3D5A80.toInt()))
        assertEquals("#000001", hexOf(1))
    }
}
