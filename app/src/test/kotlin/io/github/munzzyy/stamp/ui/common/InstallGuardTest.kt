package io.github.munzzyy.stamp.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallGuardTest {
    private var allowed = false
    private var now = 1_000_000L
    private val store = object : WishStore {
        var text: String? = null

        override fun read(): Kept? = WishText.decode(text)

        override fun write(kept: Kept) {
            text = WishText.encode(kept)
        }

        override fun clear() {
            text = null
        }
    }
    private val guard = InstallGuard({ allowed }, store, { now })

    private fun afterARestart() = InstallGuard({ allowed }, store, { now })

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

    @Test
    fun whatWasWantedIsCarriedOverWhenAndroidClosedTheAppOnYes() {
        guard.admit(Wanted.One("a", "v2", "https://example.com/a.apk"))
        guard.admit(Wanted.AllUpdates)
        allowed = true
        now += 60_000

        val next = afterARestart()

        assertEquals(listOf(Wanted.One("a", "v2", "https://example.com/a.apk"), Wanted.AllUpdates), next.carriedOver())
        assertEquals(emptyList<Wanted>(), next.carriedOver())
        assertEquals(emptyList<Wanted>(), next.waiting)
        assertEquals(emptyList<Wanted>(), afterARestart().carriedOver())
    }

    @Test
    fun afterARestartWithoutTheAnswerTheQuestionIsShownAgain() {
        guard.admit(Wanted.One("a"))

        val next = afterARestart()

        assertEquals(emptyList<Wanted>(), next.carriedOver())
        assertEquals(listOf<Wanted>(Wanted.One("a")), next.waiting)
    }

    @Test
    fun whatWasWantedLongAgoIsNotStartedBehindTheUsersBack() {
        guard.admit(Wanted.One("a"))
        allowed = true
        now += InstallGuard.KEEP_MS + 1

        val next = afterARestart()

        assertEquals(emptyList<Wanted>(), next.carriedOver())
        assertEquals(emptyList<Wanted>(), next.waiting)
        assertEquals(null, store.text)
    }

    @Test
    fun aClockThatWentBackwardsStartsNothing() {
        guard.admit(Wanted.One("a"))
        allowed = true
        now -= 1

        assertEquals(emptyList<Wanted>(), afterARestart().carriedOver())
    }

    @Test
    fun anAnswerOrNotNowLeavesNothingBehindForTheNextStart() {
        guard.admit(Wanted.One("a"))
        guard.release()
        guard.admit(Wanted.One("b"))
        guard.forget()
        allowed = true

        assertEquals(emptyList<Wanted>(), afterARestart().carriedOver())
    }

    @Test
    fun whatIsKeptReadsBackAsItWasWritten() {
        val kept = Kept(listOf(Wanted.One("a"), Wanted.One("b", "v1.0", null), Wanted.One("c", null, "https://example.com/c.apk"), Wanted.AllUpdates), 42)
        assertEquals(kept, WishText.decode(WishText.encode(kept)))
    }

    @Test
    fun aWishThatCouldBreakOutOfItsLineIsNotKept() {
        val kept = Kept(listOf(Wanted.One("a\tb"), Wanted.One("c", "v1\nall\nrest"), Wanted.One("d")), 42)
        assertEquals(Kept(listOf(Wanted.One("d")), 42), WishText.decode(WishText.encode(kept)))
    }

    @Test
    fun textThatIsNotOursReadsAsNothing() {
        for (text in listOf(null, "", "soon", "42", "42\n", "42\nsome\tthing", "42\none\t\t\t", "42\none\ta\tb")) {
            assertEquals(text, null, WishText.decode(text))
        }
    }
}
