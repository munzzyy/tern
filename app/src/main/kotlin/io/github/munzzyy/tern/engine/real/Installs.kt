package io.github.munzzyy.tern.engine.real

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.FileProvider
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
import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.install.Downloader
import io.github.munzzyy.tern.install.FetchedPart
import io.github.munzzyy.tern.install.GateRequest
import io.github.munzzyy.tern.install.ObbFile
import io.github.munzzyy.tern.install.ObbOutcome
import io.github.munzzyy.tern.install.OtherAppInstaller
import io.github.munzzyy.tern.install.PartsZip
import io.github.munzzyy.tern.install.StepFailure
import io.github.munzzyy.tern.install.VerifiedApps
import io.github.munzzyy.tern.log.TernLog
import io.github.munzzyy.tern.work.Installed
import io.github.munzzyy.tern.work.TransferService
import io.github.munzzyy.tern.work.Trouble
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val self = e.stored[appId]?.config?.let(e::isSelf) == true
        val job = e.scope.launch {
            if (self) untilOthersDone(appId)
            run(appId, releaseId, assetUrl, picked = userStarted && releaseId != null)
        }
        jobs[appId] = job
        job.invokeOnCompletion {
            jobs.remove(appId, job)
            e.userTransfers -= appId
            e.publish()
        }
        if (userStarted) TransferService.start(e.context)
        return job
    }

    /**
     * Tern's own update waits for every other install under way, one waiting for the person
     * included: once it is in, Android restarts Tern and whatever still ran would stop. After ten
     * minutes it goes ahead all the same.
     */
    private suspend fun untilOthersDone(appId: String) {
        // Installs started together, as by Update all, have all begun by then.
        delay(SETTLE_MS)
        val until = e.nowMs() + SELF_WAIT_MS
        while (e.progress.keys.any { it != appId } && e.nowMs() < until) delay(POLL_MS)
    }

    /** The one download at a time the settings may ask for; otherwise downloads run side by side. */
    private val turn = Mutex()

    internal suspend fun <T> inTurn(block: suspend () -> T): T = if (e.settings.value.oneDownloadAtATime) turn.withLock { block() } else block()

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
                TernLog.w(TAG, "Could not reopen the install confirmation: ${ex.message}")
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

    /**
     * Returns the session handed to the installer, or null when the pipeline stopped before that.
     * [picked] is a release the person chose by hand, the only install that may go to an older version.
     */
    suspend fun run(appId: String, releaseId: String?, assetUrl: String?, picked: Boolean = false): Int? = withContext(Dispatchers.IO) {
        e.ready()
        if (!busy.add(appId)) return@withContext null
        val staging = File(e.staging, e.downloader.folder(appId).name)
        try {
            // A source whose file addresses do not last is asked again first, unless a particular file was picked.
            if (releaseId == null && assetUrl == null && e.stored[appId]?.config?.refreshFirst == true) e.checks.run(listOf(appId), CheckCause.INSTALL)
            pipeline(appId, releaseId, assetUrl, staging, picked)
        } finally {
            staging.deleteRecursively()
            busy.remove(appId)
        }
    }

    private suspend fun pipeline(appId: String, releaseId: String?, assetUrl: String?, staging: File, picked: Boolean): Int? {
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
                ?: e.evaluator.bestFile(config, chosenRelease)
                ?: throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, e.texts.noFileForDevice(null))
            asset = chosenAsset
            val lower = chosenAsset.name.lowercase()
            if (UNOPENED_ARCHIVES.any { lower.endsWith(it) }) throw StepFailure(ProblemKind.UNSUPPORTED, e.texts.archiveCompressionUnsupported())

            e.setProgress(appId, Progress(Phase.QUEUED))
            val expected = expectedChecksum(config, chosenRelease, chosenAsset)
            e.setProgress(appId, Progress(Phase.DOWNLOADING, 0, chosenAsset.size))
            // A base and its splits come one by one, each from its own address, and count as one download.
            val files = filesOf(chosenAsset)
            var before = 0L
            val downloads = inTurn {
                files.map { file ->
                    val from = where(config, file)
                    // A token goes only to the host it was given for, never to where a source sends the download.
                    val sameHost = Urls.host(from.url) == Urls.host(file.url)
                    val authorization = if (file.needsAuth && sameHost) e.tokens.tokenFor(Urls.host(from.url))?.let { "Bearer $it" } else null
                    e.downloader.fetch(appId, Downloader.key(chosenRelease.id, file.url), from.url, authorization, from.headers) { done, total ->
                        val whole = if (files.size == 1) total ?: chosenAsset.size else chosenAsset.size
                        e.setProgress(appId, Progress(Phase.DOWNLOADING, before + done, whole))
                    }.also { before += it.size }
                }
            }
            val download = downloads.first()
            val fetched = downloads.filterNot { it.reused }
            if (fetched.isNotEmpty()) e.event(appId, EventKind.DOWNLOADED, e.texts.eventDownloaded(fetched.sumOf { it.size }))

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
                allowDowngrade = allowsDowngrade(picked, e.settings.value.allowDowngrades, e::canDowngrade),
                claimedVersionCode = chosenRelease.versionCode,
                installsInside = { name -> AssetPicker.installsInside(config.assets, name) },
                parts = files.drop(1).zip(downloads.drop(1)) { file, got -> FetchedPart(file.name, got.file) },
                unpacksObb = e.installer.placesObb,
            )
            val pass = runInterruptible { e.gate.check(request) }
            val facts = pass.facts.copy(checksumMatchedFrom = expected?.second, fileSha256 = download.sha256)
            e.inspector.remember(chosenAsset, chosenRelease.id, facts)
            val verified = e.texts.eventVerified(facts.packageName, facts.versionCode, facts.signers.firstOrNull()?.take(16) ?: "?")
            // Installing through another installer than the one chosen is never silent.
            val fellBack = e.installers.fellBack()?.let { " " + e.texts.installerFellBack(it) }.orEmpty()
            e.event(appId, EventKind.VERIFIED, verified + fellBack)
            val update = e.readInstalled(facts.packageName) != null
            updating[appId] = update
            if (!update) handToVerifier(appId, pass.apks.first(), facts.packageName)

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
                fileSize = downloads.sumOf { it.size },
                assetUrl = chosenAsset.url,
                startedAtMs = e.nowMs(),
                signers = facts.signers,
                partUrls = chosenAsset.parts,
            )
            commit(appId, pending)
            if (pass.obbs.isNotEmpty()) placeObb(appId, sessionId, facts.packageName, chosenAsset, pass.obbs)
            sessionId
        } catch (failure: StepFailure) {
            fail(appId, release, asset, failure.problem)
            null
        } catch (cancel: CancellationException) {
            e.setProgress(appId, null)
            throw cancel
        } catch (io: IOException) {
            TernLog.w(TAG, "Install of $appId stopped: ${io.javaClass.simpleName}: ${io.message}")
            fail(appId, release, asset, Problem(ProblemKind.STORAGE, e.texts.downloadFailed(io)))
            null
        } catch (bug: RuntimeException) {
            TernLog.e(TAG, "Install of $appId failed unexpectedly", bug)
            fail(appId, release, asset, Problem(ProblemKind.INSTALL_FAILED, e.texts.installFailed(bug.javaClass.simpleName)))
            null
        }
    }

    /**
     * Before the first install of an app, where the setting asks and Verified Apps is on the
     * device, hands it a copy of the checked file, read-only, and waits for the person to come back
     * to Tern, as Obtainium does. The installer then gets the file the gate passed, whatever
     * happened there.
     */
    private suspend fun handToVerifier(appId: String, apk: File, packageName: String) {
        val send = { pkg: String -> Intent(Intent.ACTION_SEND).setPackage(pkg).setType(APK_MIME) }
        val pm = e.context.packageManager
        val verifier = VerifiedApps.first({ VerifiedApps.signers(pm, it) }) { send(it).resolveActivity(pm) != null }
        if (!VerifiedApps.handsOver(e.settings.value.shareToVerifier, firstInstall = true, there = appId in e.userTransfers && inForeground(), verifier = verifier)) return
        val copy = runInterruptible {
            val folder = File(e.context.cacheDir, "${OtherAppInstaller.FOLDER}/$VERIFY_FOLDER").apply {
                deleteRecursively()
                mkdirs()
            }
            apk.copyTo(File(folder, "$packageName.apk"), overwrite = true)
        }
        val uri = FileProvider.getUriForFile(e.context, OtherAppInstaller.authority(e.context), copy)
        val intent = send(verifier ?: return).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            e.context.startActivity(intent)
        } catch (ex: RuntimeException) {
            TernLog.w(TAG, "Could not hand the file to $verifier: ${ex.javaClass.simpleName}")
            return
        }
        // Tern leaves the screen while the person is there, and the install goes on once they are back.
        withTimeoutOrNull(VERIFIER_OPENS_MS) { while (inForeground()) delay(POLL_MS) } ?: return
        withTimeoutOrNull(VERIFIER_WAIT_MS) { while (!inForeground()) delay(POLL_MS) }
    }

    /** Whether each install under way replaces an app on the device, kept for the notification that says it is done. */
    private val updating = ConcurrentHashMap<String, Boolean>()

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

    /**
     * Puts the OBB files of the archive in the app's OBB folder once the install succeeded. Only
     * the Shizuku and root installers can write there, and Tern asks for no storage of its own, so
     * with another installer the activity says where the files are instead.
     */
    private suspend fun placeObb(appId: String, sessionId: Int, packageName: String, asset: Asset, obbs: List<ObbFile>) {
        val folder = "Android/obb/$packageName"
        val names = obbs.joinToString(", ") { it.name }
        val message = try {
            when (runInterruptible { e.installer.placeObb(sessionId, packageName, obbs) }) {
                ObbOutcome.PLACED -> e.texts.obbPlaced(obbs.size, folder)
                ObbOutcome.CANNOT -> e.texts.obbNotPlaced(folder, asset.name, names)
                ObbOutcome.NOT_INSTALLED -> return
            }
        } catch (ex: IOException) {
            TernLog.w(TAG, "OBB files of $appId were not put in place: ${ex.message}")
            e.texts.obbFailed(folder, ex.message, asset.name, names)
        }
        e.event(appId, EventKind.MOVED, message)
    }

    private fun expectedChecksum(config: AppConfig, release: Release, asset: Asset): Pair<String, String>? {
        asset.sha256?.let { sha -> return normalized(sha) to e.evaluator.digestLabel(config.source) }
        var fetched: Asset? = null
        val sha = try {
            Checksums.expectedFor(release, asset) { sums ->
                fetched = sums
                // Asked for where the source says, as the file is: a file behind GitHub's API needs the header that asks for the file itself.
                val from = try {
                    e.registry.resolve(config.source, sums, e.sourceContext())
                } catch (ex: SourceException) {
                    throw IOException(ex.message, ex)
                }
                val sameHost = Urls.host(from.url) == Urls.host(sums.url)
                val authorization = if (sums.needsAuth && sameHost) e.tokens.tokenFor(Urls.host(sums.url))?.let { "Bearer $it" } else null
                e.http.execute(HttpRequest(from.url, headers = from.headers, authorization = authorization)).use { response ->
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

    /**
     * The files [asset] stands for, each as an asset of its own: the file it names, then the splits
     * it names beside it. A checksum and a size belong to the asset as a whole, so a split has none.
     */
    private fun filesOf(asset: Asset): List<Asset> =
        listOf(asset) + asset.parts.map { url -> asset.copy(name = PartsZip.nameOf(url), url = url, size = null, sha256 = null, signers = emptyList(), parts = emptyList()) }

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
            for (url in listOf(asset.url) + asset.parts) e.downloader.discard(appId, Downloader.key(release.id, url))
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
            TernLog.w(TAG, "Result for session $sessionId of $appId, which is not the pending install")
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
        updating.remove(appId)
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
        if (byOtherApp(pending)) e.installer.abandon(pending.sessionId)
        // It counts only if it is what the gate passed: another app, or Dhizuku, holds the session it installed from and could change it.
        if (pending.signers.isNotEmpty() && now.app.signers.toSet() != pending.signers.toSet()) {
            failed(appId, Problem(ProblemKind.SIGNER_MISMATCH, if (byOtherApp(pending)) e.texts.otherAppSigner() else e.texts.installedSignerDiffers()))
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
        e.notifier.installedUpdate(appId) { id -> e.stored[id]?.config?.shownName?.takeIf { e.evaluations[id]?.status == AppStatus.UPDATE_AVAILABLE } }
        if (!e.settings.value.keepInstallers) {
            for (url in listOf(pending.assetUrl) + pending.partUrls) e.downloader.discard(appId, Downloader.key(pending.releaseId, url))
        }
        val update = updating.remove(appId) ?: true
        if (notify && e.settings.value.notifyInstalled) {
            e.stored[appId]?.config?.shownName?.let { e.notifier.installed(listOf(Installed(appId, it, pending.version, update))) }
        }
    }

    /** The apps that could not be checked or updated among [ids], each with the words its row shows for why. */
    private fun troubles(ids: List<String>): List<Trouble> = ids.distinct().mapNotNull { id ->
        val stored = e.stored[id] ?: return@mapNotNull null
        val reason = e.evaluations[id]?.problem?.message ?: stored.state.installProblem?.message ?: stored.state.checkProblem?.message
        Trouble(id, stored.config.shownName, reason ?: e.texts.installFailed(null))
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
                TernLog.w(TAG, "Could not open the install confirmation: ${ex.message}")
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

    /**
     * Checks [only] or every app the background looks at, and installs what may install by itself.
     * With [installsNow] false such updates wait, and the run says so. [cause] is what the log says started it.
     */
    /** [check] false installs from what the last check found, as the waiting job does. */
    suspend fun runScheduled(
        settings: Settings,
        installsNow: Boolean = true,
        only: Set<String>? = null,
        cause: CheckCause = CheckCause.SCHEDULE,
        check: Boolean = true,
    ): ScheduledRun {
        val targets = e.stored.values
            .filter { checkedInTheBackground(it.config) && e.inWholeListCheck(it.config) && (only == null || it.config.id in only) }
            .map { it.config.id }
        val checked: List<CheckOutcome> = if (!check) {
            emptyList()
        } else {
            if (settings.notifyChecking && targets.isNotEmpty()) e.notifier.checking(targets.size)
            try {
                e.checks.run(targets, cause)
            } finally {
                e.notifier.doneChecking()
            }
        }
        val installed = ArrayList<Installed>()
        val failed = ArrayList<String>()
        // Without the permission Android would ask from a notification and then call the install cancelled.
        val allowed = e.mayInstallUnattended()
        var waited = false
        for (id in selfLast(targets) { e.stored[it]?.config?.let(e::isSelf) == true }) {
            val config = e.stored[id]?.config ?: continue
            if (!installsByItself(config, allowed, e.evaluations[id], busy = e.progress.containsKey(id))) continue
            if (!installsNow) {
                waited = true
                continue
            }
            batch += id
            try {
                val session = run(id, null, null)
                val status = if (session != null) awaitOutcome(id, INSTALL_WAIT_MS) else null
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> installed += Installed(id, config.shownName, e.stored[id]?.state?.record?.version)
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
                    else -> failed += id
                }
            } finally {
                batch -= id
            }
        }
        val named = { ids: List<String> -> ids.mapNotNull { id -> e.stored[id]?.config?.shownName?.let { id to it } } }
        // A muted app is still checked and shown; it only makes no sound.
        val heard = checked.filter { it.newRelease && e.stored[it.id]?.config?.muted != true }
        val fresh = heard.filter { e.evaluations[it.id]?.status == AppStatus.UPDATE_AVAILABLE }.map { it.id }
        val tracked = heard.filter { e.evaluations[it.id]?.status == AppStatus.NEW_RELEASE }.map { it.id }
        if (settings.notifyUpdates) e.notifier.updates(named(fresh))
        if (settings.notifyTracked) e.notifier.tracked(named(tracked))
        if (settings.notifyInstalled) e.notifier.installed(installed)
        if (settings.notifyFailures) e.notifier.failures(troubles(checked.filter { it.failed }.map { it.id } + failed))
        return ScheduledRun(waited, checked.filter { it.failed }.map { it.id })
    }

    /** What a scheduled run left to do: updates that waited for Wi-Fi or charging, and apps that could not be checked. */
    internal data class ScheduledRun(val waited: Boolean, val failed: List<String>)

    internal companion object {
        /**
         * Whether the background check may install an update of this app without being asked.
         * Only where the user set this very app to it, Android lets Tern install at all, and
         * the update is sure and has nothing held against it.
         */
        fun installsByItself(config: AppConfig, mayInstall: Boolean, evaluation: Evaluation?, busy: Boolean): Boolean =
            mayInstall && config.updates == UpdateMode.AUTO && !config.trackOnly && !busy && evaluation != null &&
                evaluation.status == AppStatus.UPDATE_AVAILABLE && evaluation.certain && evaluation.problem == null

        /**
         * Whether an install may put an older version over a newer one: only a release the person
         * [picked] by hand, with the setting on and something there that lets Android do it.
         * Background installs, Update all and Install latest never go back.
         */
        fun allowsDowngrade(picked: Boolean, setting: Boolean, canDowngrade: () -> Boolean): Boolean = picked && setting && canDowngrade()

        /** Which apps the background check looks at: all but those the user set to be left alone. */
        fun checkedInTheBackground(config: AppConfig): Boolean = config.updates != UpdateMode.MANUAL

        /** [ids] in their order, but the one [isSelf] names last: Tern's own update restarts Tern. */
        fun <T> selfLast(ids: List<T>, isSelf: (T) -> Boolean): List<T> = ids.sortedBy(isSelf)

        private const val SETTLE_MS = 1000L
        private const val SELF_WAIT_MS = 10 * 60 * 1000L
        private const val POLL_MS = 1000L
        private const val VERIFIER_OPENS_MS = 5000L
        private const val VERIFIER_WAIT_MS = 10 * 60 * 1000L
        private const val VERIFY_FOLDER = "verify"
        private const val APK_MIME = "application/vnd.android.package-archive"

        private const val TAG = "TernInstalls"
        private const val CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val SUMS_LIMIT = 1024 * 1024
        private const val INSTALL_WAIT_MS = 3 * 60 * 1000L

        /** Tar archives compressed with zstd, which neither Tern nor Obtainium reads; plain ones and gzip, bzip2 and xz are opened. */
        private val UNOPENED_ARCHIVES = listOf(".tar.zst", ".tzst")
        private val GATE_KINDS = setOf(
            ProblemKind.CHECKSUM_MISMATCH, ProblemKind.SIGNER_MISMATCH, ProblemKind.PIN_MISMATCH, ProblemKind.PACKAGE_MISMATCH,
            ProblemKind.DOWNGRADE, ProblemKind.UNSUPPORTED, ProblemKind.NO_FILE_FOR_DEVICE, ProblemKind.PARSE,
        )
    }
}
