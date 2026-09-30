package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode

enum class AppStatus {
    /** Never checked. */
    UNKNOWN,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    NOT_INSTALLED,

    /** Track-only app with a release the user has not seen yet. */
    NEW_RELEASE,

    /** A release exists but Tern refuses it; see [AppRow.problem]. */
    BLOCKED,

    /** The last check failed; see [AppRow.problem]. */
    ERROR,
}

enum class ProblemKind {
    NETWORK,
    RATE_LIMITED,
    NOT_FOUND,
    AUTH,
    PARSE,
    NO_RELEASES,
    NO_FILE_FOR_DEVICE,
    CHECKSUM_MISMATCH,
    /** Signed by someone other than the signer of the installed app; Android itself refuses such a file. */
    SIGNER_MISMATCH,

    /** Signed by someone other than the certificate pinned for this app. */
    PIN_MISMATCH,
    PACKAGE_MISMATCH,
    DOWNGRADE,
    INSTALL_FAILED,
    STORAGE,
    UNSUPPORTED,

    /** The app comes from a third-party store while those are off, so nothing of it is asked for. */
    STORES_OFF,
}

data class Problem(
    val kind: ProblemKind,
    /** Plain sentence for the user, already localized. */
    val message: String,
    /** When a retry can succeed, for rate limits. */
    val retryAtMs: Long? = null,
)

/** Thrown by an engine call that has no answer to give, with the reason already in plain words. */
class ProblemException(val problem: Problem) : Exception(problem.message)

enum class SignerState {
    /** Nothing known yet. */
    UNKNOWN,

    /** First time this app is seen and nothing is pinned; the certificate will be pinned on install. */
    FIRST_SEEN,
    MATCHES_PIN,
    MATCHES_INSTALLED,
    MISMATCH,
}

enum class ChecksumState {
    /** The publisher offers no checksum for this file. */
    NOT_PUBLISHED,

    /** A checksum exists and will be compared after download. */
    PENDING,
    MATCHED,
    MISMATCH,
}

data class Verification(
    val packageName: String?,
    /** SHA-256 of the signing certificates, lowercase hex. */
    val signers: List<String>,
    /** False while the certificates were only read from the file's header; true once every file downloaded had its signature verified, by Android or by Tern's own verifier. */
    val signersVerified: Boolean,
    val signerState: SignerState,
    val checksum: ChecksumState,
    /** Where the checksum came from, for example "GitHub release digest" or "signed repository index". */
    val checksumSource: String?,
    /** Permissions the offered version asks for that the installed one does not. */
    val newPermissions: List<String>,
    /** SHA-256 of the file, lowercase hex: the publisher's digest before download, the measured hash after. */
    val fileSha256: String? = null,
    /** The checksum is one a third-party store gives, which shows the file arrived as the store has it and says nothing of the developer. */
    val checksumFromStore: Boolean = false,
)

enum class Phase { QUEUED, DOWNLOADING, VERIFYING, INSTALLING, WAITING_FOR_USER }

data class Progress(val phase: Phase, val bytesDone: Long = 0, val bytesTotal: Long? = null) {
    val fraction: Float? get() = bytesTotal?.takeIf { it > 0 }?.let { (bytesDone.toFloat() / it).coerceIn(0f, 1f) }
}

data class FileChoice(
    val asset: Asset,
    /** Short plain phrases that explain the ranking, such as "matches this device (arm64-v8a)". */
    val reasons: List<String>,
    /** The host the file is served from when that is not the site of the app's source. It is said, never held against the file. */
    val foreignHost: String? = null,
    /** The person picked this file, and the updates keep to its kind. */
    val picked: Boolean = false,
)

