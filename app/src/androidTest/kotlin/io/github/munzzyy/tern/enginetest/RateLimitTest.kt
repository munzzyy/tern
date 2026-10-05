package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.ProblemKind
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A server that asks a download to wait: up to a minute is waited out, and a longer wait stops the download, saying until when. */
@RunWith(AndroidJUnit4::class)
class RateLimitTest {
    private val file = "${FakeForge.FILES}v1.0/app-v1.apk"

    @After
    fun tearDown() {
        Prompt.dismiss()
        uninstallFixture()
    }

    /** The forge, with its first answer for the file a 429 that asks for [retryAfter] seconds; [asked] says when each request came. */
    private fun limitedOnce(retryAfter: Int, asked: MutableList<Long>): Routes {
        val forge = FakeForge().also { it.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk"))))) }
        return Routes(forge).on(file) { request ->
            asked += System.currentTimeMillis()
            if (asked.size == 1) HttpResponse.of(429, "slow down", Headers.of("Retry-After" to "$retryAfter"), request.url) else forge.execute(request)
        }
    }

    @Test
    fun aWaitOfTwentySecondsIsSatOutAndTheDownloadGoesOn() = runBlocking {
        prepareDevice()
        uninstallFixture()
        val asked = CopyOnWriteArrayList<Long>()
        Harness("ratelimit-short", http = limitedOnce(20, asked)).use { h ->
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(60_000, "the install to wait for the user") { h.row(id).progress?.phase == Phase.WAITING_FOR_USER }
            assertEquals(h.describe(id), 2, asked.size)
            assertTrue("asked again after ${asked[1] - asked[0]} ms", asked[1] - asked[0] >= 19_000)
            h.engine.cancel(id)
        }
    }

    @Test
    fun aWaitOfTwoMinutesStopsTheDownloadAndSaysUntilWhen() = runBlocking {
        val asked = CopyOnWriteArrayList<Long>()
        Harness("ratelimit-long", http = limitedOnce(120, asked)).use { h ->
            val id = h.addFixture()
            h.engine.check(id)
            val started = System.currentTimeMillis()
            h.engine.install(id)
            waitUntil(30_000, "the install to stop") { h.state(id).installProblem != null }
            val problem = h.state(id).installProblem ?: error("No problem kept: ${h.describe(id)}")
            assertEquals(h.describe(id), ProblemKind.RATE_LIMITED, problem.kind)
            assertTrue(h.describe(id), (problem.retryAtMs ?: 0) > started + 100_000)
            assertEquals(1, asked.size)
            assertTrue("gave up after ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 15_000)
        }
    }
}
