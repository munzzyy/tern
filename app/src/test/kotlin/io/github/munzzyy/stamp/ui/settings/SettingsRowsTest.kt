package io.github.munzzyy.stamp.ui.settings

import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.OrbotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRowsTest {
    @Test
    fun everyStateOfOrbotHasItsSentenceAndItsAction() {
        assertEquals(OrbotWords(R.string.orbot_not_installed, R.string.orbot_get), orbotWords(OrbotState.NOT_INSTALLED))
        assertEquals(OrbotWords(R.string.orbot_off, R.string.orbot_start), orbotWords(OrbotState.OFF))
        assertEquals(OrbotWords(R.string.orbot_starting, null), orbotWords(OrbotState.STARTING))
        assertEquals(OrbotWords(R.string.orbot_on, null), orbotWords(OrbotState.ON))
        assertEquals(OrbotWords(R.string.orbot_unknown, R.string.orbot_ask_again), orbotWords(OrbotState.UNKNOWN))
        assertEquals(OrbotState.entries.size, OrbotState.entries.map { orbotWords(it).sentence }.distinct().size)
    }

    @Test
    fun aTelevisionShowsNoRowsAboutNotifications() {
        assertFalse(showsNotifications(television = true))
        assertTrue(showsNotifications(television = false))
    }

    @Test
    fun beingTheOnlyUpdaterIsOfferedFromAndroid14On() {
        assertFalse(ownershipSupported(29))
        assertFalse(ownershipSupported(33))
        assertTrue(ownershipSupported(34))
        assertTrue(ownershipSupported(36))
    }

    @Test
    fun orbotIsFetchedFromItsOwnDevelopersOverHttps() {
        assertEquals("https://github.com/guardianproject/orbot-android", ORBOT_URL)
    }
}
