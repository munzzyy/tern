package io.github.munzzyy.tern.log

import android.util.Log
import io.github.munzzyy.tern.engine.EventKind

/**
 * Tern's own messages, and the one way to Android's log: nothing else in the app writes there.
 * Every message goes to Android's log as it always has. While the setting to keep them is on,
 * warnings, errors and notes go to the activity log as well, through [journal], which takes out
 * whatever could be secret first.
 */
object TernLog {
    /** Where messages are kept besides Android's log. The engine sets it; while there is none, nothing is kept. */
    @Volatile
    var journal: Journal? = null

    /** For Android's log alone: a detail for someone reading it with the device attached. */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    /** Something that went as it should and is kept too, such as how a check began and how it ended. */
    fun note(tag: String, message: String) {
        Log.i(tag, message)
        journal?.take(EventKind.OWN_NOTE, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
        journal?.take(EventKind.OWN_WARNING, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
        journal?.take(EventKind.OWN_ERROR, message, error)
    }
}
