package io.github.munzzyy.stamp.ui.firstrun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunTest {
    @Test
    fun aPhoneThatHasNotSaidYesIsAsked() {
        assertTrue(asksAboutNotifications(television = false, sdk = 33, granted = false))
        assertTrue(asksAboutNotifications(television = false, sdk = 36, granted = false))
    }

    @Test
    fun aTelevisionIsNeverAsked() {
        assertFalse(asksAboutNotifications(television = true, sdk = 34, granted = false))
        assertFalse(asksAboutNotifications(television = true, sdk = 30, granted = false))
    }

    @Test
    fun nobodyIsAskedTwiceOrBeforeAndroid13() {
        assertFalse(asksAboutNotifications(television = false, sdk = 34, granted = true))
        assertFalse(asksAboutNotifications(television = false, sdk = 32, granted = false))
        assertFalse(asksAboutNotifications(television = false, sdk = 29, granted = false))
    }
}
