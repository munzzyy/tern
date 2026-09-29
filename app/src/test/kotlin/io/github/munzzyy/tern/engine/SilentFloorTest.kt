package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.engine.real.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The floor quoted from PackageInstaller.SessionParams#setRequireUserAction on developer.android.com. */
class SilentFloorTest {
    @Test
    fun matchesTheDocumentedTable() {
        assertNull(Device.silentTargetFloor(29))
        assertNull(Device.silentTargetFloor(30))
        assertEquals(29, Device.silentTargetFloor(31))
        assertEquals(29, Device.silentTargetFloor(32))
        assertEquals(30, Device.silentTargetFloor(33))
        assertEquals(31, Device.silentTargetFloor(34))
        assertEquals(33, Device.silentTargetFloor(35))
        assertEquals(34, Device.silentTargetFloor(36))
        assertEquals(35, Device.silentTargetFloor(37))
        assertEquals(36, Device.silentTargetFloor(38))
    }
}