data class AppRow(
    val config: AppConfig,
    val installed: InstalledApp?,
    val status: AppStatus,
    /** False when only version text could be compared, so an update is likely rather than sure. */
    val statusCertain: Boolean = true,
    val latest: Release? = null,
    val file: FileChoice? = null,
    val otherFiles: List<FileChoice> = emptyList(),
    val verification: Verification? = null,
    val progress: Progress? = null,
    val problem: Problem? = null,
    val lastCheckedMs: Long? = null,
    /** True when Android will let this update install without a prompt; null when unknown. */
    val silentUpdate: Boolean? = null,
    val checking: Boolean = false,
    /** Where the source says the project lives now. Shown as a suggestion, never followed silently. */
    val movedTo: String? = null,
    /** When the app was added to Tern; null for apps added before this was kept. */
    val addedAtMs: Long? = null,
    /** What the source says the app is, in a sentence or two. */
    val description: String? = null,
) {
    val id: String get() = config.id
}

/** The last three are Tern's own messages, kept only while [Settings.keepOwnMessages] is on: see [isOwn]. */
enum class EventKind { ADDED, REMOVED, IMPORTED, UPDATE_FOUND, DOWNLOADED, VERIFIED, INSTALLED, BLOCKED, FAILED, CHECK_FAILED, MOVED, CANCELLED, OWN_NOTE, OWN_WARNING, OWN_ERROR }

data class Event(
    val id: Long,
    val atMs: Long,
    val appId: String?,
    val appName: String?,
    val kind: EventKind,
    val message: String,
)

data class SearchHit(
    val name: String,
    val owner: String?,
    val description: String?,
    val url: String,
    /** Human name of where it was found, such as "GitHub" or "Codeberg". */
    val origin: String,
    val stars: Int? = null,
    /** The kind of source to read [url] as, where the address alone does not say, as for a project on a Forgejo of its own. */
    val type: String? = null,
)

/** A place a search could not look in, and why, in one sentence. */
data class SearchMiss(val origin: String, val reason: String)

/** How the Add screen asks for an address to be read, beyond what [Engine.detect] finds by itself. */
data class Reading(
    /** The kind of source to read the address as, one of those a person may pick; null lets Tern find out. */
    val type: String? = null,
    /** The options of the source to read it with, in place of those the address carries; null keeps those. */
    val options: Map<String, String>? = null,
    /** The package name the person gave. The app is held to it and nothing is learned in its place. */
    val packageName: String? = null,
    /** Words to look for among the apps of a repository that is given by its address. */
    val words: String? = null,
)

sealed interface Detection {
    data class Found(
        val spec: SourceSpec,
        val name: String,
        val author: String?,
        val description: String?,
        val release: Release?,
        val file: FileChoice?,
        val otherFiles: List<FileChoice>,
        val verification: Verification?,
        val installed: InstalledApp?,
        /** Id of the tracked app when this source is already in the list. */
        val alreadyTracked: String?,
        /** Things the user should know before adding, plain sentences. */
        val warnings: List<String>,
        /** Settings that came with a link or a file. Not stored until the user has seen them and agreed. */
        val carried: AppConfig? = null,
        /** Where the source says an icon can be had, best first. The engine decides whether to ask. */
        val iconUrls: List<String> = emptyList(),
        /** True when the certificate this app is held to is one Tern itself carries for it, and not one that came with a link or a file. */
        val builtInPin: Boolean = false,
        /** The package name the person gave. The app is stored with it, and a file of another package is refused. */
        val packageName: String? = null,
    ) : Detection

    /**
     * A list to pick from: what a search found, or the apps of a repository that was given by its
     * address. [more] says the list was cut and the rest is not shown. [missed] names the places a
     * search could not look in. [within] holds the words a repository's apps were searched for.
     */
    data class Results(
        val query: String,
        val hits: List<SearchHit>,
        val more: Boolean = false,
        val missed: List<SearchMiss> = emptyList(),
        val within: String? = null,
    ) : Detection {
        companion object {
            /** The query of a list of apps that came in one link, which the Add screen heads apart from a search. */
            const val CARRIED = "obtainium://apps"
        }
    }

