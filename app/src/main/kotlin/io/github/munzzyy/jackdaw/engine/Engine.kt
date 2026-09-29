package io.github.munzzyy.jackdaw.engine

import android.graphics.Bitmap
import android.net.Uri
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.Release
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

    /** False while the device has no working internet connection. */
    val online: StateFlow<Boolean>

    /** Looks at what the user typed, pasted or shared: a link is resolved, anything else is searched. */
    suspend fun detect(input: String): Detection

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
     * Repositories [user] has starred, at most 300, for the user to pick from. Nothing is added.
     * Throws [ProblemException] when the name is not valid or the list cannot be had.
     */
    suspend fun starredBy(user: String): List<SearchHit>

    /** Returns how many apps were written. Tokens are never exported. */
    suspend fun exportTo(uri: Uri): Int

    suspend fun clearEvents()

    /** Icon of the installed app or of its downloaded file; null when there is neither. */
    suspend fun icon(row: AppRow, sizePx: Int): Bitmap?
}
