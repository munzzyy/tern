package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What an entry says first. [PLAIN] says what the engine wrote, for the kinds that have no words of their own. */
enum class Headline { INSTALLED, UPDATED, UPDATE_WAITS, INSTALL_WAITS, NOT_INSTALLED, CANCELLED, BLOCKED, ADDED, REMOVED, CHECK_FAILED, PLAIN }

/** One thing that happened: a single event, or the events of one operation on one app. */
data class Entry(
    /** Oldest first. The last one is the outcome, and the entry takes its time and its app from it. */
    val steps: List<Event>,
    val headline: Headline,
    /** The version the headline names, where a step told it. */
    val version: String?,
) {
    val outcome: Event get() = steps.last()
    val id: Long get() = outcome.id
    val atMs: Long get() = outcome.atMs
    val isProblem: Boolean get() = isProblem(outcome)
}

data class EntryDay(val date: LocalDate, val entries: List<Entry>)

/** Two events of one app further apart than this belong to two operations. */
const val OPERATION_GAP_MS = 120_000L

private val STAGES = mapOf(
    EventKind.UPDATE_FOUND to 0,
    EventKind.DOWNLOADED to 1,
    EventKind.VERIFIED to 2,
    EventKind.INSTALLED to 3,
    EventKind.BLOCKED to 3,
    EventKind.FAILED to 3,
    EventKind.CANCELLED to 3,
)

private val DOTTED = Regex("""[vV]?\d+(?:\.\d+)+[0-9A-Za-z.+_-]*""")
private val NUMBERED = Regex("""[vV]?\d[0-9A-Za-z.+_-]*""")

/**
 * The version a sentence of the engine names. An event carries no version of its own, so it is
 * read from the words: the first number with a dot in it, or with [loose] any first number.
 */
fun versionIn(message: String, loose: Boolean = false): String? {
    val found = DOTTED.find(message) ?: if (loose) NUMBERED.find(message) else null
    return found?.value?.trimEnd('.', '-', '+', '_')?.takeIf { it.isNotEmpty() }
}

/**
 * The events as entries, newest first. What an install or an update does to one app, found,
 * downloaded, verified, then installed, blocked or failed, is one entry when each step follows
 * the one before within [OPERATION_GAP_MS]. Everything else is an entry of its own.
 */
fun entriesOf(events: List<Event>): List<Entry> {
    val groups = mutableListOf<MutableList<Event>>()
    val running = HashMap<String, MutableList<Event>>()
    for (event in events.sortedWith(compareBy<Event> { it.atMs }.thenBy { it.id })) {
        val stage = STAGES[event.kind]
        val group = event.appId?.let(running::get)
        val last = group?.last()
        if (stage != null && last != null && event.atMs - last.atMs <= OPERATION_GAP_MS && stage > STAGES.getValue(last.kind)) {
            group.add(event)
            continue
        }
        val started = mutableListOf(event)
        groups += started
        val appId = event.appId
        if (stage != null && appId != null) running[appId] = started
    }
    val hadRelease = HashSet<String>()
    val offered = HashMap<String, String>()
    val entries = groups.map { steps ->
        val appId = steps.last().appId
        val found = steps.lastOrNull { it.kind == EventKind.UPDATE_FOUND }
        val installed = steps.lastOrNull { it.kind == EventKind.INSTALLED }
        val update = found != null || (appId != null && appId in hadRelease)
        val version = installed?.let { versionIn(it.message) }
            ?: found?.let { versionIn(it.message, loose = true) }
            ?: appId?.let(offered::get)?.takeIf { STAGES[steps.last().kind] != null }
        if (appId != null) {
            if (found != null || installed != null) hadRelease += appId
            if (found != null && version != null) offered[appId] = version
            if (installed != null) offered.remove(appId)
        }
        Entry(steps, headlineOf(steps.last().kind, update), version)
    }
    return entries.sortedWith(compareByDescending<Entry> { it.atMs }.thenByDescending { it.id })
}

private fun headlineOf(outcome: EventKind, update: Boolean): Headline = when (outcome) {
    EventKind.INSTALLED -> if (update) Headline.UPDATED else Headline.INSTALLED
    EventKind.UPDATE_FOUND -> Headline.UPDATE_WAITS
    EventKind.DOWNLOADED, EventKind.VERIFIED -> if (update) Headline.UPDATE_WAITS else Headline.INSTALL_WAITS
    EventKind.FAILED -> Headline.NOT_INSTALLED
    EventKind.CANCELLED -> Headline.CANCELLED
    EventKind.BLOCKED -> Headline.BLOCKED
    EventKind.ADDED -> Headline.ADDED
    EventKind.REMOVED -> Headline.REMOVED
    EventKind.CHECK_FAILED -> Headline.CHECK_FAILED
    EventKind.IMPORTED, EventKind.MOVED -> Headline.PLAIN
}

/** Entries under the day of their outcome, newest first inside each day and across days. */
fun entriesByDay(events: List<Event>, zone: ZoneId, problemsOnly: Boolean): List<EntryDay> {
    val days = LinkedHashMap<LocalDate, MutableList<Entry>>()
    for (entry in entriesOf(events)) {
        if (problemsOnly && !entry.isProblem) continue
        val day = Instant.ofEpochMilli(entry.atMs).atZone(zone).toLocalDate()
        days.getOrPut(day) { mutableListOf() }.add(entry)
    }
    return days.map { (date, list) -> EntryDay(date, list) }
}
