package io.github.munzzyy.jackdaw.engine.real

import io.github.munzzyy.jackdaw.core.engine.Block
import io.github.munzzyy.jackdaw.core.engine.Decision
import io.github.munzzyy.jackdaw.core.engine.Rejection
import io.github.munzzyy.jackdaw.core.engine.ReleaseSelector
import io.github.munzzyy.jackdaw.core.engine.UpdateDecision
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.AssetKind
import io.github.munzzyy.jackdaw.core.model.DeviceProfile
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.select.AssetPicker
import io.github.munzzyy.jackdaw.core.select.AssetPolicyException
import io.github.munzzyy.jackdaw.core.select.Pick
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.text.PatternException
import io.github.munzzyy.jackdaw.core.verify.Checksums
import io.github.munzzyy.jackdaw.data.AppState
import io.github.munzzyy.jackdaw.data.FileFacts
import io.github.munzzyy.jackdaw.data.PatternProblem
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.ChecksumState
import io.github.munzzyy.jackdaw.engine.FileChoice
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.engine.SignerState
import io.github.munzzyy.jackdaw.engine.Verification

/** The row's verdict, derived from stored releases and the device; never from a stored installed version. */
data class Evaluation(
    val status: AppStatus,
    val certain: Boolean = true,
    val latest: Release? = null,
    val file: FileChoice? = null,
    val otherFiles: List<FileChoice> = emptyList(),
    val verification: Verification? = null,
    val problem: Problem? = null,
    val facts: FileFacts? = null,
    val patternProblem: PatternProblem? = null,
)

class Evaluator(private val texts: Texts, private val device: DeviceProfile, private val nowMs: () -> Long) {
    /** [inspect] returns what a file says, or null when that cannot be known now. */
    fun evaluate(config: AppConfig, state: AppState, installed: DeviceApp?, inspect: (Asset, String) -> FileFacts?): Evaluation {
        val filters = filtersKey(config)
        state.patternProblem?.takeIf { it.filters == filters }?.let {
            return Evaluation(AppStatus.ERROR, problem = Problem(ProblemKind.PARSE, texts.patternProblem(it.message)), patternProblem = it)
        }
        if (state.releases.isEmpty()) {
            if (state.checkProblem != null) return Evaluation(AppStatus.ERROR, problem = state.checkProblem)
            if (state.lastCheckedMs == null) return Evaluation(AppStatus.UNKNOWN)
            return Evaluation(AppStatus.ERROR, problem = emptyListing(config))
        }
        val selection = try {
            ReleaseSelector.select(state.releases, config.releases, nowMs()) { config.trackOnly || rank(config, it).isNotEmpty() }
        } catch (e: PatternException) {
            return patternFailure(filters, e.message)
        } catch (e: AssetPolicyException) {
            return patternFailure(filters, e.message)
        }
        val candidate = selection.candidate ?: return Evaluation(AppStatus.ERROR, problem = noCandidate(selection.rejected.map { it.second }, state))

        if (config.trackOnly) {
            val status = if (candidate.id == state.seenReleaseId) AppStatus.UP_TO_DATE else AppStatus.NEW_RELEASE
            return Evaluation(status, latest = candidate, problem = state.checkProblem)
        }

        val ranked = rank(config, candidate)
        val (chosen, facts) = choose(config, installed, candidate, ranked, inspect)
        val app = installed?.app
        var decision = UpdateDecision.decide(
            candidate, app, state.record, facts?.inspection, config.packageName, config.pinnedSigners,
            indexSigners = chosen.asset.signers, file = chosen.asset,
        )
        var certain = true
        if (decision is Decision.NeedsInspection && app != null) {
            decision = UpdateDecision.guess(candidate, app, state.record)
            certain = false
        }
        val latest = if (candidate.version.isBlank() && facts?.versionName != null) candidate.copy(version = facts.versionName) else candidate
        var status = when (decision) {
            is Decision.NotInstalled -> AppStatus.NOT_INSTALLED
            is Decision.UpToDate -> AppStatus.UP_TO_DATE
            is Decision.UpdateAvailable -> AppStatus.UPDATE_AVAILABLE.also { certain = certain && decision.certain }
            is Decision.NeedsInspection -> AppStatus.NOT_INSTALLED
            is Decision.Blocked -> AppStatus.BLOCKED
        }
        var problem: Problem? = (decision as? Decision.Blocked)?.let { blocked ->
            when (blocked.block) {
                Block.PACKAGE_MISMATCH -> Problem(ProblemKind.PACKAGE_MISMATCH, texts.packageMismatch(config.packageName ?: app?.packageName, facts?.packageName ?: "?"))
                Block.SIGNER_MISMATCH -> Problem(ProblemKind.SIGNER_MISMATCH, texts.signerMismatch())
            }
        }
        val gateBlock = state.block?.takeIf { it.releaseId == candidate.id && it.assetUrl == chosen.asset.url }
        if (gateBlock != null && status != AppStatus.UP_TO_DATE) {
            status = AppStatus.BLOCKED
            problem = gateBlock.problem
        }
        if (problem == null) problem = state.installProblem ?: state.checkProblem
        return Evaluation(
            status = status,
            certain = certain,
            latest = latest,
            file = FileChoice(chosen.asset, chosen.reasons),
            otherFiles = ranked.filter { it !== chosen }.map { FileChoice(it.asset, it.reasons) },
            verification = verification(config, state, candidate, chosen.asset, facts, installed),
            problem = problem,
            facts = facts,
        )
    }

    fun rank(config: AppConfig, release: Release): List<Pick> = AssetPicker.rank(release.assets, device, config.assets)