    /** [spec] is the source that was read, when Tern got as far as knowing it, so that its options can be set and it can be read again. */
    data class Failed(val problem: Problem, val spec: SourceSpec? = null) : Detection

    /** The address belongs to the third-party store [type], and those are off. Nothing was asked of it. */
    data class StoresOff(val type: String) : Detection
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the list is ordered. Apps with an update come first unless [Settings.updatesFirst] is off. */
enum class AppSort { NAME, AUTHOR, ADDED, RELEASED, RECENTLY_CHECKED, SOURCE }

/** How the list is divided. An app with several categories is shown under each of them. */
enum class AppGrouping { NONE, CATEGORY, SOURCE }

/** Tern's own export, or the file Obtainium imports, for a list that moves to Obtainium. */
enum class ExportFormat { TERN, OBTAINIUM }

/** How the kept export stands: when it was last written, or why it could not be. */
data class ExportStatus(val writtenAtMs: Long?, val problem: String?)

/** What hands a checked file to Android. Whichever it is, the file passed the same checks first. */
enum class InstallerMode {
    /** Android's own installer: asks before a first install, and before an update where Android wants it. */
    SYSTEM,

    /** pm, run through Shizuku or Sui: installs and updates without a prompt. */
    SHIZUKU,

    /** Android's installer, called through Dhizuku as the device owner: installs and updates without a prompt. Never hands an install to another. */
    DHIZUKU,

    /** pm, run through su: installs and updates without a prompt. */
    ROOT,

    /** Another installer app the person chose. It always asks, so it never runs in the background. */
    OTHER_APP,
}

/** Whether the chosen installer can be used now, and if not, what stands in the way. */
enum class InstallerReadiness {
    READY,
    SHIZUKU_NOT_RUNNING,
    SHIZUKU_TOO_OLD,
    SHIZUKU_NOT_ALLOWED,
    NO_ROOT,

    /** "Another app" is chosen and no app is picked, or the one picked is gone. */
    NO_OTHER_APP,

    /** This Android lacks a part of its installer that Tern reaches through Dhizuku. */
    DHIZUKU_UNSUPPORTED,
    DHIZUKU_NOT_INSTALLED,

    /** Dhizuku is installed, and neither the device owner nor a profile owner that may install without asking. */
    DHIZUKU_NOT_OWNER,

    /** Dhizuku is the owner and did not hand over its binder, or the binder did not answer. */
    DHIZUKU_NOT_ANSWERING,

