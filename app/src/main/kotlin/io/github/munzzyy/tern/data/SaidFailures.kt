package io.github.munzzyy.tern.data

import android.content.Context

/**
 * The failures a notification has already said, each kept as a fingerprint alone and never as a
 * name or a reason, so that the same failure is not said again at every check.
 */
class SaidFailures(context: Context, name: String = DEFAULT_NAME) {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Synchronized
    fun get(): Set<String> = prefs.getStringSet(KEY, null)?.toSet().orEmpty()

    @Synchronized
    fun set(said: Set<String>) {
        if (said.isEmpty()) prefs.edit().remove(KEY).apply() else prefs.edit().putStringSet(KEY, said).apply()
    }

    companion object {
        const val DEFAULT_NAME = "notified"
        private const val KEY = "failures"
    }
}
