package io.github.munzzyy.tern.engine

import android.graphics.Bitmap
import android.net.Uri
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Release
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens may ask for. Screens hold no logic of their own: they draw these flows and
 * call these functions. Every suspend function is safe to call from the main thread.
 */
interface Engine {
    /** Apps with updates first, then the rest by name. Emits on every change, including progress. */
    val apps: StateFlow<List<AppRow>>

    /** Newest first, at most 500. */
    val events: StateFlow<List<Event>>

    val settings: StateFlow<Settings>

    /** True while a check of the whole list is running. */
    val checkingAll: StateFlow<Boolean>

    /** How far the running check of the whole list has got; null while none runs. */
    val checkCount: StateFlow<CheckCount?>

    /** False while the device has no working internet connection. */
    val online: StateFlow<Boolean>

    /** Looks at what the user typed, pasted or shared: a link is resolved, anything else is searched. */
    suspend fun detect(input: String): Detection

    /** Every place a search can look, by the name each goes by; [Settings.searchIn] picks among them. */
    val searchOrigins: List<String>

    /** [text] written in Markdown, such as an app's notes, as blocks to show. Links go only to web addresses. */
    fun renderNotes(text: String): List<NoteBlock>

    /** Whether the app's source keeps a project page Tern can read: a README on GitHub, GitLab or a Forgejo. */
    fun hasProjectPage(row: AppRow): Boolean

    /**
     * The app's project page, its README, as blocks to show; empty when the project keeps none.
     * Throws [ProblemException] when it cannot be read now.
     */
    suspend fun projectPage(appId: String): List<NoteBlock>

    /**
     * The configuration [add] would store for [found]. An app that arrives by link or import can
     * carry filters and pinned certificates, and the user has to see them before they are stored.
     */
    fun proposedConfig(found: Detection.Found): AppConfig

    /**
     * Starts tracking and stores exactly what [proposedConfig] returns. Returns the id of the new
     * app, or of the existing one when it was already tracked.
     */
    suspend fun add(found: Detection.Found, install: Boolean): String

    /** Checks one app, or all of them when [appId] is null. Returns when the check is finished. */
    suspend fun check(appId: String? = null)

    /**
     * Downloads, verifies and installs. Returns at once; progress and the outcome arrive through
     * [apps]. [releaseId] picks a release other than the offered one, [assetUrl] a file other than
     * the recommended one.
     */
    fun install(appId: String, releaseId: String? = null, assetUrl: String? = null)

    /**
     * Whether Android lets Tern install apps at all. The user says so once, in the system
     * settings. An install started without it is answered with that question and then reported
     * as cancelled, so the screens ask first.
     */
    fun mayInstall(): Boolean

    /** Whether the installer chosen in settings can be used now. Until it can, Android's own installer is used. */
    val installerReadiness: StateFlow<InstallerReadiness>

    /** Asks the chosen installer again. For root this runs su, which may show the root manager's own question. */
    fun recheckInstaller()

    /** Asks Shizuku to let Tern use it; the answer arrives through [installerReadiness]. False when Shizuku cannot be asked. */
    fun askShizuku(): Boolean

    /** Apps on this device that take an APK to install, for [InstallerMode.OTHER_APP]. */
    fun installerChoices(): List<InstallerChoice>

    fun installAllUpdates()

    fun cancel(appId: String)

    /**
     * Reopens Android's confirmation for an install that waits for the user. False when nothing
     * waits for this app any more.
     */
    fun resumeInstall(appId: String): Boolean

    /** Stops tracking. The installed app stays. */
    suspend fun remove(appId: String)

    /** Opens Android's own uninstall dialog for the app. */
    fun uninstall(appId: String)

    /** Opens the installed app. False when it has no launcher. */
    fun open(appId: String): Boolean

    /**
     * Changes the app's settings. [change] is given the settings as they are stored, not as they
     * were last drawn, so what an install learned in the meantime (the signer, the package name)
     * is kept. Throws IllegalArgumentException when the result is not valid.
     */
    suspend fun configure(appId: String, change: (AppConfig) -> AppConfig)

    /**
     * Replaces the app's source with the address in [AppRow.movedTo], after detecting it afresh.
     * Only a source offering the same package and, where known on both sides, the same signer is
     * accepted. Returns why not otherwise, and null once the source is replaced.
     */
    suspend fun followMove(appId: String): Problem?

    /** Keeps the current source and stops suggesting the address in [AppRow.movedTo]. */
    suspend fun keepAddress(appId: String)

    /** Marks the offered release as seen (track-only) or skipped (installable). */
    suspend fun dismissRelease(appId: String)

