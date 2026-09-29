package io.github.munzzyy.jackdaw.engine.real

import io.github.munzzyy.jackdaw.core.apk.BinaryManifest
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.net.Urls
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.data.FileFacts
import io.github.munzzyy.jackdaw.data.StateJson
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** How one check ended, for the background summary. */
internal data class CheckOutcome(val id: String, val newRelease: Boolean, val failed: Boolean)

/** Checks run at most four at once and at most two per host. */
internal class Checks(private val e: RealEngine) {
    private val all = Semaphore(MAX_PARALLEL)
    private val perHost = ConcurrentHashMap<String, Semaphore>()

    suspend fun checkMany(ids: List<String>): List<CheckOutcome> = coroutineScope {
        ids.map { id -> async { checkOne(id) } }.awaitAll().filterNotNull()
    }

    suspend fun checkOne(id: String): CheckOutcome? {
        val stored = e.stored[id] ?: return null
        e.checking += id
        e.publish()
        try {
            return withContext(Dispatchers.IO) {
                val retryAt = stored.state.checkProblem?.takeIf { it.kind == ProblemKind.RATE_LIMITED }?.retryAtMs
                val failed = if (retryAt != null && retryAt > e.nowMs()) true else fetch(id, stored.config.source)
                reevaluate(id, network = true)
                CheckOutcome(id, announce(id), failed)
            }
        } finally {
            e.checking -= id
            e.publish()
        }
    }

    /** Returns true when the check failed. */
    private suspend fun fetch(id: String, spec: io.github.munzzyy.jackdaw.core.model.SourceSpec): Boolean {
        val hostPermits = perHost.getOrPut(Urls.host(spec.url)) { Semaphore(PER_HOST) }
        val outcome: Any = all.withPermit {
            hostPermits.withPermit {
                runInterruptible(Dispatchers.IO) {
                    try {
                        e.registry.check(spec, CheckContext(e.http, e.store, e.tokens, e.nowMs, e.device.profile))
                    } catch (ex: SourceException) {
                        ex
                    }
                }
            }
        }
        val now = e.nowMs()
        when (outcome) {
            is SourceException -> {
                val problem = problemOf(outcome)
                e.saveState(id) { it.copy(lastCheckedMs = now, checkProblem = problem) }
                e.event(id, EventKind.CHECK_FAILED, problem.message)
                return true
            }
            is CheckResult.Listing -> storeListing(id, outcome.listing, now)
            else -> e.saveState(id) { it.copy(lastCheckedMs = now, checkProblem = null) }
        }
        return false
    }

    fun storeListing(id: String, listing: SourceListing, now: Long) {
        e.saveApp(id) { s ->
            var config = s.config
            if (listing.learnedOptions.isNotEmpty()) {
                config = config.copy(source = config.source.copy(options = config.source.options + listing.learnedOptions))
            }
            val listed = listing.packageName
            if (config.packageName == null && listed != null && BinaryManifest.isValidName(listed)) config = config.copy(packageName = listed)
            val releases = listing.releases.take(StateJson.MAX_RELEASES).map { it.copy(notes = it.notes?.take(StateJson.MAX_NOTES)) }
            s.copy(
                config = config,
                state = s.state.copy(
                    releases = releases,
                    lastCheckedMs = now,
                    checkProblem = null,
                    movedTo = listing.movedTo?.takeIf { Urls.isHttps(it) && it.length <= MAX_ADDRESS },
                    description = listing.description?.take(1000) ?: s.state.description,
                ),
            )
        }
    }

    /** Recomputes the row against the device. With [network], files may be inspected remotely. */
    fun reevaluate(id: String, network: Boolean) {
        val stored = e.stored[id] ?: return
        val inspect: (Asset, String) -> FileFacts? = if (network) e.inspector::inspect else e.inspector::cached
        var config = stored.config
        var installed = e.readInstalled(config.packageName)
        var eval = e.evaluator.evaluate(config, stored.state, installed, inspect)
        val learned = eval.facts?.packageName
        if (config.packageName == null && learned != null) {
            config = e.saveApp(id) { it.copy(config = it.config.copy(packageName = learned)) }?.config ?: config
            installed = e.readInstalled(learned)
            eval = e.evaluator.evaluate(config, stored.state, installed, inspect)
        }
        if (eval.patternProblem != stored.state.patternProblem) e.saveState(id) { it.copy(patternProblem = eval.patternProblem) }
        e.evaluations[id] = eval
    }

    private fun announce(id: String): Boolean {
        val eval = e.evaluations[id] ?: return false
        val latest = eval.latest ?: return false
        if (eval.status != AppStatus.UPDATE_AVAILABLE && eval.status != AppStatus.NEW_RELEASE) return false
        val stored = e.stored[id] ?: return false
        if (stored.state.announcedReleaseId == latest.id) return false
        e.saveState(id) { it.copy(announcedReleaseId = latest.id) }
        e.event(id, EventKind.UPDATE_FOUND, e.texts.eventUpdateFound(latest.version.ifBlank { latest.id }))
        return true
    }

    fun onPackageChanged(packageName: String) {
        e.readInstalled(packageName)
        for (stored in e.stored.values) {
            if (e.packageOf(stored.config) == packageName) reevaluate(stored.config.id, network = false)
        }
        e.publish()
    }

    fun problemOf(ex: SourceException): Problem {
        val t = e.texts
        return when (ex.kind) {
            SourceErrorKind.NETWORK -> Problem(ProblemKind.NETWORK, t.checkNetwork(ex.message))
            SourceErrorKind.NOT_FOUND -> Problem(ProblemKind.NOT_FOUND, t.checkNotFound(ex.message))
            SourceErrorKind.AUTH -> Problem(ProblemKind.AUTH, t.checkAuth(ex.message))
            SourceErrorKind.RATE_LIMITED -> Problem(ProblemKind.RATE_LIMITED, t.checkRateLimited(ex.retryAtMs), ex.retryAtMs)
            SourceErrorKind.PARSE -> Problem(ProblemKind.PARSE, t.checkParse(ex.message))
            SourceErrorKind.UNSUPPORTED -> Problem(ProblemKind.UNSUPPORTED, t.checkUnsupported(ex.message))
            SourceErrorKind.NO_RELEASES -> Problem(ProblemKind.NO_RELEASES, t.checkNoReleases())
        }
    }

    private companion object {
        const val MAX_PARALLEL = 4
        const val PER_HOST = 2
        const val MAX_ADDRESS = 2048
    }
}
