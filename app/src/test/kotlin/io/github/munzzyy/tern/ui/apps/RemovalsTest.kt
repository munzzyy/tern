package io.github.munzzyy.tern.ui.apps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalsTest {
    private val removals = Removals()
    private val removed = mutableListOf<String>()

    @Test
    fun withoutUndoTheAppIsRemovedOnceTheOfferHasGone() = runBlocking {
        val answer = CompletableDeferred<Boolean>()
        val job = launch(Dispatchers.Unconfined) { removals.remove("a", offerUndo = { answer.await() }) { removed += "a" } }
        assertEquals(setOf("a"), removals.hidden.value)
        assertEquals("nothing is removed while Undo is on offer", emptyList<String>(), removed)
        answer.complete(false)
        job.join()
        assertEquals(listOf("a"), removed)
        assertEquals(emptySet<String>(), removals.hidden.value)
    }

    @Test
    fun undoBringsTheAppBackAndRemovesNothing() = runBlocking {
        removals.remove("a", offerUndo = { true }) { removed += "a" }
        assertEquals(emptyList<String>(), removed)
        assertEquals(emptySet<String>(), removals.hidden.value)
    }

    @Test
    fun anOfferThatIsCutShortRemovesNothing() = runBlocking {
        val never = CompletableDeferred<Boolean>()
        val job = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { removals.remove("a", offerUndo = { never.await() }) { removed += "a" } }
        assertEquals(setOf("a"), removals.hidden.value)
        job.cancelAndJoin()
        assertEquals(emptyList<String>(), removed)
        assertEquals(emptySet<String>(), removals.hidden.value)
    }

    @Test
    fun aRemovalThatFailsShowsTheAppAgain() = runBlocking {
        val failure = runCatching { removals.remove("a", offerUndo = { false }) { error("no") } }
        assertTrue(failure.isFailure)
        assertEquals(emptySet<String>(), removals.hidden.value)
    }

    @Test
    fun twoAppsCanBeOnOfferAtOnce() = runBlocking {
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        val a = launch(Dispatchers.Unconfined) { removals.remove("a", offerUndo = { first.await() }) { removed += "a" } }
        val b = launch(Dispatchers.Unconfined) { removals.remove("b", offerUndo = { second.await() }) { removed += "b" } }
        assertEquals(setOf("a", "b"), removals.hidden.value)
        first.complete(false)
        a.join()
        assertEquals(setOf("b"), removals.hidden.value)
        second.complete(true)
        b.join()
        assertEquals(listOf("a"), removed)
        assertEquals(emptySet<String>(), removals.hidden.value)
    }
}
