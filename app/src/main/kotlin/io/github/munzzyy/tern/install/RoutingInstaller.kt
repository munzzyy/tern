package io.github.munzzyy.tern.install

import android.content.Intent
import io.github.munzzyy.tern.engine.InstallerMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends each install to the installer the person chose, and every later step of a session to the
 * installer that made it, so a change of setting in between cannot strand one.
 */
class RoutingInstaller(
    private val system: Installer,
    private val others: Map<InstallerMode, Installer>,
    private val mode: () -> InstallerMode,
) : Installer {
    private val owners = ConcurrentHashMap<Int, Installer>()
    private val all: List<Installer> get() = listOf(system) + others.values

    /** The installer the next install goes to. */
    fun current(): Installer = others[mode()] ?: system

    override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
        val installer = current()
        return installer.prepare(packageName, apks, claimUpdateOwnership).also { owners[it] = installer }
    }

    override fun commit(appId: String, sessionId: Int) = ownerOf(sessionId).commit(appId, sessionId)

    override fun abandon(sessionId: Int) {
        ownerOf(sessionId).abandon(sessionId)
        owners.remove(sessionId)
    }

    override fun liveSessionIds(): Set<Int> = all.flatMapTo(HashSet()) { it.liveSessionIds() }

    override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int = all.sumOf { it.abandonOlderThan(maxAgeMs, nowMs) }

    override fun confirmation(sessionId: Int): Intent? = ownerOf(sessionId).confirmation(sessionId)

    override val placesObb: Boolean get() = current().placesObb

    override fun placeObb(sessionId: Int, packageName: String, files: List<ObbFile>): ObbOutcome = ownerOf(sessionId).placeObb(sessionId, packageName, files)

    /** After a restart nobody remembers who made a session: the one that still lists it made it. */
    private fun ownerOf(sessionId: Int): Installer =
        owners[sessionId] ?: all.firstOrNull { sessionId in it.liveSessionIds() } ?: system
}
