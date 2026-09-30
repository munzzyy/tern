package io.github.munzzyy.tern.ui.settings

import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.text.intervalStops
import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalButtonsTest {
    private val stops = intervalStops(60)

    @Test
    fun theMinusButtonSaysWhenItTurnsTheChecksOff() {
        assertEquals(R.string.interval_turn_off, minusLabel(stops, stops.indexOf(15)))
        assertEquals(R.string.interval_more_often, minusLabel(stops, stops.indexOf(20)))
        assertEquals(R.string.interval_more_often, minusLabel(stops, stops.lastIndex))
    }

    @Test
    fun thePlusButtonSaysWhenItTurnsTheChecksOn() {
        assertEquals(R.string.interval_turn_on, plusLabel(stops, stops.indexOf(0)))
        assertEquals(R.string.interval_less_often, plusLabel(stops, stops.indexOf(15)))
        assertEquals(R.string.interval_less_often, plusLabel(stops, stops.lastIndex))
    }

    @Test
    fun anIntervalOfItsOwnBelowAQuarterOfAnHourStillTurnsOffOneStepDown() {
        val own = intervalStops(5)
        assertEquals(listOf(0, 5, 15), own.take(3))
        assertEquals(R.string.interval_turn_off, minusLabel(own, 1))
        assertEquals(R.string.interval_more_often, minusLabel(own, 2))
    }
}
