package io.github.munzzyy.tern.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log is bounded, and Tern's own messages are bounded apart from what happened to apps. */
class EventLimitsTest {
    private fun event(id: Long, kind: EventKind) = Event(id, id * 1000, if (kind.isOwn) null else "a", if (kind.isOwn) null else "App", kind, "event $id")

    /** [count] events, newest first, every third one Tern's own. */
    private fun mixed(count: Int) = (count downTo 1).map { id -> event(id.toLong(), if (id % 3 == 0) EventKind.OWN_WARNING else EventKind.INSTALLED) }

    @Test
    fun onlyTheThreeKindsOfTernsOwnAreItsOwn() {
        val own = EventKind.entries.filter { it.isOwn }
        assertEquals(listOf(EventKind.OWN_NOTE, EventKind.OWN_WARNING, EventKind.OWN_ERROR), own)
    }

    @Test
    fun eachGroupKeepsItsNewestFiveHundredInTheOrderItCameIn() {
        val events = mixed(3_000)
        val kept = EventLimits.kept(events)
        val own = kept.filter { it.kind.isOwn }
        val apps = kept.filterNot { it.kind.isOwn }
        assertEquals(EventLimits.OWN, own.size)
        assertEquals(EventLimits.APPS, apps.size)
        assertEquals(events.filter { it.kind.isOwn }.take(EventLimits.OWN), own)
        assertEquals(events.filterNot { it.kind.isOwn }.take(EventLimits.APPS), apps)
        assertEquals(kept.sortedByDescending { it.id }, kept)
    }

    @Test
    fun manyMessagesOfTernsOwnNeverPushOutWhatHappenedToApps() {
        val apps = (1L..10L).map { event(it, EventKind.UPDATE_FOUND) }
        val flood = (1_011L downTo 11L).map { event(it, if (it % 2 == 0L) EventKind.OWN_ERROR else EventKind.OWN_NOTE) }
        val kept = EventLimits.kept(flood + apps.reversed())
        assertEquals(apps.reversed(), kept.filterNot { it.kind.isOwn })
        assertEquals(EventLimits.OWN, kept.count { it.kind.isOwn })
        assertEquals(flood.take(EventLimits.OWN), kept.filter { it.kind.isOwn })
    }

    @Test
    fun whatIsWithinTheLimitsIsKeptAsItIs() {
        val events = mixed(30)
        assertEquals(events, EventLimits.kept(events))
        assertTrue(EventLimits.kept(emptyList()).isEmpty())
    }

    @Test
    fun theOldestOfTernsOwnGoesFirst() {
        val own = (EventLimits.OWN + 1 downTo 1).map { event(it.toLong(), EventKind.OWN_NOTE) }
        val kept = EventLimits.kept(own)
        assertEquals(EventLimits.OWN, kept.size)
        assertFalse(kept.any { it.id == 1L })
        assertEquals((EventLimits.OWN + 1).toLong(), kept.first().id)
    }
}
