package io.github.munzzyy.stamp.ui.look

import io.github.munzzyy.stamp.engine.Palette
import io.github.munzzyy.stamp.ui.BackStack
import io.github.munzzyy.stamp.ui.Route
import io.github.munzzyy.stamp.ui.Tab
import io.github.munzzyy.stamp.ui.decodeRoute
import io.github.munzzyy.stamp.ui.encodeRoute
import io.github.munzzyy.stamp.ui.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookPageTest {
    @Test
    fun theLookPageSurvivesSavingAndBelongsToSettings() {
        assertEquals(Route.Look, decodeRoute(encodeRoute(Route.Look)))
        assertEquals(Tab.SETTINGS, Route.Look.tab())
        val stack = BackStack(emptyList())
        stack.select(Tab.SETTINGS)
        stack.push(Route.Look)
        assertEquals(listOf(Route.Apps, Route.Settings, Route.Look), BackStack.decode(stack.encode()).routes)
        assertTrue(stack.pop())
        assertEquals(Route.Settings, stack.top)
    }

    @Test
    fun leftAndRightMoveTheHueByFiveDegrees() {
        assertEquals(255, hueAfterKey(250, forward = true))
        assertEquals(245, hueAfterKey(250, forward = false))
        assertEquals(257, hueAfterKey(252, forward = true))
    }

    @Test
    fun theHueIsACircleSoTheSliderHasNoEndToGetStuckAt() {
        assertEquals(0, hueAfterKey(355, forward = true))
        assertEquals(3, hueAfterKey(358, forward = true))
        assertEquals(355, hueAfterKey(0, forward = false))
        assertEquals(357, hueAfterKey(2, forward = false))
        var hue = 250
        repeat(72) { hue = hueAfterKey(hue, forward = true) }
        assertEquals(250, hue)
        val seen = generateSequence(0) { hueAfterKey(it, forward = true) }.take(72).toSet()
        assertEquals(72, seen.size)
        assertTrue(seen.all { it in 0..359 })
    }

    @Test
    fun aFingerPicksTheHueUnderIt() {
        assertEquals(0, hueAt(0f, 400f, rightToLeft = false))
        assertEquals(359, hueAt(400f, 400f, rightToLeft = false))
        assertTrue(hueAt(200f, 400f, rightToLeft = false) in 179..181)
        assertEquals(0, hueAt(-30f, 400f, rightToLeft = false))
        assertEquals(359, hueAt(900f, 400f, rightToLeft = false))
        assertEquals(0, hueAt(10f, 0f, rightToLeft = false))
    }

    @Test
    fun inARightToLeftLanguageTheTrackRunsTheOtherWay() {
        assertEquals(359, hueAt(0f, 400f, rightToLeft = true))
        assertEquals(0, hueAt(400f, 400f, rightToLeft = true))
        assertEquals(hueAt(100f, 400f, rightToLeft = false), hueAt(300f, 400f, rightToLeft = true))
    }

    @Test
    fun theTrackRunsOnceAroundTheCircleOfHues() {
        val hues = trackHues()
        assertEquals(TRACK_SEGMENTS, hues.size)
        assertEquals(hues.sorted(), hues)
        assertTrue(hues.all { it in 0..359 })
        assertEquals(2, hues.first())
        assertEquals(357, hues.last())
        for (i in 1 until hues.size) assertEquals(HUE_KEY_STEP, hues[i] - hues[i - 1])
        assertEquals(listOf(45, 135, 225, 315), trackHues(4))
    }

    @Test
    fun theSegmentsOfTheTrackLeaveNoGapAndStayInsideIt() {
        for ((start, end) in listOf(24f to 1011f, 72f to 963.5f, 10f to 50f, 0f to 36f)) {
            val edges = segmentEdges(start, end)
            assertEquals(TRACK_SEGMENTS + 1, edges.size)
            assertEquals(start, edges.first(), 0f)
            assertEquals(end, edges.last(), 0f)
            for (i in 1 until edges.size) {
                assertTrue("segment $i of $start to $end runs backwards", edges[i] >= edges[i - 1])
                if (i < edges.size - 1) assertEquals("edge $i is not on a whole pixel", Math.round(edges[i]).toFloat(), edges[i], 0f)
            }
            val widths = (1 until edges.size).map { edges[it] - edges[it - 1] }
            assertEquals(end - start, widths.sum(), 0.01f)
            assertTrue("segments differ by more than a pixel: $widths", widths.max() - widths.min() <= 1.01f)
        }
    }

    @Test
    fun theWheelHasAWedgeForEveryTwelfthOfTheCircle() {
        assertEquals(listOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330), wheelHues())
        assertEquals(listOf(0, 120, 240), wheelHues(3))
    }

    @Test
    fun swatchesStandAsManyAbreastAsFit() {
        assertEquals(5, columnsFor(width = 347f, least = 64f, count = 10))
        assertEquals(2, columnsFor(width = 347f, least = 128f, count = 10))
        assertEquals(1, columnsFor(width = 100f, least = 128f, count = 10))
        assertEquals(10, columnsFor(width = 2000f, least = 64f, count = 10))
        assertEquals(1, columnsFor(width = 300f, least = 64f, count = 1))
    }

    @Test
    fun noSwatchIsLeftAloneOnTheLastLine() {
        assertEquals(4, columnsFor(width = 347f, least = 64f, count = 11))
        assertEquals(3, columnsFor(width = 260f, least = 64f, count = 9))
        assertEquals(2, columnsFor(width = 140f, least = 64f, count = 9))
        for (count in 2..12) {
            for (width in listOf(200f, 347f, 500f, 900f)) {
                val columns = columnsFor(width, 64f, count)
                assertTrue("$count swatches in $columns columns", columns <= 2 || count % columns != 1)
                assertTrue(columns * 64f <= width)
            }
        }
    }

    @Test
    fun everyPaletteHasAName() {
        assertEquals(Palette.entries.size, Palette.entries.map(::paletteName).distinct().size)
    }
}
