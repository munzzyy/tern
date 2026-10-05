package io.github.munzzyy.tern.work

import io.github.munzzyy.tern.engine.Progress

/**
 * Lets a stream of progress through at most once every [intervalMs]. The first report goes
 * through, and so does every change that is more than a byte count, at once.
 */
class Pace(private val intervalMs: Long) {
    private var last = 0L
    private var started = false

    /** Whether the report at [nowMs] goes out; [changed] is true when it says more than how many bytes are done. */
    @Synchronized
    fun due(nowMs: Long, changed: Boolean): Boolean {
        // A clock that went back is not waited out.
        if (started && !changed && nowMs >= last && nowMs - last < intervalMs) return false
        started = true
        last = nowMs
        return true
    }

    companion object {
        /** Whether going from [before] to [after] moves only the bytes done, the one change that can wait. */
        fun onlyBytes(before: Progress?, after: Progress?): Boolean =
            before != null && after != null && before != after && before.copy(bytesDone = 0) == after.copy(bytesDone = 0)

        /** The same for every transfer at once: the same ones, in the same phases, and some of them further along. */
        fun onlyBytes(before: Map<String, Progress>, after: Map<String, Progress>): Boolean =
            before.keys == after.keys && before != after && before.all { (key, progress) -> progress.copy(bytesDone = 0) == after.getValue(key).copy(bytesDone = 0) }
    }
}
