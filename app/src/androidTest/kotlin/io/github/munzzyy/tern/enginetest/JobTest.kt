package io.github.munzzyy.tern.enginetest

import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.work.Scheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JobTest {
    private val scheduler = targetContext.getSystemService(JobScheduler::class.java)

    @Before
    fun setUp() = uninstallFixture()

    @After
    fun tearDown() = scheduler.cancel(Scheduler.JOB_ID)

    @Test
    fun theScheduledJobSurvivesAForcedRunAndChecks() = runBlocking {
        Harness("job").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            assertNull(scheduler.getPendingJob(Scheduler.JOB_ID))

            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryHours = 6, onlyOnUnmetered = true))
            val job = checkNotNull(scheduler.getPendingJob(Scheduler.JOB_ID)) { "apply() scheduled nothing" }
            assertTrue(job.isPeriodic)
            assertEquals(6 * 60 * 60 * 1000L, job.intervalMillis)
            assertTrue(job.isPersisted)
            assertTrue(job.isRequireBatteryNotLow)
            @Suppress("DEPRECATION")
            assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, job.networkType)

            val listingsBefore = h.forge.requests.count { it.url.contains("/releases") }
            val out = shell("cmd jobscheduler run -f -u 0 ${targetContext.packageName} ${Scheduler.JOB_ID}")
            android.util.Log.i("EngineTest", "jobscheduler run said: ${out.trim()}")
            waitUntil(30_000, "the forced job to check the app") { h.state(id).lastCheckedMs != null }
            waitUntil(10_000, "the row to show the result") { h.row(id).status == AppStatus.NOT_INSTALLED }
            assertTrue(h.forge.requests.count { it.url.contains("/releases") } > listingsBefore)
            assertNotNull("the periodic job is gone after a forced run", scheduler.getPendingJob(Scheduler.JOB_ID))

            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryHours = 0))
            assertNull(scheduler.getPendingJob(Scheduler.JOB_ID))
        }
    }
}
