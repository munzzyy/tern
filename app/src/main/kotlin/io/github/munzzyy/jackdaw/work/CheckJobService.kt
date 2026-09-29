package io.github.munzzyy.jackdaw.work

import android.app.job.JobParameters
import android.app.job.JobService

class CheckJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean = false

    override fun onStopJob(params: JobParameters): Boolean = false
}
