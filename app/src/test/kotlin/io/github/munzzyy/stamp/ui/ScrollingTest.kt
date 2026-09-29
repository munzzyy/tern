package io.github.munzzyy.stamp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollingTest {
    private val screen = 1000f

    @Test
    fun whatLiesInsideWithRoomAroundItMovesNothing() {
        assertEquals(0f, scrollToShow(offset = 400f, size = 200f, containerSize = screen, room = 16f), 0f)
        assertEquals(0f, scrollToShow(offset = 16f, size = 200f, containerSize = screen, room = 16f), 0f)
        assertEquals(0f, scrollToShow(offset = 784f, size = 200f, containerSize = screen, room = 16f), 0f)
    }

    @Test
    fun whatLiesInsideStaysWhereItIsWhereverThatIs() {
        for (offset in 16..784 step 8) {
            assertEquals("at $offset", 0f, scrollToShow(offset.toFloat(), 200f, screen, 16f), 0f)
        }
    }

    @Test
    fun whatLiesBelowComesUpByAsMuchAsItTakesAndTheRoom() {
        assertEquals(116f, scrollToShow(offset = 900f, size = 200f, containerSize = screen, room = 16f), 0f)
        assertEquals(6f, scrollToShow(offset = 790f, size = 200f, containerSize = screen, room = 16f), 0f)
    }

    @Test
    fun whatLiesAboveComesDownByAsMuchAsItTakesAndTheRoom() {
        assertEquals(-116f, scrollToShow(offset = -100f, size = 200f, containerSize = screen, room = 16f), 0f)
        assertEquals(-6f, scrollToShow(offset = 10f, size = 200f, containerSize = screen, room = 16f), 0f)
    }

    @Test
    fun afterTheMoveItLiesInsideAndAsksForNothingMore() {
        for (offset in listOf(-500f, -1f, 3f, 790f, 999f, 4000f)) {
            val moved = offset - scrollToShow(offset, 200f, screen, 16f)
            assertEquals("from $offset", 0f, scrollToShow(moved, 200f, screen, 16f), 0f)
        }
    }

    @Test
    fun whatIsTooLongForTheRoomIsShownWithoutIt() {
        assertEquals(0f, scrollToShow(offset = 0f, size = 990f, containerSize = screen, room = 16f), 0f)
        assertEquals(40f, scrollToShow(offset = 50f, size = 990f, containerSize = screen, room = 16f), 0f)
    }

    @Test
    fun whatCoversTheScreenOnBothSidesStays() {
        assertEquals(0f, scrollToShow(offset = -100f, size = 1500f, containerSize = screen, room = 16f), 0f)
    }
}
