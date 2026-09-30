package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Progress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job

/**
 * Files on their way to Downloads, each under a key of its own that no app id can take. The
 * notification of downloads names and counts them with the installs, and its Cancel stops them.
 */
internal class Saves {
    private class Entry(val name: String, @Volatile var progress: Progress)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private val next = AtomicLong()

    /** Starts to count the file [name], of [total] bytes where that is known, and returns its key. */
    fun begin(name: String, total: Long?): String {
        val key = PREFIX + next.incrementAndGet()
        entries[key] = Entry(name, Progress(Phase.DOWNLOADING, 0, total))
        return key
    }

    fun progress(key: String, done: Long, total: Long?) {
        entries[key]?.progress = Progress(Phase.DOWNLOADING, done, total)
    }

    fun end(key: String) {
        entries.remove(key)
    }

    /** How far each file is, by its key. */
    fun all(): Map<String, Progress> = entries.mapValues { it.value.progress }

    /** The name of the file under [key], or null when [key] is no save. */
    fun name(key: String): String? = entries[key]?.name

    fun track(job: Job) {
        jobs += job
        job.invokeOnCompletion { jobs -= job }
    }

    fun cancelAll() {
        for (job in jobs.toList()) job.cancel()
    }

    companion object {
        /** Keys start with a character no app id holds. */
        const val PREFIX = "save:"
    }
}
