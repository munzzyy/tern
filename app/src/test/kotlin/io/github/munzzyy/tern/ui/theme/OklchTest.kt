package io.github.munzzyy.tern.ui.theme

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklchTest {
    private fun rgb(value: Int): Int = BLACK or value

    @Test
    fun knownColoursLandWhereTheReferenceSaysTheyDo() {
        val blue = oklchOf(rgb(0x0000FF))
        assertEquals(0.452, blue.lightness, 0.001)
        assertEquals(0.313, blue.chroma, 0.001)
        assertEquals(264.05, blue.hue, 0.1)
        val red = oklchOf(rgb(0xFF0000))
        assertEquals(0.628, red.lightness, 0.001)
        assertEquals(0.2577, red.chroma, 0.001)
        assertEquals(29.23, red.hue, 0.1)
        val green = oklchOf(rgb(0x00FF00))
        assertEquals(0.8664, green.lightness, 0.001)
        assertEquals(0.2948, green.chroma, 0.001)
        assertEquals(142.5, green.hue, 0.1)
        assertEquals(1.0, oklchOf(WHITE).lightness, 0.0005)
        assertEquals(0.0, oklchOf(WHITE).chroma, 0.0005)
        assertEquals(0.0, oklchOf(BLACK).lightness, 0.0005)
    }

    @Test
    fun aColourSurvivesTheTripThereAndBack() {
        for (r in 0..255 step 15) {
            for (g in 0..255 step 15) {
                for (b in 0..255 step 15) {
                    val argb = rgb((r shl 16) or (g shl 8) or b)
                    assertEquals(argb, oklchOf(argb).toArgb())
                }
            }
        }
    }

    @Test
    fun aColourNoScreenCanShowLosesChromaAndKeepsItsHue() {
        for (hue in 0 until 360 step 15) {
            val shown = oklchOf(Oklch(0.6, 0.4, hue.toDouble()).toArgb())
            assertTrue("hue $hue came back as ${shown.hue}", abs(((shown.hue - hue + 540) % 360) - 180) < 3)
            assertTrue(shown.chroma < 0.4)
            assertEquals(0.6, shown.lightness, 0.02)
        }
    }

    @Test
    fun contrastIsWhatWcagSaysItIs() {
        assertEquals(21.0, contrast(BLACK, WHITE), 0.001)
        assertEquals(1.0, contrast(WHITE, WHITE), 0.001)
        assertEquals(4.54, contrast(rgb(0x767676), WHITE), 0.01)
        assertEquals(contrast(rgb(0x3D5A80), WHITE), contrast(WHITE, rgb(0x3D5A80)), 0.0)
        for (color in listOf(0x3D5A80, 0xBA1A1A, 0x00A03C, 0xFFB000, 0x101010, 0xFAFAFA)) {
            assertEquals(wcag(rgb(color), WHITE), contrast(rgb(color), WHITE), 0.0001)
        }
    }

    @Test
    fun aToneHasTheSameLuminanceWhateverTheHue() {
        for (hue in 0 until 360 step 20) {
            for (chroma in listOf(0.0, 0.02, 0.1, 0.2)) {
                for (tone in 5..95 step 5) {
                    val got = toneOf(toned(hue.toDouble(), chroma, tone.toDouble()))
                    assertEquals("hue $hue, chroma $chroma", tone.toDouble(), got, 0.6)
                }
            }
        }
        assertEquals(BLACK, toned(277.0, 0.2, 0.0))
        assertEquals(WHITE, toned(277.0, 0.2, 100.0))
    }

    @Test
    fun tonesOfOneRampGetLighterStepByStep() {
        for (hue in 0 until 360 step 20) {
            val ramp = (0..100 step 4).map { luminance(toned(hue.toDouble(), 0.19, it.toDouble())) }
            assertEquals(ramp.sorted(), ramp)
            assertEquals(ramp.size, ramp.distinct().size)
        }
    }
}
