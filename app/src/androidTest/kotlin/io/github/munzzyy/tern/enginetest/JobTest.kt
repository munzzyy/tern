package io.github.munzzyy.tern.enginetest

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.work.Scheduler
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JobTest {
    private val scheduler = targetContext.getSystemService(JobScheduler::class.java)

    @Before
    fun setUp() = uninstallFixture()

    @After
    fun tearDown() {
        scheduler.cancel(Scheduler.JOB_ID)
        scheduler.cancel(Scheduler.RETRY_JOB_ID)
    }

    @Test
    fun theScheduledJobSurvivesAForcedRunAndChecks() = runBlocking {
        Harness("job").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            assertNull(scheduler.getPendingJob(Scheduler.JOB_ID))

            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryMinutes = 360, onlyOnUnmetered = true))
            val job = checkNotNull(scheduler.getPendingJob(Scheduler.JOB_ID)) { "apply() scheduled nothing" }
            assertTrue(job.isPeriodic)
            assertEquals(6 * 60 * 60 * 1000L, job.intervalMillis)
            assertTrue(job.isPersisted)
            assertTrue(job.isRequireBatteryNotLow)
            // Wi-Fi only holds back installs; the check itself runs on any network, as in Obtainium.
            @Suppress("DEPRECATION")
            assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
            assertFalse(job.isRequireCharging)
            val set = h.engine.background()
            assertTrue(set.scheduled)
            assertNotNull("a job set anew notes when", set.sinceMs)

            val listingsBefore = h.forge.requests.count { it.url.contains("/releases") }
            // A job an earlier test set, or this one as soon as it was set, may have run already.
            val forcedAt = System.currentTimeMillis()
            val out = shell("cmd jobscheduler run -f -u 0 ${targetContext.packageName} ${Scheduler.JOB_ID}")
            android.util.Log.i("EngineTest", "jobscheduler run said: ${out.trim()}")
            waitUntil(30_000, "the forced job to check the app") {
                (h.state(id).lastCheckedMs ?: 0L) >= forcedAt && h.forge.requests.count { it.url.contains("/releases") } > listingsBefore
            }
            waitUntil(10_000, "the row to show the result") { h.row(id).status == AppStatus.NOT_INSTALLED }
            assertNotNull("the periodic job is gone after a forced run", scheduler.getPendingJob(Scheduler.JOB_ID))
            waitUntil(10_000, "the run to be noted for settings") { (h.engine.background().lastRunMs ?: 0L) >= forcedAt }
            assertEquals("a run leaves the time the job was set alone", set.sinceMs, h.engine.background().sinceMs)
            assertNull("a run that went through has no reason to give", h.engine.background().lastRunStopped)

            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryMinutes = 0))
            assertNull(scheduler.getPendingJob(Scheduler.JOB_ID))
            assertFalse(h.engine.background().scheduled)
        }
    }

    @Test
    fun aRetryWaitsForWhatTheCheckWaitsForAndGoesWhenChecksAreOff() = runBlocking {
        Harness("job-retry").use { h ->
            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryMinutes = 360, checkOnlyOnUnmetered = true, checkOnlyWhileCharging = true))
            Scheduler.retry(targetContext, h.engine.settings.value, listOf("fixture"), attempt = 1, delayMs = 60_000)
            val retry = checkNotNull(scheduler.getPendingJob(Scheduler.RETRY_JOB_ID)) { "no retry was set" }
            val network = checkNotNull(retry.requiredNetwork) { "the retry asks for no network" }
            assertTrue(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            assertFalse("VALIDATED stays out, as it does for every job", network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            assertTrue(retry.isRequireCharging)
            assertTrue(retry.isRequireBatteryNotLow)

            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryMinutes = 0))
            assertNull("turning the checks off takes the retry with them", scheduler.getPendingJob(Scheduler.RETRY_JOB_ID))
            Scheduler.retry(targetContext, h.engine.settings.value, listOf("fixture"), attempt = 1, delayMs = 60_000)
            assertNull("no retry is set while the checks are off", scheduler.getPendingJob(Scheduler.RETRY_JOB_ID))
        }
    }

    @Test
    fun aBackgroundRestrictionIsReadFromAndroid() {
        val own = targetContext.packageName
        Harness("job-restricted").use { h ->
            assertFalse("Tern starts unrestricted here", h.engine.background().restricted)
            try {
                shell("appops set $own RUN_ANY_IN_BACKGROUND ignore")
                waitUntil(5_000, "Android to report the restriction") { h.engine.background().restricted }
            } finally {
                shell("appops set $own RUN_ANY_IN_BACKGROUND allow")
            }
            waitUntil(5_000, "the restriction to be lifted") { !h.engine.background().restricted }
        }
    }

    @Test
    fun checksTurnedOffWhileARunIsUnderwayLeaveNoRetry() = runBlocking {
        val hold = AtomicBoolean(false)
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val down = HttpClient {
            if (hold.get()) {
                asked.countDown()
                answer.await(30, TimeUnit.SECONDS)
            }
            throw IOException("no route to the forge")
        }
        Harness("job-retry-midway", http = down).use { h ->
            Assume.assumeTrue("this device is offline, and then the run stops before it checks", h.engine.online.value)
            h.addFixture()
            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryMinutes = 360))
            scheduler.cancel(Scheduler.JOB_ID)

            h.engine.runScheduledCheck()
            assertNotNull("a run that could not reach the forge is tried again", scheduler.getPendingJob(Scheduler.RETRY_JOB_ID))
            scheduler.cancel(Scheduler.RETRY_JOB_ID)

            hold.set(true)
            val run = launch(Dispatchers.IO) { h.engine.runScheduledCheck() }
            assertTrue("the run never asked the forge", asked.await(30, TimeUnit.SECONDS))
            h.engine.saveSettings(h.engine.settings.value.copy(checkEveryMinutes = 0))
            answer.countDown()
            run.join()
            assertNull("checks turned off while the run was underway leave no retry", scheduler.getPendingJob(Scheduler.RETRY_JOB_ID))
        }
    }
}
