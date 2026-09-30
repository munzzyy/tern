package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.engine.real.Installs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DowngradeTest {
    @Test
    fun onlyAReleaseThePersonPickedMayGoBack() {
        assertTrue(Installs.allowsDowngrade(picked = true, setting = true) { true })
        // The background check, Update all and Install latest all start with no release picked.
        assertFalse(Installs.allowsDowngrade(picked = false, setting = true) { true })
        assertFalse(Installs.allowsDowngrade(picked = true, setting = false) { true })
        assertFalse(Installs.allowsDowngrade(picked = true, setting = true) { false })
        assertFalse(Installs.allowsDowngrade(picked = false, setting = true) { throw AssertionError("asked the device for nothing") })
    }

    @Test
    fun anOlderFileGoesInOnlyWhereItIsAllowed() {
        assertEquals(Downgrade.NONE, downgrade(file = 5, installed = null, allowed = false, claimed = null))
        assertEquals(Downgrade.NONE, downgrade(file = 5, installed = 5, allowed = false, claimed = 5))
        assertEquals(Downgrade.REFUSED, downgrade(file = 4, installed = 5, allowed = false, claimed = 4))
        assertEquals(Downgrade.ALLOWED, downgrade(file = 4, installed = 5, allowed = true, claimed = 4))
        assertEquals(Downgrade.ALLOWED, downgrade(file = 4, installed = 5, allowed = true, claimed = null))
    }

    @Test
    fun aSourceThatNamedANewerVersionThanItServedCannotRollTheAppBack() {
        // The store said 6, served a signed 4, and 5 is installed.
        assertEquals(Downgrade.NOT_AS_NAMED, downgrade(file = 4, installed = 5, allowed = true, claimed = 6))
        assertEquals(Downgrade.NOT_AS_NAMED, downgrade(file = 4, installed = 5, allowed = false, claimed = 6))
        // An update whose file differs from what was named still goes, as long as it goes forward.
        assertEquals(Downgrade.NONE, downgrade(file = 7, installed = 5, allowed = false, claimed = 8))
    }
}
