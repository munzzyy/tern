package io.github.munzzyy.jackdaw.work

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import io.github.munzzyy.jackdaw.engine.real.RealEngine
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CheckJobService : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val engine = RealEngine.obtain(applicationContext)
        val job = engine.scope.launch {
            var failed = false
            try {
                engine.runScheduledCheck()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Background check failed", e)
                failed = true
            } finally {
                running.remove(params.jobId)
            }
            jobFinished(params, failed)
        }
        running[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    private companion object {
        const val TAG = "JackdawJob"
    }
}
