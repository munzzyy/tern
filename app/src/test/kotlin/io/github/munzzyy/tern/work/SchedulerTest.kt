package io.github.munzzyy.tern.work

import android.app.job.JobInfo
import io.github.munzzyy.tern.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    private val held = Settings(autoInstalls = true, onlyOnUnmetered = true, onlyWhileCharging = true)
    private val never = { throw AssertionError("the waiting job asked the device again") }

    @Test
    fun theWaitingJobInstallsWhateverTheDeviceSaysAndNeverAsksIt() {
        // JobScheduler counted a charger paused at 80 % as charging; BatteryManager would say no.
        assertTrue(Scheduler.installsNow(held, waitingJob = true, unmetered = never, charging = never))
        assertFalse(Scheduler.installsNow(held.copy(autoInstalls = false), waitingJob = true, unmetered = never, charging = never))
    }

    @Test
    fun theWaitingJobNeverSetsItselfAgain() {
        assertFalse(Scheduler.armsWaiting(held, waitingJob = true, waited = true))
        assertFalse(Scheduler.armsWaiting(held, waitingJob = true, waited = false))
    }

    @Test
    fun aPeriodicRunThatHeldInstallsBackSetsOneWaitingJob() {
        assertFalse(Scheduler.installsNow(held, waitingJob = false, unmetered = { false }, charging = { true }))
        assertFalse(Scheduler.installsNow(held, waitingJob = false, unmetered = { true }, charging = { false }))
        assertTrue(Scheduler.installsNow(held, waitingJob = false, unmetered = { true }, charging = { true }))
        assertTrue(Scheduler.armsWaiting(held, waitingJob = false, waited = true))
        assertFalse(Scheduler.armsWaiting(held, waitingJob = false, waited = false))
        assertFalse(Scheduler.armsWaiting(held.copy(autoInstalls = false), waitingJob = false, waited = true))
    }

    @Test
    fun theWaitingJobWaitsAQuarterOfAnHourAtLeast() {
        assertEquals(15 * 60 * 1000L, Scheduler.WAITING_LATENCY_MS)
    }

    @Test
    fun checksWaitForWiFiOnlyWhenTheCheckSettingSaysSo() {
        assertEquals(JobInfo.NETWORK_TYPE_ANY, Scheduler.checkNetwork(Settings(onlyOnUnmetered = true)))
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, Scheduler.checkNetwork(Settings(checkOnlyOnUnmetered = true)))
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, Scheduler.installNetwork(Settings(onlyOnUnmetered = true)))
        assertEquals(JobInfo.NETWORK_TYPE_ANY, Scheduler.installNetwork(Settings(checkOnlyOnUnmetered = true)))
    }
}
