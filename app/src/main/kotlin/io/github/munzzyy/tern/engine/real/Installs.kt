package io.github.munzzyy.tern.engine.real

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.github.munzzyy.tern.core.engine.InstallRecord
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.verify.Checksums
import io.github.munzzyy.tern.core.verify.Fingerprints
import io.github.munzzyy.tern.data.GateBlock
import io.github.munzzyy.tern.data.PendingInstall
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.install.Downloader
import io.github.munzzyy.tern.install.GateRequest
import io.github.munzzyy.tern.install.OtherAppInstaller
import io.github.munzzyy.tern.install.StepFailure
import io.github.munzzyy.tern.work.TransferService
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolve, download, gate, hand to the installer. An install counts only when the installer says
 * STATUS_SUCCESS and PackageManager then shows the new version code.
 */
internal class Installs(private val e: RealEngine) {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Apps with an install under way. Whoever starts one, the user or the background check, claims the app here first. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val outcomes = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val batch: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val confirmations = ConcurrentHashMap<String, Intent>()

    @Synchronized
    fun start(appId: String, releaseId: String?, assetUrl: String?, userStarted: Boolean): Job {
        jobs[appId]?.takeIf { it.isActive }?.let { return it }
        if (userStarted) {
            e.userTransfers += appId
            e.setProgress(appId, Progress(Phase.QUEUED))
        }
        val job = e.scope.launch { run(appId, releaseId, assetUrl) }
        jobs[appId] = job
        job.invokeOnCompletion {
            jobs.remove(appId, job)
            e.userTransfers -= appId
            e.publish()
        }
        if (userStarted) TransferService.start(e.context)
        return job
    }

    fun cancel(appId: String) {
        jobs.remove(appId)?.cancel()
        val pending = e.stored[appId]?.state?.pending
        if (pending != null) {
            e.installer.abandon(pending.sessionId)
            e.saveState(appId) { it.copy(pending = null) }
            e.notifier.cancelConfirm(appId)
        }
        confirmations.remove(appId)
        outcomes.remove(appId)?.complete(PackageInstaller.STATUS_FAILURE_ABORTED)
        e.setProgress(appId, null)
    }