    /** Dhizuku answers, and Tern has not been allowed to use it. */
    DHIZUKU_NOT_ALLOWED,
}

/**
 * An app on this device that takes an APK to install. [activity] names one way into it, for an app
 * that has several, with the name the app gives that way; null leaves it to the app.
 */
data class InstallerChoice(val packageName: String, val label: String, val activity: String? = null, val activityLabel: String? = null)

enum class ProxyMode { NONE, ORBOT, CUSTOM }

data class Settings(
    /** Minutes between background checks; 0 turns them off. Android runs them 15 minutes apart at the least. */
    val checkEveryMinutes: Int = 360,
    val onlyOnUnmetered: Boolean = false,
    val onlyWhileCharging: Boolean = false,
    val defaultUpdateMode: UpdateMode = UpdateMode.NOTIFY,
    val includePrereleasesByDefault: Boolean = false,
    val minAgeDaysByDefault: Int = 0,
    val notifyUpdates: Boolean = true,
    val notifyInstalled: Boolean = true,
    val notifyFailures: Boolean = false,
    /** Off keeps the names of apps out of every notification, and with that off the lock screen. */
    val notifyNames: Boolean = true,
    /** New releases of apps that are only tracked get a notification of their own. */
    val notifyTracked: Boolean = true,
    /** A quiet notification shows while a background check runs. */
    val notifyChecking: Boolean = false,
    val keepInstallers: Boolean = false,
    /** Ask Android to make Tern the update owner of what it installs (Android 14 and later). */
    val claimUpdateOwnership: Boolean = false,
    val openObtainiumLinks: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.WALLPAPER,
    val palette: Palette = Palette.INK,
    /** The hue of the user's own colour, in degrees from 0 to 359. */
    val customHue: Int = 250,
    val contrast: Contrast = Contrast.STANDARD,
    val pureBlack: Boolean = false,
    val density: Density = Density.COMFORTABLE,
    val corners: Corners = Corners.ROUND,
    val iconShape: IconShape = IconShape.CIRCLE,
    /** Fetch an app's icon from its source while the app is not installed. Off draws a letter on a colour. */
    val sourceIcons: Boolean = true,
    val proxy: ProxyMode = ProxyMode.NONE,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 9050,
    val listSort: AppSort = AppSort.NAME,
    val listDescending: Boolean = false,
    val listGrouping: AppGrouping = AppGrouping.NONE,
    /** Apps with an update above all others, whatever the order. */
    val updatesFirst: Boolean = true,
    /** Apps that are not installed below all others. */
    val buryNotInstalled: Boolean = false,
    /** Swiping a row starts its update, or the other way removes it. Only on touch screens. */
    val swipeActions: Boolean = true,
    /** Keep an export of the list up to date in [exportFolder], rewritten a moment after anything changes. */
    val autoExport: Boolean = false,
    /** A folder the person picked, as the address Android gave for it; null means Download/Tern. */
    val exportFolder: String? = null,
    /** Every export leaves out apps that are not installed. */
    val exportInstalledOnly: Boolean = false,
    /** Every Tern export carries these settings too, never a token and never the proxy or the installer. */
    val exportSettings: Boolean = false,
    val installer: InstallerMode = InstallerMode.SYSTEM,
    /** The package of the installer app used with [InstallerMode.OTHER_APP]. */
    val otherInstaller: String? = null,
    /** With Shizuku or root, record Google Play as the installer of every app, as if an app had asked for it. */
    val playInstaller: Boolean = false,
    /** Check every app each time Tern is opened. */
    val checkOnStart: Boolean = false,
    /** Check an app when its page is opened, unless it was checked a moment ago. */
    val checkOnOpen: Boolean = false,
    /** Checks of the whole list leave out apps that are neither installed nor only tracked. */
    val onlyCheckInstalled: Boolean = false,
    /** The file filter of every app that has none of its own; null leaves the choice to each app. */
    val globalFileFilter: String? = null,
    /** An app uninstalled outside Tern leaves the list as well. */
    val removeUninstalled: Boolean = false,
    /** Groups of the list start folded each time Tern is opened. */
    val collapseGroups: Boolean = false,
    /** A light tap under the finger when something is done by a swipe or a long press. */
    val haptics: Boolean = true,
    /** One pane and a bar at the bottom, however wide the screen. */
    val phoneLayout: Boolean = false,
    /** Lets an older version replace a newer one. Android refuses unless Let Me Downgrade is installed. */
    val allowDowngrades: Boolean = false,
    /** Where a search looks, by the name each place goes by. */
    val searchIn: Set<String> = DEFAULT_SEARCH,
    /** The colour picked for each category, as ARGB; a category without one takes a colour its name falls on. */
    val categoryColors: Map<String, Int> = emptyMap(),
    /** The name of the kept export's file; null for the usual name of its format. */
    val keptExportName: String? = null,
    /** The kept export is written in Tern's format, which holds everything, or in Obtainium's. */
    val keptExportFormat: ExportFormat = ExportFormat.TERN,
    /** How colourful the user's own colour is, from 0 for nearly grey to 100 for vivid. */
    val customStrength: Int = 80,
    /** The colour code the user's own colour was taken from, shown back to them; null once the hue is moved by hand. */
    val customColor: Int? = null,
    /** How far the colours of a scheme reach from the seed. Android's wallpaper colours are its own. */
    val colorStyle: ColorStyle = ColorStyle.STANDARD,
    /** GitHub, GitLab and Codeberg must show a certificate from the authority each is known to use. */
    val pinCertificates: Boolean = false,
    /** Apps set to update by themselves do; off, they only say so, and each keeps its own choice. */
    val autoInstalls: Boolean = true,
    /** What the Update all button above the list takes in, or that there is no such button. */
    val updateAllMode: UpdateAllMode = UpdateAllMode.UPDATES,
    /** Update all says first how many apps it installs or updates, and waits for a yes. */
    val confirmUpdateAll: Boolean = false,
    /** The Forgejo or Gitea a search looks in, by its host. The token stored for that host goes with the search. */
    val searchForgejo: String = DEFAULT_FORGEJO,
    /** A search of GitHub or of a Forgejo leaves out projects with fewer stars than this. */
    val searchMinStars: Int = 0,
    /** A hubproxy host every request to GitHub goes through, with no token; null goes to GitHub itself. Never exported. */
    val githubProxy: String? = null,
    /** Downloads wait for each other instead of running side by side. */
    val oneDownloadAtATime: Boolean = false,
    /** The activity of [otherInstaller] the file goes to, for an installer app with several; null leaves it to the app. */
    val otherInstallerActivity: String? = null,
    /** Before the first install of an app, its checked file goes to Verified Apps or AppVerifier, where one is installed. */
    val shareToVerifier: Boolean = true,
    /** The activity log keeps Tern's own warnings and errors too, and when each check starts and ends, with nothing secret in them. */
    val keepOwnMessages: Boolean = false,
    /**
     * APKPure, Aptoide, APKCombo, APKMirror, Tencent, Huawei AppGallery, the Galaxy Store and vivo
     * are read. Off, none of their hosts is asked: an address of theirs is not added, a search
     * leaves them out, and their apps are paused. Only the person turns it on; no file or link does.
     */
    val thirdPartyStores: Boolean = false,
) {
    companion object {
        /** The forges and F-Droid; the stores are there to be picked. */
        val DEFAULT_SEARCH: Set<String> = setOf("GitHub", "Codeberg", "GitLab", "F-Droid")

        /** The Forgejo a search looks in until another is named. */
        const val DEFAULT_FORGEJO = "codeberg.org"
    }
}

data class ImportSummary(
    val added: Int,
    val alreadyPresent: Int,
    /** Name and the reason it could not be brought over. */
    val skipped: List<Pair<String, String>>,
    /** Names of added apps that arrived with pinned signing certificates. */
    val withPins: List<String> = emptyList(),
    /** Names of added apps that arrived with release or file filters. */
    val withFilters: List<String> = emptyList(),
    /**
     * Names of added apps whose file asked for updates that install by themselves. They were
     * stored as "tell me", because that is for the user of this device to switch on.
     */
    val askedToInstallByThemselves: List<String> = emptyList(),
    /** The file carried settings, and they were taken: the look, the list, notifications and checks. */
    val settingsTaken: Boolean = false,
    /** Apps already in the list whose settings were replaced with the file's, once the person asked for it. */
    val replaced: Int = 0,
    /** Names of apps already in the list for which the file holds other settings. Nothing of theirs changes unless the person asks. */
    val replaceable: List<String> = emptyList(),
    /** The file carries settings other than these. They are not taken unless the person asks. */
    val settingsOffered: Boolean = false,
    /** What [Engine.finishImport] is given to do what the file only offered; null when it offered nothing. */
    val offer: String? = null,
    /** Names of added apps from third-party stores, whose first install decides the certificate unless they are pinned. */
    val fromStores: List<String> = emptyList(),
)

/** Where the colours come from. [WALLPAPER] needs Android 12 and falls back to [PALETTE] before that. */
enum class ColorSource { WALLPAPER, PALETTE, CUSTOM }

/** Ready-made colours. Every shade of each is worked out from one hue, so all of them pass the same contrast tests. */
enum class Palette { INK, SLATE, TIDE, MOSS, AMBER, CLAY, ROSE, PLUM }

enum class Contrast { STANDARD, MEDIUM, HIGH }

/** Standard keeps to the seed; vibrant is more colourful throughout; expressive turns its second and third colours further away. */
enum class ColorStyle { STANDARD, VIBRANT, EXPRESSIVE }

/** How much room the list takes. Minimal leaves out the icon and the author. */
enum class Density { COMFORTABLE, COMPACT, MINIMAL }

enum class Corners { ROUND, SOFT, SHARP }

/** The outline app icons are cut to in lists. */
enum class IconShape { CIRCLE, SQUIRCLE, SQUARE }

enum class SuggestionKind { MEDIA, TOOLS, PRIVACY, READING, MESSAGING, MAPS, LAUNCHERS, GAMES }

/**
 * A well known app and the address its own developer publishes it at. Built into the app and never
 * fetched, so offering one needs no network and tells nobody anything.
 */
data class Suggestion(
    val name: String,
    /** One short line in the user's language. */
    val summary: String,
    val url: String,
    val kind: SuggestionKind,
    /** Made for a television, or at home on one. */
    val forTelevision: Boolean,
    /** Tern carries the certificate this app has to be signed with, so its first install is checked against it too. */
    val pinned: Boolean = false,
)

/** A file the app can read or has written without a file picker. */
data class SavedFile(
    val name: String,
    /** Where a person would look for it, such as "Download/Tern". */
    val place: String,
    val path: String,
    val modifiedAtMs: Long,
    val sizeBytes: Long,
)

/** The dark and light squares of a QR code, [size] by [size], without the quiet border around it. */
class QrCode(val size: Int, private val dark: BooleanArray) {
    init {
        require(size > 0 && dark.size == size * size) { "A QR code of side $size needs ${size * size} squares" }
    }

    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * An open door for a phone on the same network: while it is open, the page at [address] takes
 * links and one export file and hands them to this device. Nothing that arrives is added until
 * the user has looked at it here.
 */
data class Handoff(
    /** The page, short enough to type into a browser. */
    val address: String,
    /**
     * What seals everything the phone sends, in groups for typing by hand. [qr] carries it too, so
     * a phone that scans has nothing to type. It never travels over the network.
     */
    val code: String,
    /** [address] and [code] together. */
    val qr: QrCode,
    val closesAtMs: Long,
    /** How many things have arrived and wait in [Engine.takeReceived]. */
    val waiting: Int,
) {
    /** Without the code, so that a log line that holds a handoff does not hold what seals it. */
    override fun toString(): String = "Handoff(address=$address, closesAtMs=$closesAtMs, waiting=$waiting)"
}

/** Why a handoff is no longer open. */
enum class HandoffEnd {
    /** Closed from this device. */
    CLOSED,

    /** Its ten minutes are over. */
    EXPIRED,

    /** It answered as many requests as one handoff answers, which no person reaches by hand. */
    USED_UP,

    /** Tern left the screen. */
    LEFT_SCREEN,
}

/** How Orbot is doing, as far as Orbot says. */
enum class OrbotState {
    /** Not asked yet, or Orbot has not answered. */
    UNKNOWN,
    NOT_INSTALLED,
    OFF,
    STARTING,
    ON,
}

sealed interface Received {
    data class Link(val text: String) : Received

    class ExportFile(val name: String, val bytes: ByteArray) : Received
}

/** What the Update all button above the list takes in. A first install still passes every check, and Android asks as usual. */
enum class UpdateAllMode {
    /** The updates of installed apps. */
    UPDATES,

    /** Those, and the apps that are not installed yet. */
    ALL,

    /** No button. */
    NONE,
}

/** How far a check of the whole list has got: [done] of [total] apps. */
data class CheckCount(val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
