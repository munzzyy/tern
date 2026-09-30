package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.InstallerReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which installer an install goes to while the one chosen cannot be used. */
class InstallerChoiceTest {
    @Test
    fun dhizukuNeverHandsAnInstallToAnotherInstaller() {
        for (readiness in InstallerReadiness.entries) {
            assertEquals(readiness.name, InstallerMode.DHIZUKU, effectiveInstaller(InstallerMode.DHIZUKU, readiness))
        }
    }

    @Test
    fun theOthersLeaveItToAndroidUntilTheyAreReady() {
        assertEquals(InstallerMode.SHIZUKU, effectiveInstaller(InstallerMode.SHIZUKU, InstallerReadiness.READY))
        assertEquals(InstallerMode.SYSTEM, effectiveInstaller(InstallerMode.SHIZUKU, InstallerReadiness.SHIZUKU_NOT_RUNNING))
        assertEquals(InstallerMode.ROOT, effectiveInstaller(InstallerMode.ROOT, InstallerReadiness.READY))
        assertEquals(InstallerMode.SYSTEM, effectiveInstaller(InstallerMode.ROOT, InstallerReadiness.NO_ROOT))
        assertEquals(InstallerMode.SYSTEM, effectiveInstaller(InstallerMode.OTHER_APP, InstallerReadiness.NO_OTHER_APP))
        assertEquals(InstallerMode.SYSTEM, effectiveInstaller(InstallerMode.SYSTEM, InstallerReadiness.READY))
    }

    @Test
    fun anInstallThatGoesToAnotherInstallerSaysWhichOneWasChosen() {
        assertEquals(InstallerMode.SHIZUKU, fellBackFrom(InstallerMode.SHIZUKU, InstallerReadiness.SHIZUKU_NOT_RUNNING))
        assertEquals(InstallerMode.ROOT, fellBackFrom(InstallerMode.ROOT, InstallerReadiness.NO_ROOT))
        assertEquals(InstallerMode.OTHER_APP, fellBackFrom(InstallerMode.OTHER_APP, InstallerReadiness.NO_OTHER_APP))
        assertNull(fellBackFrom(InstallerMode.SHIZUKU, InstallerReadiness.READY))
        assertNull(fellBackFrom(InstallerMode.SYSTEM, InstallerReadiness.READY))
        assertNull(fellBackFrom(InstallerMode.DHIZUKU, InstallerReadiness.DHIZUKU_NOT_ANSWERING))
    }
}