    /** Reopens the system's confirmation for a live session; otherwise settles the phase and returns false. */
    fun resume(appId: String): Boolean {
        val pending = e.stored[appId]?.state?.pending
        val confirm = confirmations[appId] ?: pending?.let { e.installer.confirmation(it.sessionId) }
        val live = e.installer.liveSessionIds()
        if (pending != null && pending.sessionId in live && confirm != null) {
            try {
                e.context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (ex: RuntimeException) {
                Log.w(TAG, "Could not reopen the install confirmation: ${ex.message}")
            }
        }
        if (pending != null) {
            if (pending.sessionId in live) e.installer.abandon(pending.sessionId)
            settle(appId, pending)
        }
        confirmations.remove(appId)
        e.notifier.cancelConfirm(appId)
        outcomes.remove(appId)?.complete(PackageInstaller.STATUS_FAILURE_ABORTED)
        e.progress.remove(appId)
        e.checks.reevaluate(appId, network = false)
        e.publish()
        return false
    }

    /** Returns the session handed to the installer, or null when the pipeline stopped before that. */
    suspend fun run(appId: String, releaseId: String?, assetUrl: String?): Int? = withContext(Dispatchers.IO) {
        e.ready()
        if (!busy.add(appId)) return@withContext null
        val staging = File(e.staging, e.downloader.folder(appId).name)
        try {
            // A source whose file addresses do not last is asked again first, unless a particular file was picked.
            if (releaseId == null && assetUrl == null && e.stored[appId]?.config?.refreshFirst == true) e.checks.checkOne(appId)
            pipeline(appId, releaseId, assetUrl, staging)
        } finally {
            staging.deleteRecursively()
            busy.remove(appId)
        }
    }

    private suspend fun pipeline(appId: String, releaseId: String?, assetUrl: String?, staging: File): Int? {
        val stored = e.stored[appId] ?: return null
        val waiting = stored.state.pending
        if (waiting != null && waiting.sessionId in e.installer.liveSessionIds()) {
            resume(appId)
            return null
        }
        val config = stored.config
        var release: Release? = null
        var asset: Asset? = null
        return try {
            if (config.trackOnly) throw StepFailure(ProblemKind.UNSUPPORTED, e.texts.trackOnly())
            val eval = e.evaluations[appId]
            val chosenRelease = releaseId?.let { id -> stored.state.releases.firstOrNull { it.id == id } }
                ?: eval?.latest
                ?: throw StepFailure(ProblemKind.NO_RELEASES, e.texts.noRelease())
            release = chosenRelease
            val chosenAsset = assetUrl?.let { url -> chosenRelease.assets.firstOrNull { it.url == url } }
                ?: eval?.file?.asset?.takeIf { eval.latest?.id == chosenRelease.id }
                ?: e.evaluator.rank(config, chosenRelease).firstOrNull()?.asset
                ?: throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, e.texts.noFileForDevice(null))
            asset = chosenAsset
            val lower = chosenAsset.name.lowercase()
            if (UNOPENED_ARCHIVES.any { lower.endsWith(it) }) throw StepFailure(ProblemKind.UNSUPPORTED, e.texts.archiveCompressionUnsupported())

            e.setProgress(appId, Progress(Phase.QUEUED))
            val expected = expectedChecksum(config, chosenRelease, chosenAsset)
            e.setProgress(appId, Progress(Phase.DOWNLOADING, 0, chosenAsset.size))
            val from = where(config, chosenAsset)
            // A token goes only to the host it was given for, never to where a source sends the download.
            val sameHost = Urls.host(from.url) == Urls.host(chosenAsset.url)
            val authorization = if (chosenAsset.needsAuth && sameHost) e.tokens.tokenFor(Urls.host(from.url))?.let { "Bearer $it" } else null
            val key = Downloader.key(chosenRelease.id, chosenAsset.url)
            val download = e.downloader.fetch(appId, key, from.url, authorization, from.headers) { done, total ->
                e.setProgress(appId, Progress(Phase.DOWNLOADING, done, total ?: chosenAsset.size))
            }
            if (!download.reused) e.event(appId, EventKind.DOWNLOADED, e.texts.eventDownloaded(download.size))

            e.setProgress(appId, Progress(Phase.VERIFYING))
            val request = GateRequest(
                file = download.file,
                asset = chosenAsset,
                fileSha256 = download.sha256,
                expectedSha256 = expected?.first,
                expectedPackage = config.packageName,
                pinnedSigners = config.pinnedSigners,
                installedOf = { pkg -> e.readInstalled(pkg)?.app },
                device = e.device.profile,
                staging = staging,
                builtInPin = e.builtIn.hold(config),
                allowDowngrade = e.settings.value.allowDowngrades && e.canDowngrade(),
                installsInside = { name -> AssetPicker.installsInside(config.assets, name) },
            )
            val pass = runInterruptible { e.gate.check(request) }
            val facts = pass.facts.copy(checksumMatchedFrom = expected?.second, fileSha256 = download.sha256)
            e.inspector.remember(chosenAsset, chosenRelease.id, facts)
            e.event(appId, EventKind.VERIFIED, e.texts.eventVerified(facts.packageName, facts.versionCode, facts.signers.firstOrNull()?.take(16) ?: "?"))

            e.setProgress(appId, Progress(Phase.INSTALLING))
            val sessionId = try {
                runInterruptible { e.installer.prepare(facts.packageName, pass.apks, e.settings.value.claimUpdateOwnership) }
            } catch (ex: IOException) {
                throw StepFailure(ProblemKind.INSTALL_FAILED, e.texts.installFailed(ex.message))
            } catch (ex: SecurityException) {
                throw StepFailure(ProblemKind.INSTALL_FAILED, e.texts.installFailed(ex.message))
            }
            val pending = PendingInstall(
                sessionId = sessionId,
                packageName = facts.packageName,
                releaseId = chosenRelease.id,
                version = facts.versionName ?: chosenRelease.version,
                versionCode = facts.versionCode,
                fileSha256 = download.sha256,
                fileSize = download.size,
                assetUrl = chosenAsset.url,
                startedAtMs = e.nowMs(),
                signers = facts.signers,
            )
            commit(appId, pending)
            sessionId
        } catch (failure: StepFailure) {
            fail(appId, release, asset, failure.problem)
            null
        } catch (cancel: CancellationException) {
            e.setProgress(appId, null)
            throw cancel
        } catch (io: IOException) {
            Log.w(TAG, "Install of $appId stopped: ${io.javaClass.simpleName}: ${io.message}")
            fail(appId, release, asset, Problem(ProblemKind.STORAGE, e.texts.downloadFailed(io)))
            null
        } catch (bug: RuntimeException) {
            Log.e(TAG, "Install of $appId failed unexpectedly", bug)
            fail(appId, release, asset, Problem(ProblemKind.INSTALL_FAILED, e.texts.installFailed(bug.javaClass.simpleName)))
            null
        }
    }

