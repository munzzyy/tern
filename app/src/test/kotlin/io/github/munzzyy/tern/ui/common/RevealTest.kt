package io.github.munzzyy.tern.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RevealTest {
    private val window = 400f
    private val room = 60f

    private fun move(offset: Float, size: Float = 80f) = revealDistance(offset, size, window, room)

    @Test
    fun whatIsInViewWithRoomAroundItMovesNothing() {
        assertEquals(0f, move(offset = 60f), 0f)
        assertEquals(0f, move(offset = 160f), 0f)
        assertEquals(0f, move(offset = 260f), 0f)
    }

    @Test
    fun theFirstThingOfAListThatIsAtItsTopAsksOnlyForWhatCannotBeGiven() {
        assertEquals("a list at its top cannot go further back, so this moves nothing", -60f, move(offset = 0f), 0f)
    }

    @Test
    fun whatLiesBelowComesUpJustFarEnoughToShowWhatFollows() {
        assertEquals(40f, move(offset = 300f), 0f)
        assertEquals(140f, move(offset = 400f), 0f)
    }

    @Test
    fun whatLiesAboveComesDownJustFarEnoughToShowWhatCameBefore() {
        assertEquals(-100f, move(offset = -40f), 0f)
    }

    @Test
    fun aThingTallerThanTheWindowStaysWhereItIs() {
        assertEquals(0f, move(offset = -50f, size = 600f), 0f)
    }

    @Test
    fun roomIsGivenUpWhereTheWindowHasNone() {
        assertEquals(0f, revealDistance(offset = 0f, size = 400f, containerSize = 400f, room = 60f), 0f)
        assertEquals(30f, revealDistance(offset = 40f, size = 380f, containerSize = 400f, room = 60f), 0f)
    }
}
