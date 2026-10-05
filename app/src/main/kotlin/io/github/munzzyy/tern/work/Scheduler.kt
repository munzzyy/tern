package io.github.munzzyy.tern.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.log.TernLog

/**
 * One persisted periodic job, which checks on any network unless the settings hold checks back.
 * Re-applying unchanged settings leaves the job alone so its clock keeps running. Two one-off jobs
 * go with it: one that installs what waited for Wi-Fi or charging once there is, and one that
 * checks again what could not be checked.
 */
object Scheduler {
    const val JOB_ID = 1
    const val WAITING_JOB_ID = 2
    const val RETRY_JOB_ID = 3
    const val EXTRA_ATTEMPT = "attempt"
    const val EXTRA_APPS = "apps"
    private const val MINUTE_MS = 60L * 1000

    /** The waiting job runs no sooner than this after it is set, so it can never go round in a loop. */
    const val WAITING_LATENCY_MS = 15 * MINUTE_MS
    private const val TAG = "TernScheduler"

    fun apply(context: Context, settings: Settings): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (settings.checkEveryMinutes <= 0) {
            scheduler.cancel(JOB_ID)
            return false
        }
        val wanted = JobInfo.Builder(JOB_ID, ComponentName(context, CheckJobService::class.java))
            .setPeriodic(settings.checkEveryMinutes * MINUTE_MS)
            .setPersisted(true)
            .setRequiredNetwork(networkRequest(settings.checkOnlyOnUnmetered))
            .setRequiresCharging(settings.checkOnlyWhileCharging)
            .setRequiresBatteryNotLow(true)
            .build()
        val current = scheduler.getPendingJob(JOB_ID)
        if (current != null && same(current, wanted)) return true
        val result = scheduler.schedule(wanted)
        if (result != JobScheduler.RESULT_SUCCESS) TernLog.e(TAG, "JobScheduler refused the periodic check")
        return result == JobScheduler.RESULT_SUCCESS
    }

    fun isScheduled(context: Context): Boolean = context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

    /**
     * Installs what waited, from what the last check found, once the network and the charger are
     * what the settings ask for. It checks nothing again but what an app asks to have checked first.
     */
    fun waitForInstalls(context: Context, settings: Settings) {
        val job = JobInfo.Builder(WAITING_JOB_ID, ComponentName(context, CheckJobService::class.java))
            .setPersisted(true)
            .setMinimumLatency(WAITING_LATENCY_MS)
            .setRequiredNetwork(networkRequest(settings.onlyOnUnmetered))
            .setRequiresCharging(settings.onlyWhileCharging)
            .setRequiresBatteryNotLow(true)
            .build()
        if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) TernLog.e(TAG, "JobScheduler refused the waiting install")
    }

    /** Checks [apps] again after [delayMs], for the [attempt]th time. */
    fun retry(context: Context, apps: Collection<String>, attempt: Int, delayMs: Long) {
        val extras = PersistableBundle().apply {
            putInt(EXTRA_ATTEMPT, attempt)
            putStringArray(EXTRA_APPS, apps.toTypedArray())
        }
        val job = JobInfo.Builder(RETRY_JOB_ID, ComponentName(context, CheckJobService::class.java))
            .setPersisted(true)
            .setRequiredNetwork(networkRequest(unmetered = false))
            .setMinimumLatency(delayMs)
            .setExtras(extras)
            .build()
        if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) TernLog.e(TAG, "JobScheduler refused the retry")
    }

    /** Not VALIDATED, which setRequiredNetworkType adds: Android's probe to Google fails where Google is blocked. */
    private fun networkRequest(unmetered: Boolean): NetworkRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .apply { if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()

    /**
     * Whether a run installs now. The waiting job runs only once JobScheduler found the network
     * and the charger the settings ask for, so it takes that as settled; asking the device again
     * could get another answer (a charger that paused at 80 %) and hold the installs once more.
     */
    fun installsNow(settings: Settings, waitingJob: Boolean, unmetered: () -> Boolean, charging: () -> Boolean): Boolean =
        settings.autoInstalls && (waitingJob || (!settings.onlyOnUnmetered || unmetered()) && (!settings.onlyWhileCharging || charging()))

    /** Whether a run that held installs back sets the waiting job. The waiting job never sets itself again. */
    fun armsWaiting(settings: Settings, waitingJob: Boolean, waited: Boolean): Boolean = !waitingJob && waited && settings.autoInstalls

    private fun same(a: JobInfo, b: JobInfo): Boolean =
        a.intervalMillis == b.intervalMillis && a.isPersisted == b.isPersisted && a.requiredNetwork == b.requiredNetwork &&
            a.isRequireCharging == b.isRequireCharging && a.isRequireBatteryNotLow == b.isRequireBatteryNotLow &&
            a.service == b.service
}
