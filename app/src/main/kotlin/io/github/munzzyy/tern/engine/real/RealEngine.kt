package io.github.munzzyy.tern.engine.real

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.munzzyy.tern.BuildConfig
import io.github.munzzyy.tern.core.icon.IconAddresses
import io.github.munzzyy.tern.core.interop.AppConfigJson
import io.github.munzzyy.tern.core.interop.AppConfigJsonException
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.GitHubProxy
import io.github.munzzyy.tern.core.net.GitHubProxyHttp
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.ValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.data.KeptPins
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.PoliteHttp
import io.github.munzzyy.tern.core.net.RateLimiter
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.core.suggest.Catalog
import io.github.munzzyy.tern.core.suggest.SuggestedApp
import io.github.munzzyy.tern.core.verify.Fingerprints
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.SettingsStore
import io.github.munzzyy.tern.data.Store
import io.github.munzzyy.tern.data.StoredApp
import io.github.munzzyy.tern.data.TokenVault
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.CheckCount
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.engine.ExportStatus
import io.github.munzzyy.tern.engine.Handoff
import io.github.munzzyy.tern.engine.HandoffEnd
import io.github.munzzyy.tern.engine.OrbotState
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.ImportSummary
import io.github.munzzyy.tern.engine.InstallerChoice
import io.github.munzzyy.tern.engine.InstallerReadiness
import io.github.munzzyy.tern.engine.NoteBlock
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.Reading
import io.github.munzzyy.tern.engine.Received
import io.github.munzzyy.tern.engine.SavedFile
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.engine.Suggestion
import io.github.munzzyy.tern.install.ArchiveReader
import io.github.munzzyy.tern.install.Downloader
import io.github.munzzyy.tern.install.Gate
import io.github.munzzyy.tern.install.InstallGate
import io.github.munzzyy.tern.install.Installer
import io.github.munzzyy.tern.install.OtherAppInstaller
import io.github.munzzyy.tern.install.PackageManagerArchiveReader
import io.github.munzzyy.tern.log.Journal
import io.github.munzzyy.tern.log.TernLog
import io.github.munzzyy.tern.net.Orbot
import io.github.munzzyy.tern.net.ProxyChoice
import io.github.munzzyy.tern.net.PinChoice
import io.github.munzzyy.tern.net.ProxyDoor
import io.github.munzzyy.tern.net.ProxyProbe
import io.github.munzzyy.tern.net.UrlConnectionHttp
import io.github.munzzyy.tern.work.Notifier
import io.github.munzzyy.tern.work.Scheduler
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * The engine behind the screens. Installed versions are always read from PackageManager and never
 * stored; what is stored is the listing, the record of what Tern itself installed, and problems.
 */