    /** Releases from the last successful check, newest first. */
    suspend fun releases(appId: String): List<Release>

    /** The release's notes, parsed for drawing. Empty when it has none. */
    suspend fun notes(release: Release): List<NoteBlock>

    /** Stores the settings and applies what follows from them: the schedule, the proxy, the link handler. */
    suspend fun saveSettings(settings: Settings)

    /** Stores a token for exactly [host]; null or blank removes it. */
    suspend fun setToken(host: String, token: String?)

    /** Hosts that have a token stored. The tokens themselves never leave the engine. */
    suspend fun tokenHosts(): List<String>

    suspend fun importFrom(uri: Uri): ImportSummary

    /**
     * False where Android has no file picker, which is the usual case on a television. Import and
     * export then go through [exportToFolder], [importableFiles], [importFromLink] and [handoff].
     */
    fun hasFilePicker(): Boolean

    /** Writes the export where a file manager can find it and returns where that is. Tokens are never exported. */
    suspend fun exportToFolder(format: ExportFormat = ExportFormat.TERN): SavedFile

    /** Export files this app may read without a picker, newest first. */
    suspend fun importableFiles(): List<SavedFile>

    suspend fun importFromFile(file: SavedFile): ImportSummary

    /** Imports an export file served at an HTTPS address. Throws [ProblemException] when it cannot be had or read. */
    suspend fun importFromLink(url: String): ImportSummary

    suspend fun importReceived(file: Received.ExportFile): ImportSummary

    /** Well known apps to start from, those for a television first when this device is one. */
    fun suggestions(): List<Suggestion>

    /**
     * False where the system has no settings page for [mayInstall] that an app can open, as on some
     * televisions. The screens then say where to find the switch by hand.
     */
    fun canOpenInstallSettings(): Boolean

    /** The open handoff, or null while there is none. */
    val handoff: StateFlow<Handoff?>

    /** Why the last handoff ended. Null while one is open, and before the first. */
    val handoffEnd: StateFlow<HandoffEnd?>

    /**
     * Opens the handoff for ten minutes, or until [closeHandoff]. Returns why not when this device
     * is on no local network.
     */
    suspend fun openHandoff(): Problem?

    fun closeHandoff()

    /** Takes what has arrived, oldest first. What is taken is gone from the handoff. */
    fun takeReceived(): List<Received>

    /** How Orbot is doing. It changes after [askOrbot] and whenever Orbot says something by itself. */
    val orbot: StateFlow<OrbotState>

    /** Asks Orbot how it is doing and to start if it is off. Does nothing when Orbot is not installed. */
    fun askOrbot()

    /** Opens Orbot, for the person to connect it there, and asks it to start. False when there is no Orbot to open. */
    fun openOrbot(): Boolean

    /**
     * Repositories [user] has starred, at most 300, for the user to pick from. Nothing is added.
     * Throws [ProblemException] when the name is not valid or the list cannot be had.
     */
    suspend fun starredBy(user: String): List<SearchHit>

    /** Returns how many apps were written. Tokens are never exported. */
    suspend fun exportTo(uri: Uri, format: ExportFormat = ExportFormat.TERN): Int

    /**
     * The apps in [appIds], or every app when it is null, written in [format] to a file another
     * app may read through the returned address, for sharing. Tokens are never in it.
     */
    suspend fun shareableExport(appIds: Collection<String>?, format: ExportFormat): Uri

    /** How the kept export stands, null before it was first written in this run. */
    val exportStatus: StateFlow<ExportStatus?>

    /** Keeps Android's grant of the folder the person picked for the kept export. Store the folder with [saveSettings] after. */
    suspend fun takeExportFolder(folder: Uri)

    /** Writes the kept export now. */
    suspend fun writeKeptExport()

    /**
     * Downloads [assetUrl] of the release [releaseId] of the app and puts a copy in Download/Tern,
     * as it came. Nothing is checked or installed. Throws [ProblemException] when it cannot.
     */
    suspend fun saveFile(appId: String, releaseId: String, assetUrl: String): SavedFile

    /** Runs the background check now, installs of apps set to update by themselves included. */
    suspend fun runBackgroundCheck()

    /** True when Let Me Downgrade is installed, without which Android refuses an older version over a newer one. */
    fun canDowngrade(): Boolean

    suspend fun clearEvents()

    /**
     * Icon of the installed app, else of its downloaded file, else the one its source offers when
     * the setting allows fetching it. Null when there is none of these.
     */
    suspend fun icon(row: AppRow, sizePx: Int): Bitmap?

    /** Icon of an app that was found and is not in the list yet, from its source. Null when there is none. */
    suspend fun icon(found: Detection.Found, sizePx: Int): Bitmap?
}
