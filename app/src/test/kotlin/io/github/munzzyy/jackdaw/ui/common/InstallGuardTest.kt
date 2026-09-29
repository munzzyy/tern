package io.github.munzzyy.jackdaw.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallGuardTest {
    private var allowed = false
    private val guard = InstallGuard { allowed }

    @Test
    fun anInstallStartsAtOnceWhenAndroidAlreadyAllowsIt() {
        allowed = true
        assertTrue(guard.admit(Wanted.One("a")))
        assertEquals(emptyList<Wanted>(), guard.waiting)
    }

    @Test
    fun anInstallIsKeptWhileAndroidHasNotAllowedIt() {
        assertFalse(guard.admit(Wanted.One("a", "v2", "https://example.com/a.apk")))
        assertFalse(guard.admit(Wanted.AllUpdates))
        assertEquals(listOf(Wanted.One("a", "v2", "https://example.com/a.apk"), Wanted.AllUpdates), guard.waiting)
    }

    @Test
    fun askingTwiceForTheSameInstallKeepsItOnce() {
        guard.admit(Wanted.One("a"))
        guard.admit(Wanted.One("a"))
        assertEquals(listOf<Wanted>(Wanted.One("a")), guard.waiting)
    }

    @Test
    fun comingBackWithTheAnswerYesStartsWhatWasKept() {
        guard.admit(Wanted.One("a"))
        guard.admit(Wanted.One("b"))
        allowed = true
        assertEquals(listOf<Wanted>(Wanted.One("a"), Wanted.One("b")), guard.release())
        assertEquals(emptyList<Wanted>(), guard.waiting)
    }

    @Test
    fun comingBackWithoutTheAnswerStartsNothingAndAsksAgainNextTime() {
        guard.admit(Wanted.One("a"))
        assertEquals(emptyList<Wanted>(), guard.release())
        assertEquals(emptyList<Wanted>(), guard.waiting)
        assertFalse(guard.admit(Wanted.One("a")))
        assertEquals(listOf<Wanted>(Wanted.One("a")), guard.waiting)
    }

    @Test
    fun aDeviceWithNoSettingsScreenLetsAndroidAskInItsOwnWay() {
        guard.admit(Wanted.One("a"))
        assertEquals(listOf<Wanted>(Wanted.One("a")), guard.release(anyway = true))
    }

    @Test
    fun notNowForgetsWhatWasKept() {
        guard.admit(Wanted.AllUpdates)
        guard.forget()
        allowed = true
        assertEquals(emptyList<Wanted>(), guard.release())
    }
}
