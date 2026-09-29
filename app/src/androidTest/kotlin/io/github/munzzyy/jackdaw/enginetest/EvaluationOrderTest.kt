package io.github.munzzyy.jackdaw.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.source.SourceListing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvaluationOrderTest {
    private fun release(n: Int) = Release("v$n.0", "$n.0", assets = listOf(Asset("app-$n.apk", "https://forge.test/files/app-$n.apk", size = 10)))

    /** A check that stores a new listing and an install that finishes evaluate the same app at the same moment. */
    @Test
    fun theRowEndsOnWhatIsStoredHoweverTwoEvaluationsInterleave() = runBlocking {
        Harness("evaluation-order").use { h ->
            val id = h.addFixture()
            val pool = Executors.newFixedThreadPool(2)
            val stale = ArrayList<String>()
            try {
                repeat(ROUNDS) { round ->
                    val start = CountDownLatch(1)
                    val fromOldState = pool.submit {
                        start.await()
                        h.engine.checks.reevaluate(id, network = false)
                    }
                    val afterNewListing = pool.submit {
                        start.await()
                        h.engine.checks.storeListing(id, SourceListing(listOf(release(round + 2), release(1))), 0)
                        h.engine.checks.reevaluate(id, network = false)
                    }
                    start.countDown()
                    fromOldState.get()
                    afterNewListing.get()
                    val stored = h.engine.stored[id]!!.state.releases.first().id
                    val shown = h.engine.evaluations[id]?.latest?.id
                    if (shown != stored) stale += "round $round shows $shown, stored is $stored"
                }
            } finally {
                pool.shutdownNow()
            }
            assertEquals("${stale.size} of $ROUNDS rounds ended on an older evaluation", emptyList<String>(), stale.take(3))
        }
    }

    private companion object {
        const val ROUNDS = 2000
    }
}