    /**
     * With something installed or pinned, the first of the best few files that is not known to be
     * refused wins: a project may sign a store build and a free build differently.
     */
    private fun choose(config: AppConfig, installed: DeviceApp?, release: Release, ranked: List<Pick>, inspect: (Asset, String) -> FileFacts?): Pair<Pick, FileFacts?> {
        val best = ranked.first()
        if (installed == null && config.pinnedSigners.isEmpty()) return best to inspect(best.asset, release.id)
        for (pick in ranked.take(MAX_CANDIDATES)) {
            val facts = inspect(pick.asset, release.id) ?: return pick to null
            if (UpdateDecision.blockFor(facts.inspection, installed?.app, config.packageName, config.pinnedSigners) == null) return pick to facts
        }
        return best to inspect(best.asset, release.id)
    }

    fun verification(config: AppConfig, state: AppState, release: Release, asset: Asset, facts: FileFacts?, installed: DeviceApp?): Verification {
        val signerState = when {
            facts == null || facts.signers.isEmpty() -> SignerState.UNKNOWN
            else -> {
                val blocked = UpdateDecision.blockFor(facts.inspection, installed?.app, null, config.pinnedSigners)?.first == Block.SIGNER_MISMATCH
                when {
                    blocked -> SignerState.MISMATCH
                    config.pinnedSigners.isNotEmpty() -> SignerState.MATCHES_PIN
                    installed != null -> SignerState.MATCHES_INSTALLED
                    else -> SignerState.FIRST_SEEN
                }
            }
        }
        val mismatch = state.block?.takeIf { it.releaseId == release.id && it.assetUrl == asset.url && it.problem.kind == ProblemKind.CHECKSUM_MISMATCH }
        val publishedFrom = expectedSourceLocally(config, release, asset)
        val (checksum, source) = when {
            facts?.checksumMatchedFrom != null -> ChecksumState.MATCHED to facts.checksumMatchedFrom
            mismatch != null -> ChecksumState.MISMATCH to publishedFrom
            publishedFrom != null -> ChecksumState.PENDING to publishedFrom
            else -> ChecksumState.NOT_PUBLISHED to null
        }
        val ownReceiverGuard = facts?.let { "${it.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" }
        val newPermissions = facts?.permissions.orEmpty().filter { (installed == null || it !in installed.permissions) && it != ownReceiverGuard }
        return Verification(
            packageName = facts?.packageName ?: config.packageName,
            signers = facts?.signers.orEmpty(),
            signersVerified = facts?.verified == true,
            signerState = signerState,
            checksum = checksum,
            checksumSource = source,
            newPermissions = newPermissions,
        )
    }

    /** Where the checksum will come from, judged without fetching anything. */
    fun expectedSourceLocally(config: AppConfig, release: Release, asset: Asset): String? {
        if (asset.sha256 != null) return digestLabel(config.source.type)
        val siblings = setOf("${asset.name}.sha256", "${asset.name}.sha256sum")
        release.assets.firstOrNull { it.kind == AssetKind.CHECKSUM && it.name in siblings }?.let { return texts.checksumFile(it.name) }
        release.assets.firstOrNull { it.kind == AssetKind.CHECKSUM && it.name.lowercase() in SHARED_SUMS }?.let { return texts.checksumFile(it.name) }
        val notes = release.notes ?: return null
        return if (Checksums.parse(notes).keys.any { it == asset.name || it.substringAfterLast('/') == asset.name }) texts.checksumNotes() else null
    }

    fun digestLabel(type: String): String = when (type) {
        SourceTypes.GITHUB, SourceTypes.GITHUB_ACTIONS -> texts.checksumGitHub()
        SourceTypes.FDROID, SourceTypes.FDROID_REPO -> texts.checksumIndex()
        else -> texts.checksumSource()
    }

    private fun noCandidate(reasons: List<Rejection>, state: AppState): Problem = when {
        reasons.isNotEmpty() && reasons.all { it == Rejection.PRERELEASE } -> Problem(ProblemKind.NO_RELEASES, texts.onlyPrereleases())
        Rejection.NO_USABLE_FILE in reasons -> Problem(ProblemKind.NO_FILE_FOR_DEVICE, texts.noFileForDevice(null))
        state.checkProblem != null -> state.checkProblem
        else -> Problem(ProblemKind.NO_RELEASES, texts.noReleasePasses())
    }

    /** A source that answered with no releases at all; a repository does that when nothing in it fits this device. */
    private fun emptyListing(config: AppConfig): Problem = when (config.source.type) {
        SourceTypes.FDROID, SourceTypes.FDROID_REPO -> Problem(ProblemKind.NO_FILE_FOR_DEVICE, texts.noFileForDevice(null))
        else -> Problem(ProblemKind.NO_RELEASES, texts.checkNoReleases())
    }

    private fun patternFailure(filters: String, message: String?): Evaluation {
        val remembered = PatternProblem(filters, message.orEmpty())
        return Evaluation(AppStatus.ERROR, problem = Problem(ProblemKind.PARSE, texts.patternProblem(message)), patternProblem = remembered)
    }

    companion object {
        const val MAX_CANDIDATES = 4
        private val SHARED_SUMS = setOf("sha256sums", "sha256sums.txt", "checksums.txt", "checksums-sha256.txt")

        fun filtersKey(config: AppConfig): String = listOf(
            config.releases.tagFilter, config.releases.titleFilter, config.releases.notesFilter,
            config.releases.versionExtract, config.assets.include, config.assets.exclude,
        ).joinToString("\u0000") { it.orEmpty() }
    }
}
