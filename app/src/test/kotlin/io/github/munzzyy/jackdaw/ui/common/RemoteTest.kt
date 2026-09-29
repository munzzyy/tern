package io.github.munzzyy.jackdaw.ui.common

import android.content.res.Configuration
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTest {
    private val normal = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
    private val television = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES

    @Test
    fun aPhoneHasTouch() {
        assertFalse(lacksTouch(touchFeature = true, touchscreen = Configuration.TOUCHSCREEN_FINGER, uiMode = normal))
    }

    @Test
    fun aTabletWithAKeyboardStillHasTouch() {
        assertFalse(lacksTouch(touchFeature = true, touchscreen = Configuration.TOUCHSCREEN_FINGER, uiMode = Configuration.UI_MODE_TYPE_NORMAL))
    }

    @Test
    fun aTelevisionLacksTouchEvenWhenItReportsATouchScreen() {
        assertTrue(lacksTouch(touchFeature = true, touchscreen = Configuration.TOUCHSCREEN_FINGER, uiMode = television))
    }

    @Test
    fun noTouchFeatureOrNoTouchScreenMeansKeysOnly() {
        assertTrue(lacksTouch(touchFeature = false, touchscreen = Configuration.TOUCHSCREEN_FINGER, uiMode = normal))
        assertTrue(lacksTouch(touchFeature = true, touchscreen = Configuration.TOUCHSCREEN_NOTOUCH, uiMode = normal))
    }

    @Test
    fun theRememberedControlComesFirst() {
        val slots = mapOf("row-2" to "R2", "import" to "IMP")
        assertEquals(listOf("R2", "F", "B"), landingOrder("row-2", slots, "F", "B", waitForSlot = true))
        assertEquals(listOf("R2", "F", "B"), landingOrder("row-2", slots, "F", "B", waitForSlot = false))
    }

    @Test
    fun aRememberedControlNotLaidOutYetIsWaitedForThenGivenUp() {
        assertEquals(emptyList<String>(), landingOrder("gone", mapOf("row-2" to "R2"), "F", "B", waitForSlot = true))
        assertEquals(listOf("F", "B"), landingOrder("gone", mapOf("row-2" to "R2"), "F", "B", waitForSlot = false))
    }

    @Test
    fun withNothingRememberedTheFirstControlThenTheBackup() {
        assertEquals(listOf("F", "B"), landingOrder(null, mapOf("row-2" to "R2"), "F", "B", waitForSlot = true))
    }

    @Test
    fun theRingSitsOutsideTheControl() {
        val ring = ringRect(Rect(10f, 20f, 110f, 60f), gap = 2f, width = 4f)
        assertEquals(Rect(6f, 16f, 114f, 64f), ring)
    }

    @Test
    fun buttonsGetAPillAndRowsARoundedBox() {
        assertEquals(22f, ringCorner(height = 40f, pillMaxHeight = 56f, rowCorner = 12f, gap = 2f), 0f)
        assertEquals(12f, ringCorner(height = 72f, pillMaxHeight = 56f, rowCorner = 12f, gap = 2f), 0f)
    }
}
