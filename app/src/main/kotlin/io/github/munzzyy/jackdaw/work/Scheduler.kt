package io.github.munzzyy.jackdaw.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import io.github.munzzyy.jackdaw.engine.Settings

/** One persisted periodic job. Re-applying unchanged settings leaves the job alone so its clock keeps running. */
object Scheduler {
    const val JOB_ID = 1
    private const val HOUR_MS = 60L * 60 * 1000
    private const val TAG = "JackdawScheduler"

    fun apply(context: Context, settings: Settings): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (settings.checkEveryHours <= 0) {
            scheduler.cancel(JOB_ID)
            return false
        }
        val wanted = JobInfo.Builder(JOB_ID, ComponentName(context, CheckJobService::class.java))
            .setPeriodic(settings.checkEveryHours * HOUR_MS)
            .setPersisted(true)
            .setRequiredNetworkType(if (settings.onlyOnUnmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(settings.onlyWhileCharging)
            .setRequiresBatteryNotLow(true)
            .build()
        val current = scheduler.getPendingJob(JOB_ID)
        if (current != null && same(current, wanted)) return true
        val result = scheduler.schedule(wanted)
        if (result != JobScheduler.RESULT_SUCCESS) Log.e(TAG, "JobScheduler refused the periodic check")
        return result == JobScheduler.RESULT_SUCCESS
    }

    fun isScheduled(context: Context): Boolean = context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

    private fun same(a: JobInfo, b: JobInfo): Boolean =
        a.intervalMillis == b.intervalMillis && a.isPersisted == b.isPersisted && a.requiredNetwork == b.requiredNetwork &&
            a.isRequireCharging == b.isRequireCharging && a.isRequireBatteryNotLow == b.isRequireBatteryNotLow &&
            a.service == b.service
}
