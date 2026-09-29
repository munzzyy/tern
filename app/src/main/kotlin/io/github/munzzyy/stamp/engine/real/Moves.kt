package io.github.munzzyy.stamp.engine.real

import io.github.munzzyy.stamp.core.apk.BinaryManifest
import io.github.munzzyy.stamp.core.engine.Block
import io.github.munzzyy.stamp.core.engine.UpdateDecision
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceListing
import io.github.munzzyy.stamp.data.AppState
import io.github.munzzyy.stamp.data.StoredApp
import io.github.munzzyy.stamp.engine.EventKind
import io.github.munzzyy.stamp.engine.Problem
import io.github.munzzyy.stamp.engine.ProblemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Following a project to its new address. The source is replaced only when the new address offers
 * the same package and, where a signer is known on both sides, the same signer.
 */
internal class Moves(private val e: RealEngine) {
    private sealed interface Probe {
        data class Listed(val spec: SourceSpec, val listing: SourceListing) : Probe
        data class Refused(val problem: Problem) : Probe
    }

    suspend fun follow(appId: String): Problem? = withContext(Dispatchers.IO) {
        val app = e.stored[appId] ?: return@withContext Problem(ProblemKind.NOT_FOUND, e.texts.moveGone())
        val target = suggestion(app.state) ?: return@withContext Problem(ProblemKind.NOT_FOUND, e.texts.moveNothing())
        val probe = probe(target)
        if (probe is Probe.Refused) return@withContext probe.problem
        val (spec, listing) = probe as Probe.Listed
        val holder = e.findBySpec(spec)
        if (holder != null && holder != appId) return@withContext Problem(ProblemKind.UNSUPPORTED, e.texts.moveAlreadyTracked())
        refusal(app, spec, listing)?.let { return@withContext it }

        val old = app.config.source
        e.saveApp(appId) { s -> s.copy(config = s.config.copy(source = spec), state = s.state.copy(keptAddress = null, block = null)) }
            ?: return@withContext Problem(ProblemKind.NOT_FOUND, e.texts.moveGone())
        e.store.removeValidators("${old.type}|${old.url}|")
        e.checks.storeListing(appId, listing, e.nowMs())
        e.event(appId, EventKind.MOVED, e.texts.eventMoved(old.url, spec.url))
        e.checks.reevaluate(appId, network = false)
        e.publish()
        null
    }

    suspend fun keep(appId: String) = withContext(Dispatchers.IO) {
        val target = e.stored[appId]?.state?.let(::suggestion) ?: return@withContext
        e.saveState(appId) { it.copy(keptAddress = target) }
        e.publish()
    }

    private suspend fun probe(target: String): Probe {
        val address = Urls.normalize(target) ?: return Probe.Refused(Problem(ProblemKind.NOT_FOUND, e.texts.moveNotASource()))
        val context = CheckContext(e.http, InMemoryValidatorStore(), e.tokens, e.nowMs, e.device.profile)
        return runInterruptible {
            try {
                val spec = e.registry.detect(address, context) ?: return@runInterruptible Probe.Refused(Problem(ProblemKind.NOT_FOUND, e.texts.moveNotASource()))
                when (val result = e.registry.check(spec, context)) {
                    is CheckResult.Listing -> Probe.Listed(spec.copy(options = spec.options + result.listing.learnedOptions), result.listing)
                    CheckResult.Unchanged -> Probe.Refused(Problem(ProblemKind.NO_RELEASES, e.texts.moveNotASource()))
                }
            } catch (ex: SourceException) {
                Probe.Refused(e.checks.problemOf(ex))
            }
        }
    }

    /** Why the new address must not replace the old one, or null when it may. */
    private fun refusal(app: StoredApp, spec: SourceSpec, listing: SourceListing): Problem? {
        val config = app.config.copy(source = spec)
        val installed = e.readInstalled(app.config.packageName)
        val expected = app.config.packageName ?: installed?.app?.packageName
            ?: return Problem(ProblemKind.PACKAGE_MISMATCH, e.texts.moveUnknownApp())
        val state = AppState(releases = listing.releases, lastCheckedMs = e.nowMs())
        val facts = e.evaluator.evaluate(config, state, installed, e.inspector::inspect).facts
        val offered = facts?.packageName ?: listing.packageName?.takeIf { BinaryManifest.isValidName(it) }
            ?: return Problem(ProblemKind.PACKAGE_MISMATCH, e.texts.moveUnreadable())
        if (offered != expected) return Problem(ProblemKind.PACKAGE_MISMATCH, e.texts.moveOtherApp(expected, offered))
        if (facts == null || facts.signers.isEmpty()) return null
        return when (UpdateDecision.blockFor(facts.inspection, installed?.app, expected, app.config.pinnedSigners)?.first) {
            Block.PIN_MISMATCH -> Problem(ProblemKind.PIN_MISMATCH, e.texts.moveOtherSigner())
            Block.SIGNER_MISMATCH -> Problem(ProblemKind.SIGNER_MISMATCH, e.texts.moveOtherSigner())
            Block.PACKAGE_MISMATCH -> Problem(ProblemKind.PACKAGE_MISMATCH, e.texts.moveOtherApp(expected, facts.packageName))
            null -> null
        }
    }

    companion object {
        /** The new home the source reports, unless the user chose to keep the old address for exactly that one. */
        fun suggestion(state: AppState): String? = state.movedTo?.takeIf { it != state.keptAddress }
    }
}
