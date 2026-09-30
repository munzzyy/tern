package io.github.munzzyy.tern.ui.settings

import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.InstallerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows of Installing in Settings with Dhizuku chosen. */
class DhizukuRowsTest {
    @Test
    fun whatDhizukuCannotDoIsNotOffered() {
        // Dhizuku's own package makes the session: Tern cannot ask for update ownership through it.
        assertFalse(ownershipOffered(InstallerMode.DHIZUKU, 36))
        assertTrue(ownershipOffered(InstallerMode.SYSTEM, 36))
        assertTrue(ownershipOffered(InstallerMode.SHIZUKU, 34))
        assertFalse(ownershipOffered(InstallerMode.SYSTEM, 33))
        // Android's own permission to install plays no part when Dhizuku installs.
        assertFalse(asksAndroidToInstall(InstallerMode.DHIZUKU))
        assertFalse(asksAndroidToInstall(InstallerMode.OTHER_APP))
        for (mode in listOf(InstallerMode.SYSTEM, InstallerMode.SHIZUKU, InstallerMode.ROOT)) assertTrue(mode.name, asksAndroidToInstall(mode))
    }

    @Test
    fun aDhizukuThatIsNotReadyDoesNotSayThatAndroidsInstallerTakesOver() {
        assertEquals(R.string.installer_dhizuku_not_ready, notReadyTitle(InstallerMode.DHIZUKU))
        for (mode in listOf(InstallerMode.SHIZUKU, InstallerMode.ROOT, InstallerMode.OTHER_APP)) {
            assertEquals(mode.name, R.string.installer_not_ready, notReadyTitle(mode))
        }
    }
}
