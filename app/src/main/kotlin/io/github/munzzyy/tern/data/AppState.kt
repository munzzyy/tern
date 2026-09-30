package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.engine.InstallRecord
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.Problem

/** An install handed to an installer whose answer has not arrived yet. */
data class PendingInstall(
    val sessionId: Int,
    val packageName: String,
    val releaseId: String,
    val version: String,
    val versionCode: Long,
    val fileSha256: String?,
    val fileSize: Long?,
    val assetUrl: String,
    val startedAtMs: Long,
    val waitingForUser: Boolean = false,
    /**
     * SHA-256 of the certificates the gate verified in the file handed over. An installer that is
     * not Android's own must end with an app signed by exactly these, or the install does not count.
     */
    val signers: List<String> = emptyList(),
)

/** A refusal tied to one file of one release, so a new release clears it by itself. */
data class GateBlock(val releaseId: String, val assetUrl: String, val problem: Problem)

/** A filter that could not be applied, remembered with the exact filters so an edit retries it. */
data class PatternProblem(val filters: String, val message: String)

/** Everything the engine keeps about an app besides its configuration. The installed version is never here. */
data class AppState(
    val releases: List<Release> = emptyList(),
    val lastCheckedMs: Long? = null,
    val checkProblem: Problem? = null,
    val record: InstallRecord? = null,
    val pending: PendingInstall? = null,
    val block: GateBlock? = null,
    val installProblem: Problem? = null,
    val seenReleaseId: String? = null,
    val movedTo: String? = null,
    /** The new home the user chose not to follow; a different one is suggested again. */
    val keptAddress: String? = null,
    val patternProblem: PatternProblem? = null,
    val description: String? = null,
    val announcedReleaseId: String? = null,
    /** Where the source says the icon can be had, the best first. */
    val iconUrls: List<String> = emptyList(),
    /** When the app was added. Older apps have none. */
    val addedAtMs: Long? = null,
)
