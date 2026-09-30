package io.github.munzzyy.tern.ui.icons

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconTintTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun aWhiteMarkOnAColouredShapeGivesTheShape() {
        val pixels = IntArray(100) { if (it < 40) argb(255, 255, 255, 255) else argb(255, 30, 120, 200) }
        assertEquals(Color(30, 120, 200), weighedColor(pixels))
    }

    @Test
    fun seeThroughPixelsCountForNothing() {
        val pixels = IntArray(100) { if (it < 90) argb(0, 255, 0, 0) else argb(255, 0, 160, 80) }
        assertEquals(Color(0, 160, 80), weighedColor(pixels))
    }

    @Test
    fun anIconOfGreysHasNoColour() {
        val pixels = IntArray(64) { argb(255, it * 4, it * 4, it * 4 + 3) }
        assertNull(weighedColor(pixels))
        assertNull(weighedColor(IntArray(0)))
    }

    @Test
    fun theMoreColouredPixelsWeighMore() {
        val strong = argb(255, 220, 20, 20)
        val faint = argb(255, 150, 100, 100)
        val tint = weighedColor(intArrayOf(strong, faint))!!
        // A plain mean would give 185 of 255 red.
        assertTrue(tint.red > 0.8f)
    }
}
