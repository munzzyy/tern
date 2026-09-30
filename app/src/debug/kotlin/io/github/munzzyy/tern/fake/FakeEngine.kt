package io.github.munzzyy.tern.fake

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.ChecksumState
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.engine.ExportStatus
import io.github.munzzyy.tern.engine.ImportSummary
import io.github.munzzyy.tern.engine.InstallerChoice
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.InstallerReadiness
import io.github.munzzyy.tern.engine.Suggestion
import io.github.munzzyy.tern.engine.SavedFile
import io.github.munzzyy.tern.engine.Received
import io.github.munzzyy.tern.engine.Handoff
import io.github.munzzyy.tern.engine.HandoffEnd
import io.github.munzzyy.tern.engine.OrbotState
import io.github.munzzyy.tern.engine.NoteBlock
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.Scenarios
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** In-memory stand-in for the real engine: invented apps in every state the contract has. */
class FakeEngine(private val context: Context) : Engine, Scenarios {
    /** tools/check-apk.sh looks for this text in a release build; class names do not survive R8. */
    val marker: String = "tern-stand-in-engine"

    /** Naming the scope after [marker] keeps the text alive: R8 drops a string nothing reads. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(marker))
    private val jobs = ConcurrentHashMap<String, Job>()
    private var ticker: Job? = null
    private val eventIds = AtomicLong(10_000)
    private val tokens = MutableStateFlow(setOf<String>())

    companion object {
        /** A device that has no file picker, no settings page for installs, and has not allowed installs yet. */
        const val BARE = "bare"

