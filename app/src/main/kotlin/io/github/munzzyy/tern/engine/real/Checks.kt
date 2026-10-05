package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.icon.IconAddresses
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.data.StateJson
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.net.isProxySilent
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.log.TernLog
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

    /**
     * Checks [ids] as [checkMany] does, and says so in Tern's own messages: what started the
     * check and how many apps it takes, then how long it took, how many of them have an update
     * after it and how many could not be checked. The log keeps these while its setting is on.
     */
    suspend fun run(ids: List<String>, cause: CheckCause, onEach: () -> Unit = {}): List<CheckOutcome> {
        val one = ids.singleOrNull()?.let { e.stored[it]?.config?.shownName }
        TernLog.note(TAG, e.texts.checkStarted(ids.size, one, cause))
        val started = e.nowMs()
        var outcomes: List<CheckOutcome>? = null
        try {
            return checkMany(ids, onEach).also { outcomes = it }
        } finally {
            val took = e.nowMs() - started
            val done = outcomes
            TernLog.note(TAG, if (done == null) e.texts.checkStopped(took) else e.texts.checkEnded(ids.size, one, took, done.count { hasUpdate(it.id) }, done.count { it.failed }))
        }
    }

    private fun hasUpdate(id: String): Boolean = e.evaluations[id]?.status.let { it == AppStatus.UPDATE_AVAILABLE || it == AppStatus.NEW_RELEASE }

    /** [onEach] is called as each check ends, however it ends. */
    suspend fun checkMany(ids: List<String>, onEach: () -> Unit = {}): List<CheckOutcome> = coroutineScope {
        ids.map { id ->
            async {
                try {
                    checkOne(id)
                } finally {
                    onEach()
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun checkOne(id: String): CheckOutcome? {
        val stored = e.stored[id] ?: return null
        if (e.registry.paused(stored.config.source)) {
            reevaluate(id, network = false)
            return CheckOutcome(id, newRelease = false, failed = false)
        }
        e.checking += id
        e.publish()
        try {
            return withContext(Dispatchers.IO) {
                val retryAt = stored.state.checkProblem?.takeIf { it.kind == ProblemKind.RATE_LIMITED }?.retryAtMs
                val failed = if (retryAt != null && retryAt > e.nowMs()) true else fetch(id, stored.config)
                reevaluate(id, network = true)
                CheckOutcome(id, announce(id), failed)
            }
        } finally {
            e.checking -= id
            e.publish()
        }
    }

    /** Returns true when the check failed. */
    private suspend fun fetch(id: String, config: AppConfig): Boolean {
        val spec = config.source
        val hostPermits = perHost.getOrPut(Urls.host(spec.url)) { Semaphore(PER_HOST) }
        val outcome: Any = all.withPermit {
            hostPermits.withPermit {
                runInterruptible(Dispatchers.IO) {
                    try {
                        e.registry.check(spec, e.checkContext(config, e.store))
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
                val before = e.stored[id]?.state?.checkProblem
                e.saveState(id) { it.copy(lastCheckedMs = now, checkProblem = problem) }
                if (logsFailure(before, problem)) e.event(id, EventKind.CHECK_FAILED, problem.message)
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
            if (config.packageName == null && listed != null && BinaryManifest.isValidName(listed)) config = withPackage(config, listed)
            // While every release listed now is too young, the last ones that were old enough stay on offer.
            val kept = ReleaseSelector.keptUntilOldEnough(listing.releases, s.state.releases, e.evaluator.minAgeDays(config), now)
            val releases = kept.take(StateJson.MAX_RELEASES).map { it.copy(notes = it.notes?.take(StateJson.MAX_NOTES)) }
            val icons = IconAddresses.afterCheck(config.source.url, listing.iconUrls, s.state.iconUrls)
            s.copy(
                config = config,
                state = s.state.copy(
                    releases = releases,
                    lastCheckedMs = now,
                    checkProblem = null,
                    movedTo = listing.movedTo?.takeIf { Urls.isHttps(it) && it.length <= MAX_ADDRESS },
                    description = listing.description?.take(1000) ?: s.state.description,
                    iconUrls = icons,
                ),
            )
        }
    }

    /** [config] once its package is known, held to the certificates Tern carries for that package where it had no pin yet. */
    private fun withPackage(config: AppConfig, packageName: String): AppConfig =
        config.copy(packageName = packageName, pinnedSigners = config.pinnedSigners.ifEmpty { e.builtIn.forApp(config.source, packageName) })

    private val evaluating = ConcurrentHashMap<String, Any>()

    /**
     * Recomputes the row against the device. With [network], files may be inspected remotely.
     *
     * A check that brings a new listing and an install that finishes evaluate the same app at the
     * same moment, and the one that started from the older state must not be the one that is
     * kept. So the row is computed one at a time for each app, from what is stored once its turn
     * has come. Reading a file over the network takes seconds and callers such as cancel run on
     * the main thread, so that reading happens before the turn and only fills the cache.
     */
    fun reevaluate(id: String, network: Boolean) {
        if (network && e.stored[id]?.let { e.registry.paused(it.config.source) } == false) readFilesFor(id)
        synchronized(evaluating.getOrPut(id) { Any() }) { evaluate(id) }
    }

    private fun readFilesFor(id: String) {
        val stored = e.stored[id] ?: return
        val eval = e.evaluator.evaluate(stored.config, stored.state, e.readInstalled(stored.config.packageName), e.inspectorFor(stored.config.source))
        // Remembered at once, so the evaluation that follows does not run a runaway pattern a second time.
        if (eval.patternProblem != stored.state.patternProblem) e.saveState(id) { it.copy(patternProblem = eval.patternProblem) }
    }

    private fun evaluate(id: String) {
        val stored = e.stored[id] ?: return
        if (e.registry.paused(stored.config.source)) {
            e.evaluations[id] = paused(e.texts.storesOffPaused())
            return
        }
        val inspect: (Asset, String) -> FileFacts? = e.inspector::cached
        var config = stored.config
        var installed = e.readInstalled(config.packageName)
        var eval = e.evaluator.evaluate(config, stored.state, installed, inspect)
        val learned = eval.facts?.packageName
        if (config.packageName == null && learned != null) {
            config = e.saveApp(id) { it.copy(config = withPackage(it.config, learned)) }?.config ?: config
            installed = e.readInstalled(learned)
            eval = e.evaluator.evaluate(config, stored.state, installed, inspect)
        }
        if (eval.patternProblem != stored.state.patternProblem) e.saveState(id) { it.copy(patternProblem = eval.patternProblem) }
        if (installed != null && !stored.state.seenInstalled) e.saveState(id) { it.copy(seenInstalled = true) }
        e.evaluations[id] = eval
    }

    private fun announce(id: String): Boolean {
        val eval = e.evaluations[id] ?: return false
        val latest = eval.latest ?: return false
        if (eval.status != AppStatus.UPDATE_AVAILABLE && eval.status != AppStatus.NEW_RELEASE) return false
        val stored = e.stored[id] ?: return false
        if (stored.state.announcedReleaseId == latest.id) return false
        e.saveState(id) { it.copy(announcedReleaseId = latest.id) }
        e.event(id, EventKind.UPDATE_FOUND, e.texts.eventUpdateFound(Shown.line(latest.version.ifBlank { latest.id }, MAX_VERSION)))
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
        val detail = Shown.lineOrNull(ex.message, MAX_DETAIL)
        return when (ex.kind) {
            SourceErrorKind.NETWORK -> Problem(ProblemKind.NETWORK, if (ex.isProxySilent()) t.proxySilent() else t.checkNetwork(detail))
            SourceErrorKind.NOT_FOUND -> Problem(ProblemKind.NOT_FOUND, t.checkNotFound(detail))
            SourceErrorKind.AUTH -> Problem(ProblemKind.AUTH, t.checkAuth(detail))
            SourceErrorKind.RATE_LIMITED -> Problem(ProblemKind.RATE_LIMITED, t.checkRateLimited(ex.retryAtMs), ex.retryAtMs)
            SourceErrorKind.PARSE -> Problem(ProblemKind.PARSE, t.checkParse(detail))
            SourceErrorKind.UNSUPPORTED -> Problem(ProblemKind.UNSUPPORTED, t.checkUnsupported(detail))
            SourceErrorKind.NO_RELEASES -> Problem(ProblemKind.NO_RELEASES, t.checkNoReleases())
        }
    }

    companion object {
        /** An app of a third-party store while those are off: kept as it is, and neither checked nor installed. */
        fun paused(message: String) = Evaluation(AppStatus.ERROR, problem = Problem(ProblemKind.STORES_OFF, message))

        /** Whether a failed check goes in the log: not when the check before it failed the same way, which the row still shows. */
        fun logsFailure(before: Problem?, now: Problem): Boolean = before == null || before.kind != now.kind || before.message != now.message

        private const val MAX_PARALLEL = 4
        private const val PER_HOST = 2
        private const val MAX_ADDRESS = 2048
        private const val MAX_DETAIL = 200
        private const val MAX_VERSION = 100
        private const val TAG = "TernChecks"
    }
}
