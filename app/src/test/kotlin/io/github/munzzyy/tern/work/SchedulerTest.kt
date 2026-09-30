package io.github.munzzyy.tern.work

import android.app.job.JobInfo
import io.github.munzzyy.tern.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {
    @Test
    fun checksWaitForWiFiOnlyWhenTheCheckSettingSaysSo() {
        assertEquals(JobInfo.NETWORK_TYPE_ANY, Scheduler.checkNetwork(Settings(onlyOnUnmetered = true)))
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, Scheduler.checkNetwork(Settings(checkOnlyOnUnmetered = true)))
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, Scheduler.installNetwork(Settings(onlyOnUnmetered = true)))
        assertEquals(JobInfo.NETWORK_TYPE_ANY, Scheduler.installNetwork(Settings(checkOnlyOnUnmetered = true)))
    }
}
