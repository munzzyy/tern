package io.github.munzzyy.tern.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import io.github.munzzyy.tern.engine.Settings

/**
 * One persisted periodic job, which checks on any network. Re-applying unchanged settings leaves
 * the job alone so its clock keeps running. Two one-off jobs go with it: one that installs what
 * waited for Wi-Fi or charging once there is, and one that checks again what could not be checked.
 */
object Scheduler {
    const val JOB_ID = 1
    const val WAITING_JOB_ID = 2
    const val RETRY_JOB_ID = 3
    const val EXTRA_ATTEMPT = "attempt"
    const val EXTRA_APPS = "apps"
    private const val MINUTE_MS = 60L * 1000
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
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .build()
        val current = scheduler.getPendingJob(JOB_ID)
        if (current != null && same(current, wanted)) return true
        val result = scheduler.schedule(wanted)
        if (result != JobScheduler.RESULT_SUCCESS) Log.e(TAG, "JobScheduler refused the periodic check")
        return result == JobScheduler.RESULT_SUCCESS
    }

    fun isScheduled(context: Context): Boolean = context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

    /** Runs the check again, and installs what waited, as soon as the network and the charger are what the settings ask for. */
    fun waitForInstalls(context: Context, settings: Settings) {
        val job = JobInfo.Builder(WAITING_JOB_ID, ComponentName(context, CheckJobService::class.java))
            .setPersisted(true)
            .setRequiredNetworkType(if (settings.onlyOnUnmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(settings.onlyWhileCharging)
            .setRequiresBatteryNotLow(true)
            .build()
        if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) Log.e(TAG, "JobScheduler refused the waiting install")
    }

    /** Checks [apps] again after [delayMs], for the [attempt]th time. */
    fun retry(context: Context, apps: Collection<String>, attempt: Int, delayMs: Long) {
        val extras = PersistableBundle().apply {
            putInt(EXTRA_ATTEMPT, attempt)
            putStringArray(EXTRA_APPS, apps.toTypedArray())
        }
        val job = JobInfo.Builder(RETRY_JOB_ID, ComponentName(context, CheckJobService::class.java))
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs)
            .setExtras(extras)
            .build()
        if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) Log.e(TAG, "JobScheduler refused the retry")
    }

    private fun same(a: JobInfo, b: JobInfo): Boolean =
        a.intervalMillis == b.intervalMillis && a.isPersisted == b.isPersisted && a.requiredNetwork == b.requiredNetwork &&
            a.isRequireCharging == b.isRequireCharging && a.isRequireBatteryNotLow == b.isRequireBatteryNotLow &&
            a.service == b.service
}
