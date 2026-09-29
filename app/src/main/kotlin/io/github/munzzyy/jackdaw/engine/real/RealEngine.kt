package io.github.munzzyy.jackdaw.engine.real

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
import io.github.munzzyy.jackdaw.BuildConfig
import io.github.munzzyy.jackdaw.core.interop.AppConfigJson
import io.github.munzzyy.jackdaw.core.interop.AppConfigJsonException
import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.ReleasePolicy
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.verify.Fingerprints
import io.github.munzzyy.jackdaw.core.net.HttpClient
import io.github.munzzyy.jackdaw.core.net.PoliteHttp
import io.github.munzzyy.jackdaw.core.net.RateLimiter
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceRegistry
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.source.TokenProvider
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidSource
import io.github.munzzyy.jackdaw.core.source.forge.ForgejoSource
import io.github.munzzyy.jackdaw.core.source.forge.GitHubActionsSource
import io.github.munzzyy.jackdaw.core.source.forge.GitHubSource
import io.github.munzzyy.jackdaw.core.source.forge.GitLabSource
import io.github.munzzyy.jackdaw.core.source.web.DirectSource
import io.github.munzzyy.jackdaw.core.source.web.HtmlSource
import io.github.munzzyy.jackdaw.core.source.web.JenkinsSource
import io.github.munzzyy.jackdaw.core.source.web.SourceForgeSource
import io.github.munzzyy.jackdaw.core.source.web.SourceHutSource
import io.github.munzzyy.jackdaw.data.AppState
import io.github.munzzyy.jackdaw.data.SettingsStore
import io.github.munzzyy.jackdaw.data.Store
import io.github.munzzyy.jackdaw.data.StoredApp
import io.github.munzzyy.jackdaw.data.TokenVault
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.engine.Event
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.engine.ImportSummary
import io.github.munzzyy.jackdaw.engine.NoteBlock
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.engine.Progress
import io.github.munzzyy.jackdaw.engine.Settings
import io.github.munzzyy.jackdaw.install.ArchiveReader
import io.github.munzzyy.jackdaw.install.Downloader
import io.github.munzzyy.jackdaw.install.Gate
import io.github.munzzyy.jackdaw.install.InstallGate
import io.github.munzzyy.jackdaw.install.Installer
import io.github.munzzyy.jackdaw.install.PackageManagerArchiveReader
import io.github.munzzyy.jackdaw.install.SessionInstaller
import io.github.munzzyy.jackdaw.net.ProxyChoice
import io.github.munzzyy.jackdaw.net.UrlConnectionHttp
import io.github.munzzyy.jackdaw.work.Notifier
import io.github.munzzyy.jackdaw.work.Scheduler
import java.io.Closeable
import java.io.File
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
 * stored; what is stored is the listing, the record of what Jackdaw itself installed, and problems.
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
) : Engine, Closeable {
    internal val context: Context = context.applicationContext
    internal val store = Store(this.context, storeName)
    private val settingsStore = SettingsStore(this.context, prefsPrefix + SettingsStore.DEFAULT_NAME)
    private val vault = TokenVault(this.context, prefsPrefix + TokenVault.DEFAULT_NAME)
    internal val texts = Texts(this.context)
    internal val device = Device(this.context)
    internal val tokens = TokenProvider { host -> vault.tokenFor(host) }
    internal val http: HttpClient = PoliteHttp(transport, RateLimiter(nowMs), "Jackdaw/${BuildConfig.VERSION_NAME}")
    internal val registry = SourceRegistry(
        listOf(
            GitHubSource(), GitHubActionsSource(), GitLabSource(), ForgejoSource(), FDroidSource(), FDroidRepoSource(::trackedInRepository),
            SourceForgeSource(), SourceHutSource(), JenkinsSource(), DirectSource(), HtmlSource(),
        ),
    )
    internal val inspector = FileInspector(http, store, tokens, device.sdk)
    internal val evaluator = Evaluator(texts, device.profile, nowMs)
    internal val downloader = Downloader(http, downloadsDir ?: File(this.context.filesDir, "downloads"), texts)
    internal val gate: Gate = gate ?: InstallGate(archiveReader ?: PackageManagerArchiveReader(this.context.packageManager), texts)
    internal val installer: Installer = installer ?: SessionInstaller(this.context)
    internal val notifier = Notifier(this.context, texts)
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

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            scope.launch(Dispatchers.IO) { checks.onPackageChanged(pkg) }
        }
    }

    private val startup = scope.launch(Dispatchers.IO) {
        notifier.ensureChannels()
        Scheduler.apply(this@RealEngine.context, _settings.value)
        setObtainiumLinks(_settings.value.openObtainiumLinks)
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

    override fun resumeInstall(appId: String): Boolean = installs.resume(appId)

    internal suspend fun ready() = startup.join()

    internal fun publish() {
        val rows = stored.values.map(::row)
        _apps.value = rows.sortedWith(
            compareBy<AppRow> { if (it.status == AppStatus.UPDATE_AVAILABLE || it.status == AppStatus.NEW_RELEASE) 0 else 1 }
                .thenBy { it.config.name.lowercase() }
                .thenBy { it.id },
        )
        _transfers.value = progress.filterKeys { it in userTransfers }
    }

    internal fun publishEvents() {
        _events.value = store.events()
    }

    private fun row(entry: StoredApp): AppRow {
        val id = entry.config.id
        val eval = evaluations[id] ?: Evaluation(AppStatus.UNKNOWN)
        val installed = deviceApps[packageOf(entry.config, eval)]?.app
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
            movedTo = entry.state.movedTo,
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

    internal fun saveState(id: String, change: (AppState) -> AppState): StoredApp? =
        store.updateState(id, change)?.also { stored[id] = it }

    internal fun saveApp(id: String, change: (StoredApp) -> StoredApp): StoredApp? =
        store.update(id, change)?.also { stored[id] = it }

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
                includePrereleases = s.includePrereleasesByDefault || found.release?.prerelease == true,
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
                pinnedSigners = base.pinnedSigners.ifEmpty { found.installed?.signers.orEmpty() },
            ),
        )
    }

    override suspend fun add(found: Detection.Found, install: Boolean): String {
        ready()
        val existing = found.alreadyTracked ?: findBySpec(found.spec)
        if (existing != null) return existing
        val config = proposedConfig(found)
        withContext(Dispatchers.IO) {
            val state = AppState(releases = listOfNotNull(found.release), description = found.description?.take(1000))
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

    override suspend fun save(config: AppConfig) {
        ready()
        val checked = validated(config)
        withContext(Dispatchers.IO) {
            saveApp(checked.id) { it.copy(config = checked) } ?: return@withContext
            checks.reevaluate(checked.id, network = false)
            publish()
        }
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
            settingsStore.save(settings)
            val loaded = settingsStore.load()
            _settings.value = loaded
            Scheduler.apply(context, loaded)
            setObtainiumLinks(loaded.openObtainiumLinks)
        }
    }

    override suspend fun setToken(host: String, token: String?) = withContext(Dispatchers.IO) { vault.put(host, token) }

    override suspend fun tokenHosts(): List<String> = withContext(Dispatchers.IO) { vault.hosts() }

    override suspend fun importFrom(uri: Uri): ImportSummary {
        ready()
        return interop.importFrom(uri)
    }

    override suspend fun exportTo(uri: Uri): Int {
        ready()
        return interop.exportTo(uri)
    }

    override suspend fun clearEvents() = withContext(Dispatchers.IO) {
        store.clearEvents()
        publishEvents()
    }

    override suspend fun icon(row: AppRow, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val size = sizePx.coerceIn(1, 1024)
        val pm = context.packageManager
        val pkg = row.installed?.packageName
        val drawable = if (pkg != null) {
            try {
                pm.getApplicationIcon(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        } else {
            row.file?.asset?.url?.let { downloader.kept(row.id, it) }?.let { file ->
                pm.getPackageArchiveInfo(file.path, 0)?.applicationInfo?.let { info ->
                    info.sourceDir = file.path
                    info.publicSourceDir = file.path
                    info.loadIcon(pm)
                }
            }
        }
        drawable?.toBitmap(size, size)
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
        private const val TAG = "JackdawEngine"
        private const val OBTAINIUM_ALIAS = "io.github.munzzyy.jackdaw.ObtainiumLinks"

        @Volatile
        internal var current: RealEngine? = null
            private set

        private var shared: RealEngine? = null

        /** The engine this process runs, for components the system starts on its own. */
        fun obtain(context: Context): RealEngine = current ?: shared(context)

        @Synchronized
        fun shared(context: Context): RealEngine {
            shared?.let { return it }
            var engine: RealEngine? = null
            val http = UrlConnectionHttp(proxy = { ProxyChoice.of(engine?.settings?.value ?: Settings()) })
            engine = RealEngine(context, http)
            shared = engine
            return engine
        }
    }
}