        /** "end-expired" ends the open handoff that way and leaves everything else as it is, for a look at the screen by hand. */
        const val END_PREFIX = "end-"
    }

    /** Tests shorten this to make installs finish quickly. */
    @Volatile var stepMs = 120L

    @Volatile var detectDelayMs = 900L

    private val _apps = MutableStateFlow<List<AppRow>>(emptyList())
    override val apps: StateFlow<List<AppRow>> = _apps.asStateFlow()

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    override val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _checkingAll = MutableStateFlow(false)
    override val checkingAll: StateFlow<Boolean> = _checkingAll.asStateFlow()

    private val _online = MutableStateFlow(true)
    private val _handoff = MutableStateFlow<Handoff?>(null)
    override val handoff: StateFlow<Handoff?> = _handoff.asStateFlow()
    private val _handoffEnd = MutableStateFlow<HandoffEnd?>(null)
    override val handoffEnd: StateFlow<HandoffEnd?> = _handoffEnd.asStateFlow()
    private val _orbot = MutableStateFlow(OrbotState.UNKNOWN)
    override val orbot: StateFlow<OrbotState> = _orbot.asStateFlow()

    /** What Orbot answers when it is asked; tests set it. */
    @Volatile var orbotAnswer = OrbotState.ON

    /** How often Orbot was asked since the scenario was loaded. */
    @Volatile var orbotAsked = 0

    /** How often Orbot was opened since the scenario was loaded. */
    @Volatile var orbotOpened = 0
    private val received = java.util.concurrent.CopyOnWriteArrayList<Received>()
    private var handoffJob: Job? = null
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Tests set this to play an install whose confirmation Android has already dropped. */
    @Volatile var nothingWaits = false

    init {
        loadScenario("default")
    }

    override fun loadScenario(name: String) {
        if (name.startsWith(END_PREFIX)) {
            HandoffEnd.entries.firstOrNull { it.name.equals(name.removePrefix(END_PREFIX), ignoreCase = true) }?.let(::endHandoff)
            return
        }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        ticker?.cancel()
        val invent = Invent(System.currentTimeMillis())
        val (rows, events) = when (name) {
            "empty", "firstrun" -> emptyList<AppRow>() to emptyList()
            "many" -> invent.manyRows(300) to invent.events()
            "three" -> invent.manyRows(3) to invent.events()
            "thirty" -> invent.manyRows(30) to invent.events()
            "offline" -> invent.offlineRows() to invent.events()
            "errors" -> invent.errorRows() to invent.events().filter { it.kind in setOf(EventKind.BLOCKED, EventKind.FAILED, EventKind.CHECK_FAILED) }
            BARE -> invent.defaultRows() to invent.events()
            else -> invent.defaultRows() to invent.events()
        }
        _apps.value = ordered(rows)
        _events.value = events
        _checkingAll.value = false
        _settings.value = Settings()
        _online.value = name != "offline"
        nothingWaits = false
        installsAllowed = name != BARE
        filePicker = name != BARE
        installSettings = name != BARE
        localNetwork = true
        savedFiles = true
        orbotAnswer = OrbotState.ON
        orbotAsked = 0
        orbotOpened = 0
        _orbot.value = OrbotState.UNKNOWN
        closeHandoff()
        tokens.value = if (rows.isEmpty()) emptySet() else setOf("api.github.com")
        if (name == "default") startTicker()
    }

    /** Keeps one download visibly moving so progress can be seen and screenshotted. */
    private fun startTicker() {
        ticker = scope.launch {
            while (isActive) {
                delay(250)
                edit("cinderplayer") { row ->
                    val p = row.progress ?: return@edit row
                    if (p.phase != Phase.DOWNLOADING || jobs.containsKey(row.id)) return@edit row
                    val total = p.bytesTotal ?: return@edit row
                    row.copy(progress = p.copy(bytesDone = (p.bytesDone + 350_000) % total))
                }
            }
        }
    }

    private fun ordered(rows: List<AppRow>): List<AppRow> {
        val collator = Collator.getInstance()
        val update = setOf(AppStatus.UPDATE_AVAILABLE, AppStatus.NEW_RELEASE)
        return rows.sortedWith(compareBy<AppRow> { it.status !in update }.thenBy(collator) { it.config.name })
    }

    private fun edit(id: String, change: (AppRow) -> AppRow) {
        _apps.update { rows -> ordered(rows.map { if (it.id == id) change(it) else it }) }
    }

    private fun row(id: String): AppRow? = _apps.value.firstOrNull { it.id == id }

    /** Tests call it to put an app into the phase they need, or out of every phase with null. */
    fun play(appId: String, progress: Progress?) = edit(appId) { it.copy(progress = progress) }

    private fun log(row: AppRow?, kind: EventKind, message: String) {
        val e = Event(eventIds.incrementAndGet(), System.currentTimeMillis(), row?.id, row?.config?.name, kind, message)
        _events.update { (listOf(e) + it).take(500) }
    }

    override suspend fun detect(input: String): Detection {
        delay(detectDelayMs)
        return fakeDetect(input, Invent(System.currentTimeMillis()))
    }

    override fun proposedConfig(found: Detection.Found): AppConfig {
        val base = found.spec.url.trimEnd('/').substringAfterLast('/').lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" }
        var id = base
        var n = 2
        while (row(id) != null) id = base + n++
        val carried = found.carried
        return (carried ?: AppConfig(id = id, source = found.spec, name = found.name)).copy(
            id = id,
            source = found.spec,
            name = carried?.name ?: found.name,
            author = carried?.author ?: found.author,
            packageName = found.verification?.packageName ?: carried?.packageName,
        )
    }

    override fun resumeInstall(appId: String): Boolean {
        val waiting = row(appId)?.progress?.phase == Phase.WAITING_FOR_USER
        if (!waiting || nothingWaits || jobs.containsKey(appId)) return false
        jobs[appId] = scope.launch {
            try {
                edit(appId) { it.copy(progress = Progress(Phase.INSTALLING)) }
                delay(stepMs * 5)
                finishInstall(appId, null)
            } finally {
                jobs.remove(appId)
            }
        }
        return true
    }

    override suspend fun add(found: Detection.Found, install: Boolean): String {
        found.alreadyTracked?.let { return it }
        delay(stepMs)
        val config = proposedConfig(found)
        val id = config.id
        val newRow = AppRow(
            config = config,
            installed = found.installed,
            status = if (found.file != null) AppStatus.NOT_INSTALLED else AppStatus.UNKNOWN,
            latest = found.release,
            file = found.file,
            otherFiles = found.otherFiles,
            verification = found.verification,
            lastCheckedMs = System.currentTimeMillis(),
        )
        _apps.update { ordered(it + newRow) }
        log(newRow, EventKind.ADDED, "Added from ${found.spec.url.substringAfter("://").substringBefore('/')}.")
        if (install) install(id)
        return id
    }

    override suspend fun check(appId: String?) {
        if (!_online.value) return
        val ids = appId?.let { listOf(it) } ?: _apps.value.map { it.id }
        if (appId == null) _checkingAll.value = true
        ids.forEach { id -> edit(id) { it.copy(checking = true) } }
        delay(stepMs * 8)
        val now = System.currentTimeMillis()
        ids.forEach { id ->
            edit(id) { r ->
                val known = if (r.status == AppStatus.UNKNOWN) {
                    val release = Invent(now).release(r.id, "1.0.0", 1)
                    val (file, others) = Invent(now).choices(release)
                    r.copy(status = AppStatus.NOT_INSTALLED, latest = release, file = file, otherFiles = others)
                } else {
                    r
                }
                val stale = nothingWaits && known.progress?.phase == Phase.WAITING_FOR_USER
                known.copy(checking = false, lastCheckedMs = now, progress = if (stale) null else known.progress)
            }
        }
        if (appId == null) _checkingAll.value = false
    }

    override fun install(appId: String, releaseId: String?, assetUrl: String?) {
        val start = row(appId) ?: return
        if (start.status == AppStatus.BLOCKED || start.config.trackOnly || jobs.containsKey(appId)) return
        jobs[appId] = scope.launch {
            try {
                runInstall(appId, releaseId)
            } finally {
                jobs.remove(appId)
            }
        }
    }

    private suspend fun runInstall(appId: String, releaseId: String?) {
        edit(appId) { it.copy(progress = Progress(Phase.QUEUED)) }
        delay(stepMs * 2)
        val total = row(appId)?.file?.asset?.size ?: 20_000_000L
        for (step in 0..20) {
            edit(appId) { it.copy(progress = Progress(Phase.DOWNLOADING, total * step / 20, total)) }
            delay(stepMs)
        }
        log(row(appId), EventKind.DOWNLOADED, "Downloaded ${row(appId)?.file?.asset?.name ?: "the file"}.")
        edit(appId) { it.copy(progress = Progress(Phase.VERIFYING)) }
        delay(stepMs * 5)
        edit(appId) { r ->
            val v = r.verification?.let {
                val measured = r.file?.asset?.sha256 ?: fakeHash("measured:${r.id}")
                it.copy(signersVerified = true, checksum = if (it.checksum == ChecksumState.PENDING) ChecksumState.MATCHED else it.checksum, fileSha256 = measured)
            }
            r.copy(verification = v, progress = Progress(Phase.INSTALLING))
        }
        log(row(appId), EventKind.VERIFIED, "Signer and checksum checked.")
        delay(stepMs * 5)
        finishInstall(appId, releaseId)
    }

    private fun finishInstall(appId: String, releaseId: String?) {
        edit(appId) { r ->
            val version = releaseId?.removePrefix("v")?.ifEmpty { null } ?: r.latest?.version?.ifBlank { null } ?: "1.0"
            val pkg = r.config.packageName ?: "org.example.${r.id}"
            val signers = r.verification?.signers.orEmpty()
            r.copy(
                status = AppStatus.UP_TO_DATE,
                installed = InstalledApp(pkg, version, Invent(0).versionCode(version), signers),
                progress = null,
                problem = null,
            )
        }
        log(row(appId), EventKind.INSTALLED, "Installed version ${row(appId)?.installed?.versionName}.")
    }

    override fun installAllUpdates() {
        _apps.value.filter { it.status == AppStatus.UPDATE_AVAILABLE && !it.config.trackOnly && it.progress == null }
            .forEach { install(it.id) }
    }

    override fun cancel(appId: String) {
        jobs.remove(appId)?.cancel()
        edit(appId) { it.copy(progress = null) }
    }

    override suspend fun remove(appId: String) {
        val gone = row(appId) ?: return
        cancel(appId)
        _apps.update { rows -> rows.filterNot { it.id == appId } }
        log(gone, EventKind.REMOVED, "Stopped following ${gone.config.name}.")
    }

    override fun uninstall(appId: String) {
        edit(appId) { it.copy(installed = null, status = AppStatus.NOT_INSTALLED) }
    }

    override fun open(appId: String): Boolean = row(appId)?.let { it.installed != null && it.id != "tidetable" } ?: false

    /** Switched off by a test to stand for a phone that has not yet allowed installs. */
    @Volatile var installsAllowed = true

    override fun mayInstall(): Boolean = installsAllowed

    private val _installerReadiness = MutableStateFlow(InstallerReadiness.READY)
    override val installerReadiness: StateFlow<InstallerReadiness> = _installerReadiness.asStateFlow()

    /** The stand-in has Shizuku running and waiting for a yes, root refused, and one installer app. */
    override fun recheckInstaller() {
        _installerReadiness.value = when (_settings.value.installer) {
            InstallerMode.SYSTEM -> InstallerReadiness.READY
            InstallerMode.SHIZUKU -> if (shizukuAllowed) InstallerReadiness.READY else InstallerReadiness.SHIZUKU_NOT_ALLOWED
            InstallerMode.ROOT -> InstallerReadiness.NO_ROOT
            InstallerMode.OTHER_APP ->
                if (_settings.value.otherInstaller == null) InstallerReadiness.NO_OTHER_APP else InstallerReadiness.READY
        }
    }

    @Volatile private var shizukuAllowed = false

    override fun askShizuku(): Boolean {
        shizukuAllowed = true
        recheckInstaller()
        return true
    }

    override fun installerChoices(): List<InstallerChoice> =
        listOf(InstallerChoice("org.example.installer", "Example Installer"))

    override suspend fun configure(appId: String, change: (AppConfig) -> AppConfig) {
        edit(appId) { it.copy(config = change(it.config).copy(id = appId)) }
    }

    override suspend fun followMove(appId: String): Problem? {
        delay(stepMs * 4)
        val r = row(appId) ?: return Problem(ProblemKind.NOT_FOUND, "This app is no longer in the list.")
        val target = r.movedTo ?: return Problem(ProblemKind.NOT_FOUND, "There is no new address to follow.")
        if (target != FakeLinks.MOVED_HOME) {
            return Problem(ProblemKind.SIGNER_MISMATCH, "The new address offers the app signed by someone else, so the old address was kept.")
        }
        edit(appId) { it.copy(config = it.config.copy(source = it.config.source.copy(url = target)), movedTo = null) }
        log(row(appId), EventKind.MOVED, "Followed the project to $target")
        return null
    }

    override suspend fun keepAddress(appId: String) {
        edit(appId) { it.copy(movedTo = null) }
    }

    override suspend fun dismissRelease(appId: String) {
        edit(appId) { r ->
            val skipped = r.config.copy(releases = r.config.releases.copy(skippedReleaseId = r.latest?.id))
            r.copy(config = skipped, status = if (r.installed != null) AppStatus.UP_TO_DATE else r.status)
        }
    }

    override suspend fun releases(appId: String): List<Release> {
        delay(stepMs * 2)
        val r = row(appId) ?: return emptyList()
        val invent = Invent(System.currentTimeMillis())
        val latest = r.latest ?: return emptyList()
        if (appId == "harborterm") {
            return (30 downTo 1).map { major -> invent.release(appId, "$major.0.0", (30 - major) * 9 + 1, prerelease = major % 7 == 0) }
        }
        val older = (1..2).map { n -> invent.release(appId, "0.$n", 40 * n) }
        return listOf(latest) + older
    }

    override suspend fun notes(release: Release): List<NoteBlock> {
        delay(stepMs)
        return when {
            release.id.isEmpty() || release.title?.startsWith("pocketnotes") == true -> emptyList()
            release.title?.startsWith("harborterm") == true -> longNotes(release.version)
            else -> shortNotes(release.version)
        }
    }

    override suspend fun saveSettings(settings: Settings) {
        _settings.value = settings
    }

    override suspend fun setToken(host: String, token: String?) {
        tokens.update { if (token.isNullOrBlank()) it - host else it + host }
    }

    override suspend fun tokenHosts(): List<String> = tokens.value.toList()

    override suspend fun importFrom(uri: Uri): ImportSummary {
        delay(stepMs * 12)
        val invent = Invent(System.currentTimeMillis())
        val heron = invent.row("imported1", "Heron Books", AppStatus.UNKNOWN, null, null, pins = listOf(fakeHash("signer:imported:heron")))
        val plover = invent.row("imported2", "Plover Chat", AppStatus.UNKNOWN, null, null)
        val added = listOf(
            heron.copy(config = heron.config.copy(assets = AssetPolicy(include = "arm64"))),
            plover.copy(config = plover.config.copy(releases = ReleasePolicy(tagFilter = "^v"))),
            invent.row("imported3", "Rook Budget", AppStatus.UNKNOWN, null, null),
        ).filter { row(it.id) == null }
        _apps.update { ordered(it + added) }
        log(null, EventKind.IMPORTED, "Imported ${added.size} apps.")
        return ImportSummary(
            added = added.size,
            alreadyPresent = 2,
            skipped = listOf(
                "Pixel Fetch" to "Its source, a chat channel, is not one Tern can read.",
                "Old Sync" to "The export had no link for it.",
            ),
            withPins = added.filter { it.config.pinnedSigners.isNotEmpty() }.map { it.config.name },
            withFilters = added.filter { it.config.assets.include != null || it.config.releases.tagFilter != null }.map { it.config.name },
            askedToInstallByThemselves = added.take(1).map { it.config.name },
        )
    }

    /** Switched off by a test to stand for a television, which has no file picker. */
    @Volatile var filePicker = true

    /** Switched off by a test to stand for a device whose settings an app cannot open. */
    @Volatile var installSettings = true

    /** Switched off by a test to stand for a device on no local network. */
    @Volatile var localNetwork = true

    override fun hasFilePicker(): Boolean = filePicker

    override suspend fun exportToFolder(): SavedFile {
        delay(stepMs * 6)
        return SavedFile("tern-apps-2026-09-29.json", "Download/Tern", "/storage/emulated/0/Download/Tern/tern-apps-2026-09-29.json", System.currentTimeMillis(), 18_432)
    }

    /** Switched off by a test to stand for a device that holds no export file. */
    @Volatile var savedFiles = true

    override suspend fun importableFiles(): List<SavedFile> {
        delay(stepMs * 4)
        if (!savedFiles) return emptyList()
        val now = System.currentTimeMillis()
        return listOf(
            SavedFile("tern-apps-2026-09-29.json", "Download/Tern", "/storage/emulated/0/Download/Tern/tern-apps-2026-09-29.json", now - 3_600_000, 18_432),
            SavedFile("obtainium-export.json", "Download", "/storage/emulated/0/Download/obtainium-export.json", now - 86_400_000 * 3, 41_200),
        )
    }

    override suspend fun importFromFile(file: SavedFile): ImportSummary = importFrom(Uri.EMPTY)

    override suspend fun importFromLink(url: String): ImportSummary {
        if (!url.trim().startsWith("https://")) throw ProblemException(Problem(ProblemKind.UNSUPPORTED, "Only https links are accepted."))
        if (url.contains("missing")) throw ProblemException(Problem(ProblemKind.NOT_FOUND, "The server answered 404: there is no file at that address."))
        return importFrom(Uri.EMPTY)
    }

    override suspend fun importReceived(file: Received.ExportFile): ImportSummary = importFrom(Uri.EMPTY)

    override fun suggestions(): List<Suggestion> = FakeSuggestions.all

    override fun canOpenInstallSettings(): Boolean = installSettings

    override suspend fun openHandoff(): Problem? {
        if (!localNetwork) return Problem(ProblemKind.NETWORK, "This device is on no local network, so a phone cannot reach it.")
        received.clear()
        _handoffEnd.value = null
        _handoff.value = Handoff("http://192.168.1.23:48211", "k4mz q7wd x2np h5tc r3vb", FakeSuggestions.pattern(), System.currentTimeMillis() + 600_000, 0)
        handoffJob?.cancel()
        handoffJob = scope.launch {
            delay(stepMs * 40)
            receive(Received.Link(FakeLinks.NEW_APP))
            delay(stepMs * 10)
            receive(Received.ExportFile("tern-apps-2026-09-29.json", ByteArray(18_432)))
        }
        return null
    }

    /** What a phone would have sent; tests call it to make something arrive at a moment of their choosing. */
    fun receive(item: Received) {
        if (_handoff.value == null) return
        received += item
        _handoff.update { it?.copy(waiting = received.size) }
    }

    override fun closeHandoff() = endHandoff(HandoffEnd.CLOSED)

    /** Ends the handoff the way the real one can end by itself; tests call it with the reason they want to see. */
    fun endHandoff(why: HandoffEnd) {
        handoffJob?.cancel()
        if (why == HandoffEnd.CLOSED) received.clear()
        if (_handoff.value != null) _handoffEnd.value = why
        _handoff.value = null
    }

    override fun askOrbot() {
        orbotAsked++
        scope.launch {
            if (orbotAnswer == OrbotState.ON) {
                _orbot.value = OrbotState.STARTING
                delay(stepMs * 8)
            }
            _orbot.value = orbotAnswer
        }
    }

    override fun openOrbot(): Boolean {
        if (orbotAnswer == OrbotState.NOT_INSTALLED) return false
        orbotOpened++
        askOrbot()
        return true
    }

    override fun takeReceived(): List<Received> {
        val taken = received.toList()
        received.removeAll(taken.toSet())
        _handoff.update { it?.copy(waiting = received.size) }
        return taken
    }

    /** "example" stars a few of every kind, "many" stars 240 tools, "nobody" does not exist, "busy" meets a rate limit. */
    override suspend fun starredBy(user: String): List<SearchHit> {
        delay(stepMs * 4)
        val name = user.trim().removePrefix("@")
        return when (name.lowercase()) {
            "nobody" -> throw ProblemException(Problem(ProblemKind.NOT_FOUND, "GitHub has no user named $name."))
            "busy" -> throw ProblemException(Problem(ProblemKind.RATE_LIMITED, "GitHub asked Tern to wait.", System.currentTimeMillis() + 20 * 60_000))
            "many" -> (1..240).map { tool(it) }
            "" -> throw ProblemException(Problem(ProblemKind.NOT_FOUND, "That is not a GitHub user name."))
            else -> listOf(
                SearchHit("sparrow", "example", "A small feed reader that works offline.", FakeLinks.NEW_APP, "GitHub", 1240),
                SearchHit("trailmap", "example", "Maps for walking, kept on the phone.", FakeLinks.TRACKED_APP, "GitHub", 310),
                SearchHit("missing", "example", null, FakeLinks.MISSING, "GitHub", 2),
            ) + (1..5).map { tool(it) }
        }
    }

    private fun tool(n: Int) = SearchHit("tool-$n", "example", "A command line tool with no Android build.", "${FakeLinks.NO_FILE_PREFIX}$n", "GitHub", n * 3)

    override suspend fun exportTo(uri: Uri, format: ExportFormat): Int {
        val rows = _apps.value
        val json = rows.joinToString(",", "{\"apps\":[", "]}") { "{\"id\":\"${it.id}\"}" }
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("No output")
        return rows.size
    }

    private val _exportStatus = MutableStateFlow<ExportStatus?>(null)
    override val exportStatus: StateFlow<ExportStatus?> = _exportStatus.asStateFlow()

    override suspend fun takeExportFolder(folder: Uri) = Unit

    override suspend fun runBackgroundCheck() = check(null)

    override suspend fun saveFile(appId: String, releaseId: String, assetUrl: String): SavedFile =
        SavedFile(assetUrl.substringAfterLast('/'), "Download/Tern", "/storage/emulated/0/Download/Tern/" + assetUrl.substringAfterLast('/'), System.currentTimeMillis(), 0)

    override val searchOrigins: List<String> = listOf("GitHub", "Codeberg", "GitLab", "F-Droid", "Aptoide", "Uptodown")

    override fun renderNotes(text: String): List<NoteBlock> = io.github.munzzyy.tern.engine.real.NotesMapper.markdown(text)

    override fun canDowngrade(): Boolean = false

    override suspend fun writeKeptExport() {
        _exportStatus.value = ExportStatus(System.currentTimeMillis(), null)
    }

    /** The stand-in writes its ids to the share folder, as the real engine writes a whole export there. */
    override suspend fun shareableExport(appIds: Collection<String>?, format: ExportFormat): Uri {
        val rows = _apps.value.filter { appIds == null || it.id in appIds }
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val file = java.io.File(dir, if (format == ExportFormat.OBTAINIUM) "obtainium-export.json" else "tern-apps.json")
        file.writeText(rows.joinToString(",", "{\"apps\":[", "]}") { "{\"id\":\"${it.id}\"}" })
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.handoff", file)
    }

    override suspend fun clearEvents() {
        _events.value = emptyList()
    }

    /** Every second suggestion has a picture, so a list shows both kinds. */
    override suspend fun icon(found: Detection.Found, sizePx: Int): Bitmap? {
        if (found.name.length % 2 == 0) return null
        val size = sizePx.coerceIn(1, 512)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B5F8A.toInt() }
        Canvas(bitmap).apply {
            drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.color = 0xFFFFFFFF.toInt()
            drawCircle(size / 2f, size / 2f, size * 0.2f, paint)
        }
        return bitmap
    }

    override suspend fun icon(row: AppRow, sizePx: Int): Bitmap? {
        val color = when (row.id) {
            "pocketnotes" -> 0xFF2E7D6B.toInt()
            "trailmap" -> 0xFF8C5A1C.toInt()
            "vaultkeys" -> 0xFF4A3F8C.toInt()
            else -> return null
        }
        val size = sizePx.coerceIn(1, 512)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.25f, size * 0.25f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size * 0.22f, paint)
        return bitmap
    }
}
