package io.github.munzzyy.tern.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPanesTest {
    @Test
    fun aTelevisionShowsOnePaneAtLargeText() {
        assertTrue(twoPanesFit(960.dp, 1f))
        assertTrue(twoPanesFit(960.dp, 1.3f))
        assertFalse(twoPanesFit(960.dp, 1.5f))
        assertFalse(twoPanesFit(960.dp, 2f))
    }

    @Test
    fun aWideTabletKeepsTwoPanesUntilTheTextOutgrowsThem() {
        assertTrue(twoPanesFit(1280.dp, 1.5f))
        assertFalse(twoPanesFit(1280.dp, 2f))
        assertFalse(twoPanesFit(600.dp, 1f))
    }
}