    /** Hands a prepared session to the installer; the pending install is stored first so no answer can outrun it. */
    internal suspend fun commit(appId: String, pending: PendingInstall) {
        outcomes[appId] = CompletableDeferred()
        e.saveState(appId) { it.copy(pending = pending, installProblem = null, block = null) }
        try {
            runInterruptible { e.installer.commit(appId, pending.sessionId) }
        } catch (ex: IOException) {
            e.saveState(appId) { it.copy(pending = null) }
            throw StepFailure(ProblemKind.INSTALL_FAILED, e.texts.installFailed(ex.message))
        } catch (ex: SecurityException) {
            e.saveState(appId) { it.copy(pending = null) }
            throw StepFailure(ProblemKind.INSTALL_FAILED, e.texts.installFailed(ex.message))
        }
        e.checks.reevaluate(appId, network = false)
        e.publish()
    }

    suspend fun awaitOutcome(appId: String, timeoutMs: Long): Int? = outcomes[appId]?.let { withTimeoutOrNull(timeoutMs) { it.await() } }

    private fun expectedChecksum(config: AppConfig, release: Release, asset: Asset): Pair<String, String>? {
        asset.sha256?.let { sha -> return normalized(sha) to e.evaluator.digestLabel(config.source.type) }
        var fetched: Asset? = null
        val sha = try {
            Checksums.expectedFor(release, asset) { sums ->
                fetched = sums
                val authorization = if (sums.needsAuth) e.tokens.tokenFor(Urls.host(sums.url))?.let { "Bearer $it" } else null
                e.http.execute(HttpRequest(sums.url, authorization = authorization)).use { response ->
                    if (!response.isSuccess) throw IOException("HTTP ${response.status} for ${sums.name}")
                    response.text(SUMS_LIMIT)
                }
            }
        } catch (ex: IOException) {
            throw StepFailure(ProblemKind.NETWORK, e.texts.downloadFailed(ex))
        } ?: return null
        val source = fetched?.let { e.texts.checksumFile(it.name) } ?: e.texts.checksumNotes()
        return normalized(sha) to source
    }

    /** Where the file is to be fetched from now: a source whose links expire is asked for a fresh one. */
    private suspend fun where(config: AppConfig, asset: Asset): Download = runInterruptible(Dispatchers.IO) {
        try {
            e.registry.resolve(config.source, asset, e.sourceContext())
        } catch (ex: SourceException) {
            val problem = e.checks.problemOf(ex)
            throw StepFailure(problem.kind, problem.message)
        }
    }