class RealEngine(
    context: Context,
    transport: HttpClient,
    storeName: String = Store.DEFAULT_NAME,
    prefsPrefix: String = "",
    internal val nowMs: () -> Long = System::currentTimeMillis,
    archiveReader: ArchiveReader? = null,
    installer: Installer? = null,
    gate: Gate? = null,
    downloadsDir: File? = null,
    orbotInstalled: (() -> Boolean)? = null,
    private val catalog: List<SuggestedApp> = Catalog.all,
) : Engine, Closeable {
    internal val context: Context = context.applicationContext
    internal val store = Store(this.context, storeName)
    private val settingsStore = SettingsStore(this.context, prefsPrefix + SettingsStore.DEFAULT_NAME)
    internal val keptPins = KeptPins(this.context, prefsPrefix + KeptPins.DEFAULT_NAME, nowMs)
    private val vault = TokenVault(this.context, prefsPrefix + TokenVault.DEFAULT_NAME)
    internal val texts = Texts(this.context)
    internal val device = Device(this.context)
    /** No token for GitHub while its requests go through a hubproxy, which must never see one. */
    internal val tokens = TokenProvider { host -> if (_settings.value.githubProxy != null && GitHubProxy.isGitHubHost(host)) null else vault.tokenFor(host) }
    internal val http: HttpClient = PoliteHttp(GitHubProxyHttp(transport) { _settings.value.githubProxy }, RateLimiter(nowMs), "Tern/${BuildConfig.VERSION_NAME}")
    internal val registry = SourceRegistry.standard(::trackedInRepository)
    internal val inspector = FileInspector(http, store, tokens, device.sdk)
    internal val builtIn = BuiltInPins(catalog)
    internal val evaluator = Evaluator(
        texts, device.profile, builtIn, nowMs,
        globalFilter = { _settings.value.globalFileFilter },
        globalMinAgeDays = { _settings.value.minAgeDaysByDefault },
        githubProxy = { _settings.value.githubProxy },
    )
    internal val downloader = Downloader(http, downloadsDir ?: File(this.context.filesDir, "downloads"), texts)
    internal val gate: Gate = gate ?: InstallGate(archiveReader ?: PackageManagerArchiveReader(this.context.packageManager), texts)
    private val installers = Installers(this)
    internal val installer: Installer = installer ?: installers.routing
    internal val notifier = Notifier(this.context, texts) { _settings.value.notifyNames }
    internal val staging = File(this.context.cacheDir, "staging")

    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> TernLog.e(TAG, "Engine task failed", e) },
    )

    internal val stored = ConcurrentHashMap<String, StoredApp>()
    internal val evaluations = ConcurrentHashMap<String, Evaluation>()
    internal val progress = ConcurrentHashMap<String, Progress>()
    internal val checking: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val deviceApps = ConcurrentHashMap<String, DeviceSlot>()
    internal val userTransfers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val saves = Saves()

    private val _apps = MutableStateFlow<List<AppRow>>(emptyList())
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    private val _settings = MutableStateFlow(settingsStore.load())
    private val _checkingAll = MutableStateFlow(false)
    private val _checkCount = MutableStateFlow<CheckCount?>(null)
    private val _transfers = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /**
     * Keeps Tern's own messages in the log while the setting says so, one after another on the
     * store's thread. What goes wrong there is said to Android's log alone, and kept nowhere.
     */
    private val journal = Journal({ _settings.value.keepOwnMessages }) { kind, text ->
        val at = nowMs()
        scope.launch(store.dispatcher) {
            try {
                store.addEvent(at, null, null, kind, text)
                publishEvents()
            } catch (e: RuntimeException) {
                TernLog.i(TAG, "A message of Tern's own could not be kept: ${e.javaClass.simpleName}")
            }
        }
    }.also { TernLog.journal = it }

    override val apps get() = _apps.asStateFlow()
    override val events: StateFlow<List<Event>> get() = _events.asStateFlow()
    override val settings: StateFlow<Settings> get() = _settings.asStateFlow()
    override val checkingAll: StateFlow<Boolean> get() = _checkingAll.asStateFlow()
    override val checkCount: StateFlow<CheckCount?> get() = _checkCount.asStateFlow()

    /** Downloads the user started, which keep the transfer service in the foreground. */
    val transfers: StateFlow<Map<String, Progress>> get() = _transfers.asStateFlow()

    private val _online = MutableStateFlow(true)
    private val _lastRunProblem = MutableStateFlow<Problem?>(null)

    override val online: StateFlow<Boolean> get() = _online.asStateFlow()

    /** Why the last check of the whole list could not run at all, such as being offline; null once one runs. */
    val lastRunProblem: StateFlow<Problem?> get() = _lastRunProblem.asStateFlow()

    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _online.value = usable(capabilities)
        }

        override fun onLost(network: Network) {
            _online.value = false
        }
    }

    internal val checks = Checks(this)
    internal val installs = Installs(this)
    private val detector = Detector(this)
    private val interop = Interop(this)
    private val handoffs = Handoffs.on(this)
    private val stars = Stars(this)
    private val moves = Moves(this)
    private val sourceIcons = SourceIcons(this)

    private val orbotLink = OrbotLink(this.context, scope, orbotInstalled)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val gone = removedForGood(
                intent.action,
                replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
                archival = intent.getBooleanExtra(EXTRA_ARCHIVAL, false),
            )
            orbotLink.packageChanged(pkg)
            scope.launch(Dispatchers.IO) {
                checks.onPackageChanged(pkg)
                installs.onPackageChanged(pkg)
                if (gone) dropUninstalled(pkg)
            }
        }
    }

    private val startup = scope.launch(Dispatchers.IO) {
        notifier.ensureChannels()
        installers.start()
        interop.kept.start()
        Scheduler.apply(this@RealEngine.context, _settings.value)
        setObtainiumLinks(_settings.value.openObtainiumLinks)
        if (_settings.value.proxy == ProxyMode.ORBOT) orbotLink.ask()
        store.apps().forEach { stored[it.config.id] = it }
        _events.value = store.events()
        noteUnreadable()
        installs.reconcile()
        for (id in stored.keys) checks.reevaluate(id, network = false)
        publish()
        dropUninstalled()
    }

    init {
        current = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this.context, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        _online.value = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }?.let(::usable) ?: false
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    private fun usable(capabilities: NetworkCapabilities) =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    /** A check started offline fails at once: one problem for the run, not a timeout per app. */
    private suspend fun offline(appId: String?) = withContext(Dispatchers.IO) {
        val problem = Problem(ProblemKind.NETWORK, texts.offline())
        if (appId == null) {
            _lastRunProblem.value = problem
            event(null, EventKind.CHECK_FAILED, problem.message)
            return@withContext
        }
        saveState(appId) { it.copy(checkProblem = problem) } ?: return@withContext
        checks.reevaluate(appId, network = false)
        publish()
    }

    /** The proxy the user chose, as it is now. Throws when the setting names a proxy that cannot be built. */
    @Throws(IOException::class)
    fun proxy(): Proxy = ProxyChoice.of(_settings.value, orbotLink.port)

    override fun resumeInstall(appId: String): Boolean = installs.resume(appId)

    internal suspend fun ready() = startup.join()

    /**
     * One at a time. Rows are published from the check, the installer's answer and the package
     * broadcasts at once, and without a lock a snapshot taken earlier could be the one written last.
     */
    @Synchronized
    internal fun publish() {
        val rows = stored.values.map(::row)
        _apps.value = rows.sortedWith(
            compareBy<AppRow> { if (it.status == AppStatus.UPDATE_AVAILABLE || it.status == AppStatus.NEW_RELEASE) 0 else 1 }
                .thenBy { it.config.shownName.lowercase() }
                .thenBy { it.id },
        )
        _transfers.value = progress.filterKeys { it in userTransfers } + saves.all()
    }

    @Synchronized
    internal fun publishEvents() {
        _events.value = store.events()
    }

    private fun row(entry: StoredApp): AppRow {
        val id = entry.config.id
        val eval = evaluations[id] ?: Evaluation(AppStatus.UNKNOWN)
        val installed = packageOf(entry.config, eval)?.let { deviceApps[it] }?.app
        return AppRow(
            config = entry.config,
            installed = installed?.app,
            status = eval.status,
            statusCertain = eval.certain,
            latest = eval.latest,
            file = eval.file,
            otherFiles = eval.otherFiles,
            verification = eval.verification,
            progress = progress[id],
            problem = eval.problem,
            lastCheckedMs = entry.state.lastCheckedMs,
            silentUpdate = if (installed == null) null else installers.silent() ?: device.silentUpdateLikely(installed, eval.facts?.targetSdk),
            checking = id in checking,
            movedTo = Moves.suggestion(entry.state),
            addedAtMs = entry.state.addedAtMs,
            description = entry.state.description,
        )
    }

    internal fun packageOf(config: AppConfig, eval: Evaluation? = evaluations[config.id]): String? = config.packageName ?: eval?.facts?.packageName

    /** For asking a source where a file is now; what it learns is not kept. */
    internal fun sourceContext(): CheckContext = CheckContext(http, InMemoryValidatorStore(), tokens, nowMs, device.profile)

    /** For a check of [app], or of an address before there is an app when null; validators go to [validators]. */
    internal fun checkContext(app: AppConfig?, validators: ValidatorStore = InMemoryValidatorStore()): CheckContext =
        CheckContext(http, validators, tokens, nowMs, device.profile, app)

    /** Reads a file of an app of [spec] from the server, fetched from where the source says it is now. */
    internal fun inspectorFor(spec: SourceSpec): (Asset, String) -> FileFacts? = { asset, releaseId ->
        inspector.inspect(asset, releaseId) { registry.resolve(spec, asset, sourceContext()) }
    }

    /** Reads the app from PackageManager now; the cache is only for drawing rows. */
    internal fun readInstalled(packageName: String?): DeviceApp? {
        if (packageName == null) return null
        val app = device.read(packageName)
        deviceApps[packageName] = DeviceSlot(app)
        return app
    }

    private val saving = Any()

    /** One at a time, so what is drawn from memory is never older than what is on disk. */
    internal fun saveState(id: String, change: (AppState) -> AppState): StoredApp? = synchronized(saving) {
        store.updateState(id, change)?.also { stored[id] = it }
    }

    internal fun saveApp(id: String, change: (StoredApp) -> StoredApp): StoredApp? = synchronized(saving) {
        store.update(id, change)?.also { stored[id] = it }
    }

    /** An app that cannot be read is said once in the log, and kept: a newer Tern may read it. */
    private fun noteUnreadable() {
        val said = _events.value.mapTo(HashSet()) { it.message }
        for (where in store.unreadableApps()) {
            val message = texts.unreadableApp(where)
            if (message !in said) event(null, EventKind.CHECK_FAILED, message)
        }
    }

    internal fun event(appId: String?, kind: EventKind, message: String) {
        val name = appId?.let { stored[it]?.config?.shownName }
        store.addEvent(nowMs(), appId, name, kind, message)
        publishEvents()
    }

    internal fun setProgress(id: String, value: Progress?) {
        if (value == null) progress.remove(id) else progress[id] = value
        publish()
    }

    override suspend fun detect(input: String): Detection {
        ready()
        return detector.detect(input)
    }

    override suspend fun detect(input: String, reading: Reading): Detection {
        ready()
        return detector.detect(input, reading)
    }

    override suspend fun replaceSettings(found: Detection.Found): String {
        ready()
        val id = found.alreadyTracked?.takeIf { stored.containsKey(it) } ?: findBySpec(found.spec) ?: return add(found, install = false)
        found.carried?.let { interop.replace(id, it) }
        return id
    }

    override fun proposedConfig(found: Detection.Found): AppConfig {
        val s = _settings.value
        val given = found.carried ?: AppConfig(
            id = "",
            source = found.spec,
            name = "",
            // No wait of its own: a new app follows the setting for all apps, also when that changes.
            releases = ReleasePolicy(includePrereleases = s.includePrereleasesByDefault || found.release?.countsAsPrerelease == true),
            updates = s.defaultUpdateMode,
        )
        // An app Tern dropped when it was uninstalled elsewhere comes back held to what it was held to.
        val base = keptPins.restore(given.copy(source = found.spec))
        return validated(
            base.copy(
                id = idFor(found.spec),
                source = base.source,
                name = found.name.take(200).ifBlank { found.spec.url.take(200) },
                author = found.author?.take(200),
                packageName = found.packageName ?: found.verification?.packageName ?: base.packageName ?: found.installed?.packageName,
                pinnedSigners = builtIn.orElse(found.spec.url, base.pinnedSigners.ifEmpty { found.installed?.signers.orEmpty() }),
            ),
        )
    }

    override suspend fun add(found: Detection.Found, install: Boolean): String {
        ready()
        val existing = found.alreadyTracked ?: findBySpec(found.spec)
        if (existing != null) return existing
        val config = proposedConfig(found)
        withContext(Dispatchers.IO) {
            val state = AppState(
                releases = listOfNotNull(found.release),
                description = found.description?.take(1000),
                iconUrls = IconAddresses.accepted(found.spec.url, found.iconUrls),
                addedAtMs = nowMs(),
            )
            store.putApp(config, state)
            stored[config.id] = StoredApp(config, state)
            event(config.id, EventKind.ADDED, texts.eventAdded(found.spec.url))
        }
        checks.run(listOf(config.id), CheckCause.ADDED)
        if (install) install(config.id)
        return config.id
    }

    override suspend fun check(appId: String?, cause: CheckCause) {
        ready()
        if (!_online.value) {
            offline(appId)
            return
        }
        _lastRunProblem.value = null
        if (appId != null) {
            checks.run(listOf(appId), cause)
            return
        }
        val ids = stored.values.filter { inWholeListCheck(it.config) }.map { it.config.id }
        _checkCount.value = CheckCount(0, ids.size)
        _checkingAll.value = true
        try {
            checks.run(ids, cause) { _checkCount.update { it?.copy(done = it.done + 1) } }
        } finally {
            _checkingAll.value = false
            _checkCount.value = null
        }
    }

    /** Whether a check of the whole list looks at [config]; one asked for by itself always does. */
    internal fun inWholeListCheck(config: AppConfig): Boolean =
        !_settings.value.onlyCheckInstalled || config.trackOnly || readInstalled(packageOf(config)) != null

    override fun install(appId: String, releaseId: String?, assetUrl: String?) {
        installs.start(appId, releaseId, assetUrl, userStarted = true)
    }

    override fun installAllUpdates() {
        val rows = _apps.value.filter { it.status == AppStatus.UPDATE_AVAILABLE && !it.config.trackOnly && it.progress == null }
        // Tern's own update comes last, and waits for the others: once it is in, Android restarts Tern.
        for (row in rows.sortedBy { isSelf(it.config) }) install(row.id)
    }

    /** Whether [config] is Tern itself, which Android restarts once its update is installed. */
    internal fun isSelf(config: AppConfig): Boolean = packageOf(config) == context.packageName

    override fun cancel(appId: String) = installs.cancel(appId)

    /** Stops every download the person started that has not reached the installer yet, as the Cancel of its notification asks. */
    fun cancelDownloads() {
        for (id in userTransfers.toList()) {
            if (progress[id]?.phase in CANCELLABLE) cancel(id)
        }
        saves.cancelAll()
    }

    override suspend fun remove(appId: String) {
        ready()
        drop(appId, texts.eventRemoved(), keepPins = false)
    }

    /**
     * With the setting on, takes out of the list the apps that were seen installed and are not any
     * more, or only those of [packageName]. Nothing is taken while an install of it is under way.
     */
    internal suspend fun dropUninstalled(packageName: String? = null) {
        if (!_settings.value.removeUninstalled) return
        for (app in stored.values.toList()) {
            val pkg = packageOf(app.config) ?: continue
            if (packageName != null && pkg != packageName) continue
            if (!app.state.seenInstalled || app.state.pending != null || progress.containsKey(app.config.id)) continue
            if (readInstalled(pkg) == null && !device.archived(pkg)) drop(app.config.id, texts.eventRemovedUninstalled(), keepPins = true)
        }
    }

    /** [keepPins] keeps what the app was held to for when its source is added again; otherwise that is forgotten too. */
    private suspend fun drop(appId: String, why: String, keepPins: Boolean) {
        withContext(Dispatchers.IO) {
            val app = stored[appId] ?: return@withContext
            if (keepPins) keptPins.keep(app.config) else keptPins.forget(app.config.source)
            installs.cancel(appId)
            event(appId, EventKind.REMOVED, why)
            store.deleteApp(appId)
            store.removeValidators("${app.config.source.type}|${app.config.source.url}|")
            downloader.discardAll(appId)
            stored.remove(appId)
            evaluations.remove(appId)
            progress.remove(appId)
            publish()
        }
    }

    override fun uninstall(appId: String) {
        val pkg = stored[appId]?.config?.let { packageOf(it) } ?: return
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun open(appId: String): Boolean {
        val pkg = stored[appId]?.config?.let { packageOf(it) } ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override fun mayInstall(): Boolean = installers.mayInstall()

    /** Whether an install may start with nobody there to answer: never through another installer app. */
    internal fun mayInstallUnattended(): Boolean = installers.mayInstallUnattended()

    override val installerReadiness: StateFlow<InstallerReadiness> get() = installers.readiness

    override fun recheckInstaller() = installers.recheck()

    override fun askShizuku(): Boolean = installers.askShizuku()

    override fun askDhizuku(): Boolean = installers.askDhizuku()

    override fun installerChoices(): List<InstallerChoice> = installers.choices()

    override suspend fun installerIcon(choice: InstallerChoice, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val size = sizePx.coerceIn(1, 1024)
        OtherAppInstaller.icon(context, choice)?.toBitmap(size, size)
    }

    override suspend fun configure(appId: String, change: (AppConfig) -> AppConfig) {
        ready()
        withContext(Dispatchers.IO) {
            val previous = stored[appId]?.config
            val before = previous?.source
            val saved = saveApp(appId) {
                val config = validated(change(it.config).copy(id = appId))
                // Another package has not been seen installed yet, so it is not taken for one that was uninstalled.
                val state = if (config.packageName != it.config.packageName) it.state.copy(seenInstalled = false) else it.state
                it.copy(config = config, state = state)
            } ?: return@withContext
            checks.reevaluate(appId, network = false)
            publish()
            // Options of the source change what a page or a listing means, and so does what the app asks of its
            // source, so what the server said before is not reused.
            if (before != null && (before.options != saved.config.source.options || asksAnew(previous, saved.config))) {
                store.removeValidators("${before.type}|${before.url}|")
                if (_online.value) scope.launch { checks.run(listOf(appId), CheckCause.CHANGED) }
            }
        }
    }

    override suspend fun followMove(appId: String): Problem? {
        ready()
        return moves.follow(appId)
    }

    override suspend fun keepAddress(appId: String) {
        ready()
        moves.keep(appId)
    }

    override suspend fun dismissRelease(appId: String) {
        ready()
        withContext(Dispatchers.IO) {
            val offered = evaluations[appId]?.latest?.id ?: return@withContext
            saveApp(appId) { stored ->
                if (stored.config.trackOnly) {
                    stored.copy(state = stored.state.copy(seenReleaseId = offered))
                } else {
                    stored.copy(config = stored.config.copy(releases = stored.config.releases.copy(skippedReleaseId = offered)))
                }
            }
            checks.reevaluate(appId, network = false)
            publish()
        }
    }

    override suspend fun releases(appId: String): List<Release> {
        ready()
        return stored[appId]?.state?.releases.orEmpty()
    }

    override suspend fun notes(release: Release): List<NoteBlock> = withContext(Dispatchers.Default) { NotesMapper.map(release) }

    override suspend fun saveSettings(settings: Settings) {
        ready()
        withContext(Dispatchers.IO) {
            val before = _settings.value
            settingsStore.save(settings)
            val loaded = settingsStore.load()
            _settings.value = loaded
            Scheduler.apply(context, loaded)
            setObtainiumLinks(loaded.openObtainiumLinks)
            if (loaded.proxy == ProxyMode.ORBOT && before.proxy != ProxyMode.ORBOT) orbotLink.ask()
            if (loaded.installer != before.installer || loaded.otherInstaller != before.otherInstaller) installers.recheck()
            if (loaded.globalFileFilter != before.globalFileFilter || loaded.minAgeDaysByDefault != before.minAgeDaysByDefault) {
                for (id in stored.keys) checks.reevaluate(id, network = false)
                publish()
            }
            if (loaded.removeUninstalled && !before.removeUninstalled) scope.launch { dropUninstalled() }
            val keeping = loaded.autoExport && (!before.autoExport || loaded.exportFolder != before.exportFolder ||
                loaded.exportInstalledOnly != before.exportInstalledOnly || loaded.exportSettings != before.exportSettings)
            if (keeping) scope.launch { interop.kept.write() }
        }
    }

    override suspend fun setToken(host: String, token: String?) = withContext(Dispatchers.IO) { vault.put(host, token) }

    override suspend fun tokenHosts(): List<String> = withContext(Dispatchers.IO) { vault.hosts() }

    override suspend fun importFrom(uri: Uri): ImportSummary {
        ready()
        return interop.importFrom(uri)
    }

    override fun hasFilePicker(): Boolean = device.hasFilePicker()

    override suspend fun exportToFolder(format: ExportFormat): SavedFile {
        ready()
        return interop.exportToFolder(format)
    }

    override suspend fun importableFiles(): List<SavedFile> = interop.importableFiles()

    override suspend fun importFromFile(file: SavedFile): ImportSummary {
        ready()
        return interop.importFromFile(file)
    }

    override suspend fun importFromLink(url: String): ImportSummary {
        ready()
        return interop.importFromLink(url)
    }

    override suspend fun importReceived(file: Received.ExportFile): ImportSummary {
        ready()
        return interop.importBytes(file.bytes)
    }

    override suspend fun finishImport(offer: String, replace: Boolean, takeSettings: Boolean): ImportSummary {
        ready()
        return interop.finish(offer, replace, takeSettings)
    }

    override fun suggestions(): List<Suggestion> = Suggestions.list(device.profile.television, catalog, context::getString)

    override fun canOpenInstallSettings(): Boolean = device.canOpenInstallSettings()

    override val handoff: StateFlow<Handoff?> get() = handoffs.handoff

    override val handoffEnd: StateFlow<HandoffEnd?> get() = handoffs.handoffEnd

    override val orbot: StateFlow<OrbotState> get() = orbotLink.state

    override fun askOrbot() = orbotLink.ask()

    override fun openOrbot(): Boolean {
        val packages = context.packageManager
        val open = packages.getLaunchIntentForPackage(Orbot.PACKAGE) ?: packages.getLeanbackLaunchIntentForPackage(Orbot.PACKAGE) ?: return false
        try {
            context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            return false
        }
        orbotLink.ask()
        return true
    }

    override suspend fun openHandoff(): Problem? = handoffs.open()

    override fun closeHandoff() = handoffs.close()

    override fun takeReceived(): List<Received> = handoffs.take()

    override suspend fun starredBy(user: String): List<SearchHit> {
        ready()
        return stars.starredBy(user)
    }

    override suspend fun exportTo(uri: Uri, format: ExportFormat): Int {
        ready()
        return interop.exportTo(uri, format)
    }

    override suspend fun shareableExport(appIds: Collection<String>?, format: ExportFormat): Uri {
        ready()
        return interop.shareable(appIds, format)
    }

    override val exportStatus: StateFlow<ExportStatus?> get() = interop.kept.status

    override suspend fun takeExportFolder(folder: Uri) = interop.kept.choose(folder)

    override suspend fun runBackgroundCheck() = runScheduledCheck(cause = CheckCause.ASKED)

    override suspend fun saveFile(appId: String, releaseId: String, assetUrl: String): SavedFile {
        ready()
        // In the engine's own scope: a page that is left stops waiting, and the file is saved all the same.
        val saving = scope.async { interop.saveFile(appId, releaseId, assetUrl) }
        saves.track(saving)
        return saving.await()
    }

    override suspend fun fileSize(appId: String, releaseId: String, assetUrl: String): Long? {
        ready()
        return interop.fileSize(appId, releaseId, assetUrl)
    }

    override val searchOrigins: List<String> get() = detector.searchOrigins

    override fun renderNotes(text: String): List<NoteBlock> = NotesMapper.markdown(text)

    private val pages = ProjectPages(http, tokens)

    override fun hasProjectPage(row: AppRow): Boolean = row.config.source.type in PROJECT_PAGE_SOURCES

    override suspend fun projectPage(appId: String): List<NoteBlock> {
        ready()
        val spec = stored[appId]?.config?.source ?: return emptyList()
        return try {
            runInterruptible(Dispatchers.IO) { pages.read(spec) }.orEmpty()
        } catch (e: IOException) {
            throw ProblemException(Problem(ProblemKind.NETWORK, texts.checkNetwork(e.message)))
        }
    }

    override fun canDowngrade(): Boolean = device.read(LET_ME_DOWNGRADE) != null

    override suspend fun writeKeptExport() {
        ready()
        interop.kept.write()
    }

    override suspend fun clearEvents() = withContext(Dispatchers.IO) {
        store.clearEvents()
        publishEvents()
    }

    override suspend fun icon(found: Detection.Found, sizePx: Int): Bitmap? {
        if (found.installed != null) {
            return withContext(Dispatchers.IO) {
                try {
                    context.packageManager.getApplicationIcon(found.installed.packageName).toBitmap(sizePx.coerceIn(1, 1024), sizePx.coerceIn(1, 1024))
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }
        return sourceIcons.forAddresses(found.iconUrls, sizePx.coerceIn(1, 1024))
    }

    override suspend fun icon(row: AppRow, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val size = sizePx.coerceIn(1, 1024)
        val pm = context.packageManager
        val pkg = row.installed?.packageName
        val release = row.latest?.id
        val kept = if (pkg == null && release != null) row.file?.asset?.url?.let { downloader.kept(row.id, Downloader.key(release, it)) } else null
        val drawable = if (pkg != null) {
            try {
                pm.getApplicationIcon(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        } else {
            kept?.let { file ->
                pm.getPackageArchiveInfo(file.path, 0)?.applicationInfo?.let { info ->
                    info.sourceDir = file.path
                    info.publicSourceDir = file.path
                    info.loadIcon(pm)
                }
            }
        }
        if (pkg == null && kept == null) sourceIcons.forRow(row, size) else drawable?.toBitmap(size, size)
    }

    /** The background check: every app not set to manual, then automatic installs where Android allows them. */
    /**
     * The background check: of [only] or of every app it looks at. Updates that install by
     * themselves wait while the settings hold them back, and a job installs them once the network
     * and the charger allow it. Apps that could not be checked for a passing reason are checked
     * again a few times, each time later, and a rate limit is waited out, as in Obtainium.
     * [cause] is what Tern's own messages in the log say started it.
     */
    suspend fun runScheduledCheck(attempt: Int = 0, only: Set<String>? = null, cause: CheckCause = if (only == null) CheckCause.SCHEDULE else CheckCause.RETRY) {
        ready()
        if (!_online.value) {
            offline(null)
            return
        }
        _lastRunProblem.value = null
        val settings = _settings.value
        val installsNow = Scheduler.installsNow(settings, waitingJob = false, device::onUnmeteredNetwork, device::isCharging)
        val run = installs.runScheduled(settings, installsNow, only, cause)
        if (Scheduler.armsWaiting(settings, waitingJob = false, waited = run.waited)) Scheduler.waitForInstalls(context, settings)
        retryDelay(run.failed, attempt, nowMs())?.let { (again, delay) -> Scheduler.retry(context, again, attempt + 1, delay) }
    }

    /** The waiting job: installs what the last check held back, without checking the list again or setting itself anew. */
    suspend fun runWaitingInstalls() {
        ready()
        if (!_online.value) {
            offline(null)
            return
        }
        val settings = _settings.value
        if (!Scheduler.installsNow(settings, waitingJob = true, device::onUnmeteredNetwork, device::isCharging)) return
        installs.runScheduled(settings, installsNow = true, check = false)
    }

    /** Which of [failed] to check again and how long to wait first, or null for none. */
    internal fun retryDelay(failed: List<String>, attempt: Int, now: Long): Pair<List<String>, Long>? =
        Retries.plan(failed.mapNotNull { id -> stored[id]?.state?.checkProblem?.let { id to it } }, attempt, now)

    /** Every package the user follows in one repository, so a single index download serves them all. */
    private fun trackedInRepository(repositoryUrl: String): Set<String> = stored.values.asSequence()
        .map { it.config.source }
        .filter { it.type == SourceTypes.FDROID_REPO && it.url.equals(repositoryUrl, ignoreCase = true) }
        .mapNotNull { it.option(SourceOptions.PACKAGE) }
        .toSet()

    internal fun findBySpec(spec: SourceSpec): String? =
        stored.values.firstOrNull { sameSource(it.config.source, spec) }?.config?.id

    internal fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** Stable per source, so the id shown before adding is the id that gets stored, and unlike any other tracked app's. */
    private fun idFor(spec: SourceSpec): String {
        // The apps of one repository share its address and are told apart by their package.
        val pkg = spec.option(SourceOptions.PACKAGE)?.takeIf { spec.type == SourceTypes.FDROID_REPO }?.let { "|$it" }.orEmpty()
        val base = Fingerprints.sha256("${spec.type}|${spec.url.lowercase()}$pkg".toByteArray())
        var length = 16
        while (length < base.length) {
            val id = base.take(length)
            val holder = stored[id]?.config?.source
            if (holder == null || sameSource(holder, spec)) return id
            length += 4
        }
        return base
    }

    /**
     * Whether [after] asks something else of its source than [before]: a forge lists tags for an
     * app that is only tracked, and a web page is read with the app's version pattern.
     */
    private fun asksAnew(before: AppConfig?, after: AppConfig): Boolean {
        if (before == null) return false
        if (before.trackOnly != after.trackOnly) return true
        return after.source.type in SourceTypes.READS_OWN_VERSIONS &&
            (before.releases.versionExtract != after.releases.versionExtract || before.releases.matchGroup != after.releases.matchGroup)
    }

    internal fun validated(config: AppConfig): AppConfig = try {
        AppConfigJson.decode(Json.parseObject(Json.write(AppConfigJson.encode(config))))
    } catch (e: AppConfigJsonException) {
        throw IllegalArgumentException(e.message, e)
    }

    private fun setObtainiumLinks(enabled: Boolean) {
        val component = android.content.ComponentName(context, OBTAINIUM_ALIAS)
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (e: IllegalArgumentException) {
            TernLog.w(TAG, "Could not switch Obtainium links: ${e.message}")
        }
    }

    override fun close() {
        if (TernLog.journal === journal) TernLog.journal = null
        scope.cancel()
        handoffs.shutDown()
        orbotLink.close()
        installers.stop()
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            TernLog.w(TAG, "Network callback was not registered: ${e.message}")
        }
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            TernLog.w(TAG, "Package receiver was not registered: ${e.message}")
        }
        store.close()
        if (current === this) current = null
    }

    /** One PackageManager reading; [app] is null when the package is not installed. */
    data class DeviceSlot(val app: DeviceApp?)

    companion object {
        private const val TAG = "TernEngine"
        private const val OBTAINIUM_ALIAS = "io.github.munzzyy.tern.ObtainiumLinks"

        /** The module that lets Android put an older version of an app over a newer one. */
        internal const val LET_ME_DOWNGRADE = "com.berdik.letmedowngrade"

        /** Where a download stands until its file is handed to the installer. */
        private val CANCELLABLE = setOf(Phase.QUEUED, Phase.DOWNLOADING, Phase.VERIFYING)

        /** The sources whose projects keep a README Tern reads for an app's page. */
        private val PROJECT_PAGE_SOURCES = setOf(SourceTypes.GITHUB, SourceTypes.GITHUB_ACTIONS, SourceTypes.GITLAB, SourceTypes.FORGEJO)

        /** Intent.EXTRA_ARCHIVAL, which Android 15 sets on the removal that archives an app. */
        private const val EXTRA_ARCHIVAL = "android.intent.extra.ARCHIVAL"

        /**
         * Whether a package broadcast means the app is gone: removed, and neither being replaced
         * nor archived. An archived app keeps its data and comes back with one tap.
         */
        internal fun removedForGood(action: String?, replacing: Boolean, archival: Boolean): Boolean =
            action == Intent.ACTION_PACKAGE_REMOVED && !replacing && !archival

        /** Whether [a] and [b] are one app's source: one kind at one address, and in a repository of many apps, one package. */
        internal fun sameSource(a: SourceSpec, b: SourceSpec): Boolean =
            a.type == b.type && a.url.equals(b.url, ignoreCase = true) &&
                (a.type != SourceTypes.FDROID_REPO || a.option(SourceOptions.PACKAGE) == b.option(SourceOptions.PACKAGE))

        @Volatile
        internal var current: RealEngine? = null
            private set

        private var shared: RealEngine? = null

        /** The engine this process runs, for components the system starts on its own. */
        fun obtain(context: Context): RealEngine = current ?: shared(context)

        @Synchronized
        fun shared(context: Context): RealEngine {
            shared?.let { return it }
            val door = ProxyDoor()
            val pins = PinChoice()
            val engine = RealEngine(context, UrlConnectionHttp(proxy = door::proxy, proxyAnswers = ProxyProbe::answers, pinning = pins::on))
            door.follow(engine::proxy)
            pins.follow { engine.settings.value.pinCertificates }
            shared = engine
            return engine
        }
    }
}
