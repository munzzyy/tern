package io.github.munzzyy.stamp.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.munzzyy.stamp.engine.Corners
import io.github.munzzyy.stamp.engine.IconShape
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlinesTest {
    @Test
    fun everyCornerSettingGivesMaterialFiveSizesThatNeverShrinkOnTheWayUp() {
        for (corners in Corners.entries) {
            val sizes = cornerSizes(corners)
            assertEquals(5, sizes.size)
            assertEquals("$corners", sizes.sorted(), sizes)
            val shapes = shapesFor(corners)
            val made = listOf(shapes.extraSmall, shapes.small, shapes.medium, shapes.large, shapes.extraLarge)
            assertEquals(sizes.map { RoundedCornerShape(it.dp) }, made)
        }
    }

    @Test
    fun roundIsRounderThanSoftIsRounderThanSharp() {
        val round = cornerSizes(Corners.ROUND)
        val soft = cornerSizes(Corners.SOFT)
        val sharp = cornerSizes(Corners.SHARP)
        for (i in 0 until 5) {
            assertTrue("size $i", round[i] > soft[i] && soft[i] > sharp[i])
        }
        assertTrue("sharp stays sharp at every size", sharp.all { it <= 2 })
    }

    @Test
    fun buttonsFollowTheCorners() {
        assertEquals(CircleShape, outlinesFor(Corners.ROUND, IconShape.CIRCLE).button)
        val soft = outlinesFor(Corners.SOFT, IconShape.CIRCLE).button
        val sharp = outlinesFor(Corners.SHARP, IconShape.CIRCLE).button
        assertEquals(3, listOf(CircleShape, soft, sharp).distinct().size)
        assertEquals(RoundedCornerShape(2.dp), sharp)
    }

    @Test
    fun theIconOutlineFollowsItsOwnSettingAndNothingElse() {
        val outlines = IconShape.entries.map { outlinesFor(Corners.ROUND, it).icon }
        assertEquals(IconShape.entries.size, outlines.distinct().size)
        assertEquals(CircleShape, outlines[IconShape.entries.indexOf(IconShape.CIRCLE)])
        assertEquals(Squircle, outlines[IconShape.entries.indexOf(IconShape.SQUIRCLE)])
        for (shape in IconShape.entries) {
            assertEquals(outlinesFor(Corners.ROUND, shape).icon, outlinesFor(Corners.SHARP, shape).icon)
            assertNotEquals(outlinesFor(Corners.ROUND, shape).button, outlinesFor(Corners.SHARP, shape).button)
        }
    }

    @Test
    fun aSquircleLiesBetweenACircleAndASquare() {
        val side = 100f
        val points = squirclePoints(side, side)
        assertEquals(96 * 2, points.size)
        var reachesFurtherThanACircle = false
        for (i in points.indices step 2) {
            val x = points[i]
            val y = points[i + 1]
            assertTrue("($x, $y) is outside the box", x in 0f..side && y in 0f..side)
            val fromCentre = hypot(x - side / 2, y - side / 2)
            assertTrue("($x, $y) is inside the circle", fromCentre >= side / 2 - 0.01f)
            if (fromCentre > side / 2 + 5) reachesFurtherThanACircle = true
        }
        assertTrue(reachesFurtherThanACircle)
        val corner = points.indices.step(2).maxOf { hypot(points[it] - side / 2, points[it + 1] - side / 2) }
        assertTrue("the corner stays short of the square's", corner < hypot(side / 2, side / 2) - 5)
        assertEquals(side, points[0], 0.01f)
        assertEquals(side / 2, points[1], 0.01f)
    }
}