    private fun normalized(sha: String): String =
        Fingerprints.normalize(sha) ?: throw StepFailure(ProblemKind.CHECKSUM_MISMATCH, e.texts.checksumMismatch())

    private fun fail(appId: String, release: Release?, asset: Asset?, problem: Problem) {
        if (problem.kind in GATE_KINDS && release != null && asset != null) {
            e.saveState(appId) { it.copy(block = GateBlock(release.id, asset.url, problem), installProblem = null) }
            e.event(appId, EventKind.BLOCKED, problem.message)
            e.downloader.discard(appId, Downloader.key(release.id, asset.url))
        } else {
            e.saveState(appId) { it.copy(installProblem = problem) }
            e.event(appId, EventKind.FAILED, problem.message)
        }
        outcomes.remove(appId)?.complete(PackageInstaller.STATUS_FAILURE)
        e.progress.remove(appId)
        e.checks.reevaluate(appId, network = false)
        e.publish()
    }

    suspend fun onResult(appId: String, status: Int, sessionId: Int, message: String?, confirm: Intent?) = withContext(Dispatchers.IO) {
        e.ready()
        val stored = e.stored[appId] ?: return@withContext
        val pending = stored.state.pending
        if (pending == null || pending.sessionId != sessionId) {
            Log.w(TAG, "Result for session $sessionId of $appId, which is not the pending install")
            return@withContext
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                e.saveState(appId) { it.copy(pending = pending.copy(waitingForUser = true)) }
                e.setProgress(appId, Progress(Phase.WAITING_FOR_USER))
                val shown = confirm?.takeIf { it.action == CONFIRM_INSTALL || OtherAppInstaller.isHandoff(e.context, it) }
                if (shown != null) {
                    confirmations[appId] = shown
                    askUser(appId, stored.config.shownName, shown)
                }
                if (!inForeground()) outcomes[appId]?.complete(status)
                return@withContext
            }
            PackageInstaller.STATUS_SUCCESS -> succeeded(appId, pending, notify = appId !in batch)
            PackageInstaller.STATUS_FAILURE_ABORTED -> cancelled(appId)
            else -> failed(appId, problemFor(status, message))
        }
        e.notifier.cancelConfirm(appId)
        confirmations.remove(appId)
        outcomes.remove(appId)?.complete(status)
        e.progress.remove(appId)
        e.checks.reevaluate(appId, network = false)
        e.publish()
    }

    private fun succeeded(appId: String, pending: PendingInstall, notify: Boolean) {
        val now = e.readInstalled(pending.packageName)
        if (now == null || now.app.versionCode != pending.versionCode) {
            failed(appId, Problem(ProblemKind.INSTALL_FAILED, e.texts.installVersionMismatch(pending.versionCode, now?.app?.versionCode ?: 0)))
            return
        }
        if (byOtherApp(pending)) {
            e.installer.abandon(pending.sessionId)
            // Another app installed something; it counts only if it is what the gate passed.
            if (pending.signers.isNotEmpty() && now.app.signers.toSet() != pending.signers.toSet()) {
                failed(appId, Problem(ProblemKind.SIGNER_MISMATCH, e.texts.otherAppSigner()))
                return
            }
        }
        val record = InstallRecord(pending.releaseId, pending.version, now.app.versionCode, pending.fileSha256, pending.fileSize)
        e.saveApp(appId) { s ->
            val pins = s.config.pinnedSigners.ifEmpty { now.app.signers }
            s.copy(
                config = s.config.copy(
                    pinnedSigners = pins,
                    packageName = s.config.packageName ?: pending.packageName,
                    name = Naming.afterInstall(s.config, now.label),
                ),
                state = s.state.copy(pending = null, record = record, installProblem = null, block = null),
            )
        }
        e.event(appId, EventKind.INSTALLED, e.texts.eventInstalled(pending.version, now.app.versionCode))
        if (!e.settings.value.keepInstallers) e.downloader.discard(appId, Downloader.key(pending.releaseId, pending.assetUrl))
        if (notify && e.settings.value.notifyInstalled) e.notifier.installed(listOfNotNull(e.stored[appId]?.config?.shownName))
    }

    /** Nothing went wrong: the row goes back to what it was, and the file stays for the next try. */
    private fun cancelled(appId: String) {
        e.saveState(appId) { it.copy(pending = null, installProblem = null) }
        e.event(appId, EventKind.CANCELLED, e.texts.installCancelled())
    }

    private fun failed(appId: String, problem: Problem) {
        e.stored[appId]?.state?.pending?.takeIf(::byOtherApp)?.let { e.installer.abandon(it.sessionId) }
        e.saveState(appId) { it.copy(pending = null, installProblem = problem) }
        e.event(appId, EventKind.FAILED, problem.message)
    }

    /** Sessions of another installer app are Tern's own numbers, below zero. */
    private fun byOtherApp(pending: PendingInstall): Boolean = pending.sessionId < 0

    /**
     * Another installer app says nothing to Tern when it is done. A package that changed is the
     * sign: when it now has the version handed over, that install happened.
     */
    suspend fun onPackageChanged(packageName: String) {
        for (stored in e.stored.values) {
            val pending = stored.state.pending ?: continue
            if (!byOtherApp(pending) || pending.packageName != packageName) continue
            val now = e.readInstalled(packageName) ?: continue
            if (now.app.versionCode == pending.versionCode) onResult(stored.config.id, PackageInstaller.STATUS_SUCCESS, pending.sessionId, null, null)
        }
    }

    private fun problemFor(status: Int, message: String?): Problem {
        val t = e.texts
        val downgrade = message?.contains("DOWNGRADE", ignoreCase = true) == true
        return when {
            downgrade -> Problem(ProblemKind.DOWNGRADE, t.installDowngrade())
            status == PackageInstaller.STATUS_FAILURE_BLOCKED -> Problem(ProblemKind.INSTALL_FAILED, t.installBlocked())
            status == PackageInstaller.STATUS_FAILURE_CONFLICT -> Problem(ProblemKind.INSTALL_FAILED, t.installConflict())
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Problem(ProblemKind.INSTALL_FAILED, t.installIncompatible())
            status == PackageInstaller.STATUS_FAILURE_INVALID -> Problem(ProblemKind.INSTALL_FAILED, t.installInvalid())
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> Problem(ProblemKind.STORAGE, t.installStorage())
            status == PackageInstaller.STATUS_FAILURE_TIMEOUT -> Problem(ProblemKind.INSTALL_FAILED, t.installTimeout())
            else -> Problem(ProblemKind.INSTALL_FAILED, t.installFailed(message))
        }
    }

    private fun askUser(appId: String, name: String, confirm: Intent) {
        if (inForeground()) {
            try {
                e.context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (ex: RuntimeException) {
                Log.w(TAG, "Could not open the install confirmation: ${ex.message}")
            }
        }
        e.notifier.confirm(appId, name, confirm)
    }

    private fun inForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** At start: old sessions of ours are abandoned, and a pending install with no live session is settled from PackageManager. */
    fun reconcile() {
        e.installer.abandonOlderThan(DAY_MS, e.nowMs())
        val live = e.installer.liveSessionIds()
        for (stored in e.stored.values) {
            val pending = stored.state.pending ?: continue
            val id = stored.config.id
            if (pending.sessionId in live) {
                e.progress[id] = Progress(if (pending.waitingForUser) Phase.WAITING_FOR_USER else Phase.INSTALLING)
                continue
            }
            settle(id, pending)
        }
    }

    /** A pending install whose session is gone: PackageManager alone says whether it happened. */
    private fun settle(appId: String, pending: PendingInstall) {
        val now = e.readInstalled(pending.packageName)
        if (now != null && now.app.versionCode == pending.versionCode) {
            succeeded(appId, pending, notify = false)
        } else {
            e.saveState(appId) { it.copy(pending = null, installProblem = Problem(ProblemKind.INSTALL_FAILED, e.texts.installNotFinished())) }
        }
    }

    suspend fun runScheduled(settings: Settings) {
        val targets = e.stored.values.filter { checkedInTheBackground(it.config) && e.inWholeListCheck(it.config) }.map { it.config.id }
        if (settings.notifyChecking && targets.isNotEmpty()) e.notifier.checking(targets.size)
        val checked = try {
            e.checks.checkMany(targets)
        } finally {
            e.notifier.doneChecking()
        }
        val installed = ArrayList<String>()
        val failed = ArrayList<String>()
        // Without the permission Android would ask from a notification and then call the install cancelled.
        val allowed = e.mayInstallUnattended()
        for (id in targets) {
            val config = e.stored[id]?.config ?: continue
            if (!installsByItself(config, allowed, e.evaluations[id], busy = e.progress.containsKey(id))) continue
            batch += id
            try {
                val session = run(id, null, null)
                val status = if (session != null) awaitOutcome(id, INSTALL_WAIT_MS) else null
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> installed += config.shownName
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
                    else -> failed += config.shownName
                }
            } finally {
                batch -= id
            }
        }
        val names = { ids: List<String> -> ids.mapNotNull { e.stored[it]?.config?.shownName } }
        val named = { ids: List<String> -> ids.mapNotNull { id -> e.stored[id]?.config?.shownName?.let { id to it } } }
        // A muted app is still checked and shown; it only makes no sound.
        val heard = checked.filter { it.newRelease && e.stored[it.id]?.config?.muted != true }
        val fresh = heard.filter { e.evaluations[it.id]?.status == AppStatus.UPDATE_AVAILABLE }.map { it.id }
        val tracked = heard.filter { e.evaluations[it.id]?.status == AppStatus.NEW_RELEASE }.map { it.id }
        if (settings.notifyUpdates) e.notifier.updates(named(fresh))
        if (settings.notifyTracked) e.notifier.tracked(named(tracked))
        if (settings.notifyInstalled) e.notifier.installed(installed)
        if (settings.notifyFailures) e.notifier.failures(names(checked.filter { it.failed }.map { it.id }) + failed)
    }

    internal companion object {
        /**
         * Whether the background check may install an update of this app without being asked.
         * Only where the user set this very app to it, Android lets Tern install at all, and
         * the update is sure and has nothing held against it.
         */
        fun installsByItself(config: AppConfig, mayInstall: Boolean, evaluation: Evaluation?, busy: Boolean): Boolean =
            mayInstall && config.updates == UpdateMode.AUTO && !config.trackOnly && !busy && evaluation != null &&
                evaluation.status == AppStatus.UPDATE_AVAILABLE && evaluation.certain && evaluation.problem == null

        /** Which apps the background check looks at: all but those the user set to be left alone. */
        fun checkedInTheBackground(config: AppConfig): Boolean = config.updates != UpdateMode.MANUAL

        private const val TAG = "TernInstalls"
        private const val CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val SUMS_LIMIT = 1024 * 1024
        private const val INSTALL_WAIT_MS = 3 * 60 * 1000L

        /** Tar archives compressed in a way Android has no reader for; plain and gzipped ones are opened. */
        private val UNOPENED_ARCHIVES = listOf(".tar.bz2", ".tbz2", ".tbz", ".tar.xz", ".txz", ".tar.zst")
        private val GATE_KINDS = setOf(
            ProblemKind.CHECKSUM_MISMATCH, ProblemKind.SIGNER_MISMATCH, ProblemKind.PIN_MISMATCH, ProblemKind.PACKAGE_MISMATCH,
            ProblemKind.DOWNGRADE, ProblemKind.UNSUPPORTED, ProblemKind.NO_FILE_FOR_DEVICE, ProblemKind.PARSE,
        )
    }
}
