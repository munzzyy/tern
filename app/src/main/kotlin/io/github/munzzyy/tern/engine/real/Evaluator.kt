package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.engine.Block
import io.github.munzzyy.tern.core.engine.Decision
import io.github.munzzyy.tern.core.engine.Rejection
import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.engine.UpdateDecision
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.GitHubProxy
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.core.select.AssetPolicyException
import io.github.munzzyy.tern.core.select.FileOrigin
import io.github.munzzyy.tern.core.select.Pick
import io.github.munzzyy.tern.core.select.PreferredFile
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.forge.GitHubSource
import io.github.munzzyy.tern.core.text.PatternException
import io.github.munzzyy.tern.core.verify.Checksums
import io.github.munzzyy.tern.core.verify.Fingerprints
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.data.PatternProblem
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.ChecksumState
import io.github.munzzyy.tern.engine.FileChoice
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SignerState
import io.github.munzzyy.tern.engine.Verification

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

class Evaluator(
    private val texts: Texts,
    private val device: DeviceProfile,
    private val builtIn: BuiltInPins,
    private val nowMs: () -> Long,
    /** The file filter of the settings, for apps that have none of their own. */
    private val globalFilter: () -> String? = { null },
    /** The wait of the settings, for apps that have none of their own. */
    private val globalMinAgeDays: () -> Int = { 0 },
    /** The hubproxy GitHub is read through, or null. */
    private val githubProxy: () -> String? = { null },
) {
    constructor(texts: Texts, device: DeviceProfile, nowMs: () -> Long) : this(texts, device, BuiltInPins(), nowMs)

    /**
     * [given] with the file filter and the wait of the settings where it has none of its own. A
     * source that reads versions with the app's pattern itself has done so, so the pattern is not
     * run over them a second time.
     */
    fun effective(given: AppConfig): AppConfig {
        var releases = given.releases
        if (releases.minAgeDays == null) releases = releases.copy(minAgeDays = globalMinAgeDays())
        if (given.source.type in SourceTypes.READS_OWN_VERSIONS && !given.source.flag(SourceOptions.HIGHEST_VERSION)) {
            releases = releases.copy(versionExtract = null, matchGroup = null)
        }
        val assets = given.assets
        val global = globalFilter()
        val files = if (assets.include != null || assets.exclude != null || global == null) assets else assets.copy(include = global)
        return if (releases == given.releases && files == assets) given else given.copy(releases = releases, assets = files)
    }

    /** How many days [config] waits, its own or the setting's. */
    fun minAgeDays(config: AppConfig): Int = config.releases.minAgeDays ?: globalMinAgeDays()

    /** [inspect] returns what a file says, or null when that cannot be known now. */
    fun evaluate(given: AppConfig, state: AppState, installed: DeviceApp?, inspect: (Asset, String) -> FileFacts?): Evaluation {
        val config = effective(given)
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
            ReleaseSelector.select(
                state.releases, config.releases, nowMs(),
                matchesPackage = { release -> config.trackOnly || matchesConfiguredPackage(config.packageName, rank(config, release).asSequence().take(MAX_CANDIDATES).map { inspect(it.asset, release.id) }) },
            ) { config.trackOnly || rank(config, it).isNotEmpty() }
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
        val preferred = preferred(config, ranked)
        val order = if (preferred == null) ranked else listOf(preferred) + ranked.filter { it !== preferred }
        val (chosen, facts) = chooseFile(config, installed, candidate, order, device, inspect)
        val abiMismatch = facts?.nativeAbis?.takeIf { it.isNotEmpty() && it.none { abi -> abi in device.abis } }
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
                Block.PIN_MISMATCH -> Problem(ProblemKind.PIN_MISMATCH, if (builtIn.hold(config)) texts.builtInPinMismatch() else texts.pinMismatch())
            }
        }
        val gateBlock = state.block?.takeIf { it.releaseId == candidate.id && it.assetUrl == chosen.asset.url }
        if (gateBlock != null && status != AppStatus.UP_TO_DATE) {
            status = AppStatus.BLOCKED
            problem = gateBlock.problem
        }
        if (abiMismatch != null && status != AppStatus.UP_TO_DATE) {
            status = AppStatus.ERROR
            problem = Problem(ProblemKind.NO_FILE_FOR_DEVICE, texts.noFileForDevice(abiMismatch.joinToString()))
        }
        if (problem == null) problem = state.installProblem ?: state.checkProblem
        return Evaluation(
            status = status,
            certain = certain,
            latest = latest,
            file = choice(config, chosen, picked = chosen === preferred),
            otherFiles = ranked.filter { it !== chosen }.map { choice(config, it, picked = false) },
            verification = verification(config, state, candidate, chosen.asset, facts, installed),
            problem = problem,
            facts = facts,
        )
    }

    fun rank(config: AppConfig, release: Release): List<Pick> = AssetPicker.rank(release.assets, device, effective(config).assets)

    /** The file of [release] to install when nothing else names one: the kind the person picked, else the best ranked. */
    fun bestFile(config: AppConfig, release: Release): Asset? {
        val ranked = rank(config, release)
        return (preferred(config, ranked) ?: ranked.firstOrNull())?.asset
    }

    /** Of [ranked], the file of the kind the person picked for the app, if one is there. */
    private fun preferred(config: AppConfig, ranked: List<Pick>): Pick? =
        config.preferredFile?.let { name -> PreferredFile.find(name, ranked) { it.asset.name } }

    private fun choice(config: AppConfig, pick: Pick, picked: Boolean) = FileChoice(
        asset = pick.asset,
        reasons = pick.reasons.map(texts::pickReason),
        foreignHost = FileOrigin.foreignHost(config.source.type, config.source.url, pick.asset),
        picked = picked,
    )

    fun verification(config: AppConfig, state: AppState, release: Release, asset: Asset, facts: FileFacts?, installed: DeviceApp?): Verification {
        val signerState = when {
            facts == null || facts.signers.isEmpty() -> SignerState.UNKNOWN
            else -> {
                val blocked = UpdateDecision.blockFor(facts.inspection, installed?.app, null, config.pinnedSigners)?.first in SIGNER_BLOCKS
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
            fileSha256 = facts?.fileSha256 ?: asset.sha256?.let(Fingerprints::normalize),
            checksumFromStore = fromStore(config.source.type, checksum),
        )
    }

    /** Where the checksum will come from, judged without fetching anything. */
    fun expectedSourceLocally(config: AppConfig, release: Release, asset: Asset): String? {
        if (asset.sha256 != null) return digestLabel(config.source)
        val siblings = setOf("${asset.name}.sha256", "${asset.name}.sha256sum")
        release.assets.firstOrNull { it.kind == AssetKind.CHECKSUM && it.name in siblings }?.let { return texts.checksumFile(it.name) }
        release.assets.firstOrNull { it.kind == AssetKind.CHECKSUM && it.name.lowercase() in SHARED_SUMS }?.let { return texts.checksumFile(it.name) }
        val notes = release.notes ?: return null
        return if (Checksums.parse(notes).keys.any { it == asset.name || it.substringAfterLast('/') == asset.name }) texts.checksumNotes() else null
    }

    /** Who gave the digest of a file from [spec]: a hubproxy is named, since it could have changed it. */
    fun digestLabel(spec: SourceSpec): String {
        digestProxy(spec, githubProxy())?.let { return texts.checksumGitHubProxy(it) }
        return when (spec.type) {
            SourceTypes.GITHUB, SourceTypes.GITHUB_ACTIONS -> texts.checksumGitHub()
            SourceTypes.FDROID, SourceTypes.FDROID_REPO -> texts.checksumIndex()
            in SourceTypes.THIRD_PARTY_STORES -> texts.checksumStore(SourceTypes.displayName(spec.type) ?: spec.type)
            else -> texts.checksumSource()
        }
    }

    private fun noCandidate(reasons: List<Rejection>, state: AppState): Problem = when {
        reasons.isNotEmpty() && reasons.all { it == Rejection.PRERELEASE } -> Problem(ProblemKind.NO_RELEASES, texts.onlyPrereleases())
        reasons.isNotEmpty() && reasons.all { it == Rejection.WRONG_PACKAGE } -> Problem(ProblemKind.PACKAGE_MISMATCH, texts.onlyOtherPackage())
        reasons.isNotEmpty() && reasons.all { it == Rejection.VERSION_FILTER } -> Problem(ProblemKind.NO_RELEASES, texts.onlyFilteredVersions())
        reasons.isNotEmpty() && reasons.all { it == Rejection.STAY_BEHIND || it == Rejection.VERSION_FILTER } -> Problem(ProblemKind.NO_RELEASES, texts.stayingBehind())
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
        /** The hubproxy that stood between [spec] and GitHub when [proxy] is set, or null when the source answered itself. */
        internal fun digestProxy(spec: SourceSpec, proxy: String?): String? =
            proxy?.takeIf { spec.type in setOf(SourceTypes.GITHUB, SourceTypes.GITHUB_ACTIONS) && GitHubProxy.through(GitHubSource.apiBase(spec.url), it) != null }

        const val MAX_CANDIDATES = 4
        private val SIGNER_BLOCKS = setOf(Block.SIGNER_MISMATCH, Block.PIN_MISMATCH)
        private val SHARED_SUMS = setOf("sha256sums", "sha256sums.txt", "checksums.txt", "checksums-sha256.txt")

        /** A checksum of an app of a third-party store is the store's own, which is said as such. */
        fun fromStore(type: String, checksum: ChecksumState): Boolean = checksum != ChecksumState.NOT_PUBLISHED && type in SourceTypes.THIRD_PARTY_STORES

        fun filtersKey(config: AppConfig): String = listOf(
            config.releases.tagFilter, config.releases.titleFilter, config.releases.notesFilter,
            config.releases.versionExtract, config.assets.include, config.assets.exclude,
            config.releases.versionFilter, config.releases.matchGroup, config.assets.innerFilter,
        ).joinToString("\u0000") { it.orEmpty() }

        /**
         * The first of the best few files that is not known to be refused wins and whose native
         * libraries, if it declares any, fit one of the device's processors: a project may sign a
         * store build and a free build differently, publish more than one app's file from the same
         * release, or build a file for fewer processors than its name suggests. When [inspect]
         * cannot read a file now, that file is offered as before this check existed.
         */
        internal fun chooseFile(
            config: AppConfig,
            installed: DeviceApp?,
            release: Release,
            ranked: List<Pick>,
            device: DeviceProfile,
            inspect: (Asset, String) -> FileFacts?,
        ): Pair<Pick, FileFacts?> {
            val best = ranked.first()
            var fallback: Pair<Pick, FileFacts?>? = null
            for (pick in ranked.take(MAX_CANDIDATES)) {
                val facts = inspect(pick.asset, release.id) ?: return pick to null
                val abiOk = facts.nativeAbis.isEmpty() || facts.nativeAbis.any { it in device.abis }
                val packageOk = UpdateDecision.blockFor(facts.inspection, installed?.app, config.packageName, config.pinnedSigners) == null
                if (abiOk && packageOk) return pick to facts
                if (fallback == null) fallback = pick to facts
            }
            return fallback ?: (best to inspect(best.asset, release.id))
        }

        /**
         * True when no package is configured, when one of [facts] reads as it, or when a fact
         * along the way could not be read at all: an unreadable file settles nothing, so this
         * behaves as it did before any package was checked here.
         */
        internal fun matchesConfiguredPackage(packageName: String?, facts: Sequence<FileFacts?>): Boolean {
            if (packageName == null) return true
            for (fact in facts) {
                if (fact == null) return true
                if (fact.packageName == packageName) return true
            }
            return false
        }
    }
}
