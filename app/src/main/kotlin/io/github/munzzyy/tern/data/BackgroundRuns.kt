package io.github.munzzyy.tern.data

import android.content.Context
import android.content.SharedPreferences
import io.github.munzzyy.tern.engine.RunStop
import io.github.munzzyy.tern.engine.Settings

/**
 * Two times and one reason and nothing else: when the periodic check last ran to its end, when its
 * job was last set anew, and why the last run the job started reached nothing. Settings tells from
 * them whether Android lets the check run, and whether it got anywhere.
 */
class BackgroundRuns(context: Context, name: String = DEFAULT_NAME) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun lastRunMs(): Long? = prefs.getLong(LAST_RUN, 0L).takeIf { it > 0L }

    fun sinceMs(): Long? = prefs.getLong(SINCE, 0L).takeIf { it > 0L }

    fun stoppedBy(): RunStop? = prefs.getString(STOPPED, null)?.let { name -> RunStop.entries.firstOrNull { it.name == name } }

    fun ran(atMs: Long) {
        prefs.edit().putLong(LAST_RUN, atMs).commit()
    }

    fun setAnew(atMs: Long) {
        prefs.edit().putLong(SINCE, atMs).commit()
    }

    /** [why] is null for a run that went through. */
    fun stopped(why: RunStop?) {
        if (stoppedBy() == why) return
        prefs.edit().apply { if (why == null) remove(STOPPED) else putString(STOPPED, why.name) }.commit()
    }

    companion object {
        const val DEFAULT_NAME = "background"
        private const val LAST_RUN = "lastRunMs"
        private const val SINCE = "sinceMs"
        private const val STOPPED = "stoppedBy"

        /**
         * Whether the job that is set now counts as set anew: there was none before, the settings
         * it is built from changed, or no time was ever noted. [before] is null when Tern starts,
         * which keeps the job and its clock as they were.
         */
        fun setsAnew(had: Boolean, before: Settings?, after: Settings, known: Boolean): Boolean =
            !had || !known || before != null && (
                before.checkEveryMinutes != after.checkEveryMinutes ||
                    before.checkOnlyOnUnmetered != after.checkOnlyOnUnmetered ||
                    before.checkOnlyWhileCharging != after.checkOnlyWhileCharging
                )
    }
}
