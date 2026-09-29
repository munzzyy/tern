package io.github.munzzyy.jackdaw.engine

import io.github.munzzyy.jackdaw.core.engine.InstalledApp
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.model.UpdateMode

enum class AppStatus {
    /** Never checked. */
    UNKNOWN,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    NOT_INSTALLED,

    /** Track-only app with a release the user has not seen yet. */
    NEW_RELEASE,

    /** A release exists but Jackdaw refuses it; see [AppRow.problem]. */
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
    SIGNER_MISMATCH,
    PACKAGE_MISMATCH,
    DOWNGRADE,
    INSTALL_FAILED,
    STORAGE,
    UNSUPPORTED,
}

data class Problem(
    val kind: ProblemKind,
    /** Plain sentence for the user, already localized. */
    val message: String,
    /** When a retry can succeed, for rate limits. */
    val retryAtMs: Long? = null,
)

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
    /** False while the certificates were only read from the file's header; true once Android verified the downloaded file. */
    val signersVerified: Boolean,
    val signerState: SignerState,
    val checksum: ChecksumState,
    /** Where the checksum came from, for example "GitHub release digest" or "signed repository index". */
    val checksumSource: String?,
    /** Permissions the offered version asks for that the installed one does not. */
    val newPermissions: List<String>,
)

enum class Phase { QUEUED, DOWNLOADING, VERIFYING, INSTALLING, WAITING_FOR_USER }

data class Progress(val phase: Phase, val bytesDone: Long = 0, val bytesTotal: Long? = null) {
    val fraction: Float? get() = bytesTotal?.takeIf { it > 0 }?.let { (bytesDone.toFloat() / it).coerceIn(0f, 1f) }
}

data class FileChoice(
    val asset: Asset,
    /** Short plain phrases that explain the ranking, such as "matches this device (arm64-v8a)". */
    val reasons: List<String>,
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
) {
    val id: String get() = config.id
}

enum class EventKind { ADDED, REMOVED, IMPORTED, UPDATE_FOUND, DOWNLOADED, VERIFIED, INSTALLED, BLOCKED, FAILED, CHECK_FAILED }

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
    ) : Detection

    /** The text was not a link, so it was used as a search. */
    data class Results(val query: String, val hits: List<SearchHit>) : Detection

    data class Failed(val problem: Problem) : Detection
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ProxyMode { NONE, ORBOT, CUSTOM }

data class Settings(
    /** 0 turns background checks off. */
    val checkEveryHours: Int = 6,
    val onlyOnUnmetered: Boolean = false,
    val onlyWhileCharging: Boolean = false,
    val defaultUpdateMode: UpdateMode = UpdateMode.NOTIFY,
    val includePrereleasesByDefault: Boolean = false,
    val minAgeDaysByDefault: Int = 0,
    val notifyUpdates: Boolean = true,
    val notifyInstalled: Boolean = true,
    val notifyFailures: Boolean = false,
    val keepInstallers: Boolean = false,
    /** Ask Android to make Jackdaw the update owner of what it installs (Android 14 and later). */
    val claimUpdateOwnership: Boolean = false,
    val openObtainiumLinks: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val proxy: ProxyMode = ProxyMode.NONE,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 9050,
)

data class ImportSummary(
    val added: Int,
    val alreadyPresent: Int,
    /** Name and the reason it could not be brought over. */
    val skipped: List<Pair<String, String>>,
)
