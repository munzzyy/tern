package io.github.munzzyy.tern.ui.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LostFocusTest {
    private val watch = LostFocus()

    @Test
    fun whatNeverHadFocusDidNotLoseIt() {
        assertFalse(watch.hadFocusUntilNow(1_000))
        watch.seen(hasFocus = false, nowMs = 900)
        assertFalse(watch.hadFocusUntilNow(1_000))
    }

    @Test
    fun whatStillHasFocusHadItUntilNow() {
        watch.seen(hasFocus = true, nowMs = 100)
        assertTrue(watch.hadFocusUntilNow(60_000))
    }

    @Test
    fun focusTakenAwayAMomentAgoCountsAsHadUntilNow() {
        watch.seen(hasFocus = true, nowMs = 100)
        watch.seen(hasFocus = false, nowMs = 5_000)
        assertTrue(watch.hadFocusUntilNow(5_000))
        assertTrue(watch.hadFocusUntilNow(5_016))
    }

    @Test
    fun focusTheUserWalkedAwayWithLongAgoDoesNotCount() {
        watch.seen(hasFocus = true, nowMs = 100)
        watch.seen(hasFocus = false, nowMs = 5_000)
        assertFalse(watch.hadFocusUntilNow(6_000))
        watch.seen(hasFocus = false, nowMs = 5_990)
        assertFalse("hearing again that there is no focus is not losing it again", watch.hadFocusUntilNow(6_000))
    }
}
