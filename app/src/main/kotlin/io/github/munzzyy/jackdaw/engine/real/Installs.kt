package io.github.munzzyy.jackdaw.engine.real

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.github.munzzyy.jackdaw.core.engine.InstallRecord
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.UpdateMode
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.Urls
import io.github.munzzyy.jackdaw.core.verify.Checksums
import io.github.munzzyy.jackdaw.core.verify.Fingerprints
import io.github.munzzyy.jackdaw.data.GateBlock
import io.github.munzzyy.jackdaw.data.PendingInstall
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.engine.Progress
import io.github.munzzyy.jackdaw.engine.Settings
import io.github.munzzyy.jackdaw.install.GateRequest
import io.github.munzzyy.jackdaw.install.StepFailure
import io.github.munzzyy.jackdaw.work.TransferService
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
    private val outcomes = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val batch: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val confirmations = ConcurrentHashMap<String, Intent>()

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
        val confirm = confirmations[appId]
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
        val stored = e.stored[appId] ?: return@withContext null
        val config = stored.config
        var release: Release? = null
        var asset: Asset? = null
        try {
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
            if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar")) throw StepFailure(ProblemKind.UNSUPPORTED, e.texts.tarUnsupported())

            e.setProgress(appId, Progress(Phase.QUEUED))
            val expected = expectedChecksum(config, chosenRelease, chosenAsset)
            e.setProgress(appId, Progress(Phase.DOWNLOADING, 0, chosenAsset.size))
            val authorization = if (chosenAsset.needsAuth) e.tokens.tokenFor(Urls.host(chosenAsset.url))?.let { "Bearer $it" } else null
            val download = e.downloader.fetch(appId, chosenAsset.url, authorization) { done, total ->
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
                staging = File(e.staging, e.downloader.folder(appId).name),
            )
            val pass = runInterruptible { e.gate.check(request) }
            val facts = pass.facts.copy(checksumMatchedFrom = expected?.second)
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
            )
            commit(appId, pending)
            request.staging.deleteRecursively()
            sessionId
        } catch (failure: StepFailure) {
            fail(appId, release, asset, failure.problem)
            null
        } catch (cancel: CancellationException) {
            e.setProgress(appId, null)
            throw cancel
        } catch (io: IOException) {
            Log.w(TAG, "Install of $appId stopped: ${io.javaClass.simpleName}: ${io.message}")
            fail(appId, release, asset, Problem(ProblemKind.STORAGE, e.texts.downloadFailed(io.message)))
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
            throw StepFailure(ProblemKind.NETWORK, e.texts.downloadFailed(ex.message))
        } ?: return null
        val source = fetched?.let { e.texts.checksumFile(it.name) } ?: e.texts.checksumNotes()
        return normalized(sha) to source
    }

    private fun normalized(sha: String): String =
        Fingerprints.normalize(sha) ?: throw StepFailure(ProblemKind.CHECKSUM_MISMATCH, e.texts.checksumMismatch())

    private fun fail(appId: String, release: Release?, asset: Asset?, problem: Problem) {
        if (problem.kind in GATE_KINDS && release != null && asset != null) {
            e.saveState(appId) { it.copy(block = GateBlock(release.id, asset.url, problem), installProblem = null) }
            e.event(appId, EventKind.BLOCKED, problem.message)
            e.downloader.discard(appId, asset.url)
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
                val shown = confirm?.takeIf { it.action == CONFIRM_INSTALL }
                if (shown != null) {
                    confirmations[appId] = shown
                    askUser(appId, stored.config.name, shown)
                }
                if (!inForeground()) outcomes[appId]?.complete(status)
                return@withContext
            }
            PackageInstaller.STATUS_SUCCESS -> succeeded(appId, pending, notify = appId !in batch)
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
        if (!e.settings.value.keepInstallers) e.downloader.discard(appId, pending.assetUrl)
        if (notify && e.settings.value.notifyInstalled) e.notifier.installed(listOfNotNull(e.stored[appId]?.config?.name))
    }

    private fun failed(appId: String, problem: Problem) {
        e.saveState(appId) { it.copy(pending = null, installProblem = problem) }
        e.event(appId, EventKind.FAILED, problem.message)
    }

    private fun problemFor(status: Int, message: String?): Problem {
        val t = e.texts
        val downgrade = message?.contains("DOWNGRADE", ignoreCase = true) == true
        return when {
            downgrade -> Problem(ProblemKind.DOWNGRADE, t.installDowngrade())
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> Problem(ProblemKind.INSTALL_FAILED, t.installCancelled())
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
        val targets = e.stored.values.filter { it.config.updates != UpdateMode.MANUAL }.map { it.config.id }
        val checked = e.checks.checkMany(targets)
        val installed = ArrayList<String>()
        val failed = ArrayList<String>()
        for (id in targets) {
            val config = e.stored[id]?.config ?: continue
            if (config.updates != UpdateMode.AUTO || config.trackOnly) continue
            val eval = e.evaluations[id] ?: continue
            if (eval.status != AppStatus.UPDATE_AVAILABLE || !eval.certain || eval.problem != null || e.progress.containsKey(id)) continue
            batch += id
            try {
                val session = run(id, null, null)
                val status = if (session != null) awaitOutcome(id, INSTALL_WAIT_MS) else null
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> installed += config.name
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
                    else -> failed += config.name
                }
            } finally {
                batch -= id
            }
        }
        val names = { ids: List<String> -> ids.mapNotNull { e.stored[it]?.config?.name } }
        val fresh = checked.filter { it.newRelease && e.evaluations[it.id]?.status == AppStatus.UPDATE_AVAILABLE }.map { it.id }
        if (settings.notifyUpdates) e.notifier.updates(names(fresh))
        if (settings.notifyInstalled) e.notifier.installed(installed)
        if (settings.notifyFailures) e.notifier.failures(names(checked.filter { it.failed }.map { it.id }) + failed)
    }

    private companion object {
        const val TAG = "JackdawInstalls"
        const val CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val SUMS_LIMIT = 1024 * 1024
        const val INSTALL_WAIT_MS = 3 * 60 * 1000L
        val GATE_KINDS = setOf(
            ProblemKind.CHECKSUM_MISMATCH, ProblemKind.SIGNER_MISMATCH, ProblemKind.PIN_MISMATCH, ProblemKind.PACKAGE_MISMATCH,
            ProblemKind.DOWNGRADE, ProblemKind.UNSUPPORTED, ProblemKind.NO_FILE_FOR_DEVICE, ProblemKind.PARSE,
        )
    }
}
