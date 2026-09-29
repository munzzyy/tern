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
import android.util.Log
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
import io.github.munzzyy.tern.core.model.SourceSpec
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
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.Handoff
import io.github.munzzyy.tern.engine.HandoffEnd
import io.github.munzzyy.tern.engine.OrbotState
import io.github.munzzyy.tern.engine.ImportSummary
import io.github.munzzyy.tern.engine.NoteBlock
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.ProxyMode
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
import io.github.munzzyy.tern.install.PackageManagerArchiveReader
import io.github.munzzyy.tern.install.SessionInstaller
import io.github.munzzyy.tern.net.Orbot
import io.github.munzzyy.tern.net.ProxyChoice
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val vault = TokenVault(this.context, prefsPrefix + TokenVault.DEFAULT_NAME)
    internal val texts = Texts(this.context)
    internal val device = Device(this.context)
    internal val tokens = TokenProvider { host -> vault.tokenFor(host) }
    internal val http: HttpClient = PoliteHttp(transport, RateLimiter(nowMs), "Tern/${BuildConfig.VERSION_NAME}")
    internal val registry = SourceRegistry.standard(::trackedInRepository)
    internal val inspector = FileInspector(http, store, tokens, device.sdk)
    internal val builtIn = BuiltInPins(catalog)
    internal val evaluator = Evaluator(texts, device.profile, builtIn, nowMs)
    internal val downloader = Downloader(http, downloadsDir ?: File(this.context.filesDir, "downloads"), texts)
    internal val gate: Gate = gate ?: InstallGate(archiveReader ?: PackageManagerArchiveReader(this.context.packageManager), texts)
    internal val installer: Installer = installer ?: SessionInstaller(this.context)
    internal val notifier = Notifier(this.context, texts) { _settings.value.notifyNames }
    internal val staging = File(this.context.cacheDir, "staging")

    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Engine task failed", e) },
    )

    internal val stored = ConcurrentHashMap<String, StoredApp>()
    internal val evaluations = ConcurrentHashMap<String, Evaluation>()
    internal val progress = ConcurrentHashMap<String, Progress>()
    internal val checking: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val deviceApps = ConcurrentHashMap<String, DeviceSlot>()
    internal val userTransfers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _apps = MutableStateFlow<List<AppRow>>(emptyList())
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    private val _settings = MutableStateFlow(settingsStore.load())
    private val _checkingAll = MutableStateFlow(false)
    private val _transfers = MutableStateFlow<Map<String, Progress>>(emptyMap())

    override val apps get() = _apps.asStateFlow()
    override val events: StateFlow<List<Event>> get() = _events.asStateFlow()
    override val settings: StateFlow<Settings> get() = _settings.asStateFlow()
    override val checkingAll: StateFlow<Boolean> get() = _checkingAll.asStateFlow()

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
            orbotLink.packageChanged(pkg)
            scope.launch(Dispatchers.IO) { checks.onPackageChanged(pkg) }
        }
    }

    private val startup = scope.launch(Dispatchers.IO) {
        notifier.ensureChannels()
        Scheduler.apply(this@RealEngine.context, _settings.value)
        setObtainiumLinks(_settings.value.openObtainiumLinks)
        if (_settings.value.proxy == ProxyMode.ORBOT) orbotLink.ask()
        store.apps().forEach { stored[it.config.id] = it }
        _events.value = store.events()
        installs.reconcile()
        for (id in stored.keys) checks.reevaluate(id, network = false)
        publish()
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
                .thenBy { it.config.name.lowercase() }
                .thenBy { it.id },
        )
        _transfers.value = progress.filterKeys { it in userTransfers }
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
            silentUpdate = if (installed == null) null else device.silentUpdateLikely(installed, eval.facts?.targetSdk),
            checking = id in checking,
            movedTo = Moves.suggestion(entry.state),
        )
    }

    internal fun packageOf(config: AppConfig, eval: Evaluation? = evaluations[config.id]): String? = config.packageName ?: eval?.facts?.packageName

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

    internal fun event(appId: String?, kind: EventKind, message: String) {
        val name = appId?.let { stored[it]?.config?.name }
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

    override fun proposedConfig(found: Detection.Found): AppConfig {
        val s = _settings.value
        val base = found.carried ?: AppConfig(
            id = "",
            source = found.spec,
            name = "",
            releases = ReleasePolicy(
                includePrereleases = s.includePrereleasesByDefault || found.release?.countsAsPrerelease == true,
                minAgeDays = s.minAgeDaysByDefault,
            ),
            updates = s.defaultUpdateMode,
        )
        return validated(
            base.copy(
                id = idFor(found.spec),
                source = found.spec,
                name = found.name.take(200).ifBlank { found.spec.url.take(200) },
                author = found.author?.take(200),
                packageName = found.verification?.packageName ?: base.packageName ?: found.installed?.packageName,
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
            )
            store.putApp(config, state)
            stored[config.id] = StoredApp(config, state)
            event(config.id, EventKind.ADDED, texts.eventAdded(found.spec.url))
        }
        checks.checkOne(config.id)
        if (install) install(config.id)
        return config.id
    }

    override suspend fun check(appId: String?) {
        ready()
        if (!_online.value) {
            offline(appId)
            return
        }
        _lastRunProblem.value = null
        if (appId != null) {
            checks.checkOne(appId)
            return
        }
        _checkingAll.value = true
        try {
            checks.checkMany(stored.keys.toList())
        } finally {
            _checkingAll.value = false
        }
    }

    override fun install(appId: String, releaseId: String?, assetUrl: String?) {
        installs.start(appId, releaseId, assetUrl, userStarted = true)
    }

    override fun installAllUpdates() {
        for (row in _apps.value) {
            if (row.status == AppStatus.UPDATE_AVAILABLE && !row.config.trackOnly && row.progress == null) install(row.id)
        }
    }

    override fun cancel(appId: String) = installs.cancel(appId)

    override suspend fun remove(appId: String) {
        ready()
        withContext(Dispatchers.IO) {
            val app = stored[appId] ?: return@withContext
            installs.cancel(appId)
            event(appId, EventKind.REMOVED, texts.eventRemoved())
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

    override fun mayInstall(): Boolean = device.mayInstall()

    override suspend fun configure(appId: String, change: (AppConfig) -> AppConfig) {
        ready()
        withContext(Dispatchers.IO) {
            saveApp(appId) { it.copy(config = validated(change(it.config).copy(id = appId))) } ?: return@withContext
            checks.reevaluate(appId, network = false)
            publish()
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
        }
    }

    override suspend fun setToken(host: String, token: String?) = withContext(Dispatchers.IO) { vault.put(host, token) }

    override suspend fun tokenHosts(): List<String> = withContext(Dispatchers.IO) { vault.hosts() }

    override suspend fun importFrom(uri: Uri): ImportSummary {
        ready()
        return interop.importFrom(uri)
    }

    override fun hasFilePicker(): Boolean = device.hasFilePicker()

    override suspend fun exportToFolder(): SavedFile {
        ready()
        return interop.exportToFolder()
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

    override suspend fun exportTo(uri: Uri): Int {
        ready()
        return interop.exportTo(uri)
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
        val kept = if (pkg == null) row.file?.asset?.url?.let { downloader.kept(row.id, it) } else null
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
    suspend fun runScheduledCheck() {
        ready()
        if (!_online.value) {
            offline(null)
            return
        }
        _lastRunProblem.value = null
        installs.runScheduled(_settings.value)
    }

    /** Every package the user follows in one repository, so a single index download serves them all. */
    private fun trackedInRepository(repositoryUrl: String): Set<String> = stored.values.asSequence()
        .map { it.config.source }
        .filter { it.type == SourceTypes.FDROID_REPO && it.url.equals(repositoryUrl, ignoreCase = true) }
        .mapNotNull { it.option(SourceOptions.PACKAGE) }
        .toSet()

    internal fun findBySpec(spec: SourceSpec): String? =
        stored.values.firstOrNull { it.config.source.type == spec.type && it.config.source.url.equals(spec.url, ignoreCase = true) }?.config?.id

    internal fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** Stable per source, so the id shown before adding is the id that gets stored, and unlike any other tracked app's. */
    private fun idFor(spec: SourceSpec): String {
        val base = Fingerprints.sha256("${spec.type}|${spec.url.lowercase()}".toByteArray())
        var length = 16
        while (length < base.length) {
            val id = base.take(length)
            val holder = stored[id]?.config?.source
            if (holder == null || (holder.type == spec.type && holder.url.equals(spec.url, ignoreCase = true))) return id
            length += 4
        }
        return base
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
            Log.w(TAG, "Could not switch Obtainium links: ${e.message}")
        }
    }

    override fun close() {
        scope.cancel()
        handoffs.shutDown()
        orbotLink.close()
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered: ${e.message}")
        }
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Package receiver was not registered: ${e.message}")
        }
        store.close()
        if (current === this) current = null
    }

    /** One PackageManager reading; [app] is null when the package is not installed. */
    data class DeviceSlot(val app: DeviceApp?)

    companion object {
        private const val TAG = "TernEngine"
        private const val OBTAINIUM_ALIAS = "io.github.munzzyy.tern.ObtainiumLinks"

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
            val engine = RealEngine(context, UrlConnectionHttp(proxy = door::proxy, proxyAnswers = ProxyProbe::answers))
            door.follow(engine::proxy)
            shared = engine
            return engine
        }
    }
}
