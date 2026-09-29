package io.github.munzzyy.stamp.ui.icons

import io.github.munzzyy.stamp.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconAskTest {
    private fun row(checkedAt: Long?) = testRow(id = "a", installed = null, lastChecked = checkedAt)

    @Test
    fun aMissIsNotAskedForAgainInTheSameRound() {
        val misses = Misses()
        val ask = iconAsk(row(1_000), 96, sourceIcons = true)
        assertFalse(misses.known(ask))
        misses.note(ask)
        assertTrue(misses.known(iconAsk(row(1_000), 96, sourceIcons = true)))
    }

    @Test
    fun theNextCheckAsksAgain() {
        val misses = Misses()
        misses.note(iconAsk(row(null), 96, sourceIcons = true))
        assertFalse(misses.known(iconAsk(row(1_000), 96, sourceIcons = true)))
        misses.note(iconAsk(row(1_000), 96, sourceIcons = true))
        assertFalse(misses.known(iconAsk(row(2_000), 96, sourceIcons = true)))
    }

    @Test
    fun theNextCheckKeepsThePictureThatIsThere() {
        assertEquals(iconAsk(row(1_000), 96, sourceIcons = true).key, iconAsk(row(2_000), 96, sourceIcons = true).key)
    }

    @Test
    fun switchingTheSettingAsksAgainAndDropsWhatWasShown() {
        val misses = Misses()
        misses.note(iconAsk(row(1_000), 96, sourceIcons = false))
        assertFalse(misses.known(iconAsk(row(1_000), 96, sourceIcons = true)))
        assertNotEquals(iconAsk(row(1_000), 96, sourceIcons = false).key, iconAsk(row(1_000), 96, sourceIcons = true).key)
    }

    @Test
    fun aLongListDoesNotGrowWithoutEnd() {
        val misses = Misses(most = 3)
        val asks = (1..5).map { IconAsk("app$it", "1") }
        asks.forEach(misses::note)
        assertEquals(listOf(false, false, true, true, true), asks.map(misses::known))
    }
}
