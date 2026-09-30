package io.github.munzzyy.tern.engine

/** Whether this is one of Tern's own messages, not something that happened to an app. */
val EventKind.isOwn: Boolean get() = this == EventKind.OWN_NOTE || this == EventKind.OWN_WARNING || this == EventKind.OWN_ERROR

/**
 * How much of the log is kept, oldest dropped first. Tern's own messages are counted apart from
 * what happened to apps, so a run of warnings never pushes out the history of an app.
 */
object EventLimits {
    /** What happened to apps. */
    const val APPS = 500

    /** Tern's own messages, on top of those. */
    const val OWN = 500

    /** [newestFirst] cut to the newest [APPS] of what happened to apps and the newest [OWN] of Tern's own messages, in its order. */
    fun kept(newestFirst: List<Event>): List<Event> {
        var apps = 0
        var own = 0
        return newestFirst.filter { if (it.kind.isOwn) own++ < OWN else apps++ < APPS }
    }
}
