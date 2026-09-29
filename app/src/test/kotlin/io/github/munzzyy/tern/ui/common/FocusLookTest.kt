package io.github.munzzyy.tern.ui.common

import io.github.munzzyy.tern.engine.Density as Roominess
import io.github.munzzyy.tern.ui.theme.lookFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLookTest {
    @Test
    fun aFocusedElementGrowsByThreeInAHundredWhereItHasTheRoom() {
        assertEquals(1.03f, focusScale(width = 200f, height = 60f, roomX = 8f, roomY = 4f), 0.0001f)
        assertEquals(1.03f, focusScale(width = 48f, height = 48f, roomX = 4f, roomY = 2f), 0.0001f)
    }

    @Test
    fun itGrowsLessWhereThatWouldTouchItsNeighbour() {
        val wide = focusScale(width = 800f, height = 60f, roomX = 8f, roomY = 4f)
        assertEquals(1.02f, wide, 0.0001f)
        val tall = focusScale(width = 100f, height = 400f, roomX = 8f, roomY = 4f)
        assertEquals(1.02f, tall, 0.0001f)
        assertEquals(1f, focusScale(width = 300f, height = 60f, roomX = 0f, roomY = 0f), 0f)
        assertEquals(1f, focusScale(width = 0f, height = 0f, roomX = 8f, roomY = 4f), 0f)
    }

    @Test
    fun whateverItsSizeItStaysInsideItsRoom() {
        for (width in listOf(24f, 48f, 120f, 411f, 840f, 1920f)) {
            for (height in listOf(24f, 48f, 72f, 300f, 1080f)) {
                for ((roomX, roomY) in listOf(4f to 2f, 8f to 4f, 1f to 0.5f)) {
                    val scale = focusScale(width, height, roomX, roomY)
                    assertTrue(scale in 1f..1.03f)
                    assertTrue("$width wide grows by ${width * (scale - 1) / 2}", width * (scale - 1) / 2 <= roomX + 0.001f)
                    assertTrue("$height high grows by ${height * (scale - 1) / 2}", height * (scale - 1) / 2 <= roomY + 0.001f)
                }
            }
        }
    }

    @Test
    fun onATelevisionAListRowGetsTheWholeScale() {
        val look = lookFor(Roominess.COMFORTABLE, television = true)
        val room = look.focusRoom.value
        val row = focusScale(width = 400 - 2 * room, height = look.rowHeight.value, roomX = room, roomY = room / 2)
        assertEquals(1.03f, row, 0.0001f)
    }
}
