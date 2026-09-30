package io.github.munzzyy.tern.engine.real

import android.content.pm.PackageManager
import io.github.munzzyy.tern.engine.InstallerChoice
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.InstallerReadiness
import io.github.munzzyy.tern.install.Dhizuku
import io.github.munzzyy.tern.install.DhizukuInstaller
import io.github.munzzyy.tern.install.DhizukuState
import io.github.munzzyy.tern.install.OtherAppInstaller
import io.github.munzzyy.tern.install.RootShell
import io.github.munzzyy.tern.install.RoutingInstaller
import io.github.munzzyy.tern.install.SessionInstaller
import io.github.munzzyy.tern.install.ShellInstaller
import io.github.munzzyy.tern.install.ShizukuShell
import io.github.munzzyy.tern.install.ShizukuState
import io.github.munzzyy.tern.log.TernLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/**
 * Which installer an install goes to. The one the person chose, while it can be used; Android's own
 * otherwise, so an install never waits on a Shizuku that is not running or a root grant that was
 * taken back. Dhizuku alone keeps its installs: until it can be used, each one stops and says why.
 * Whatever installs, the file passed the gate first.
 */
internal class Installers(private val e: RealEngine) {
    /** Unknown until su has been asked once; asking may show the root manager's own question. */
    @Volatile private var rootGranted: Boolean? = null

    /** Unknown until Dhizuku has been asked once, which is done in the background. */
    @Volatile private var dhizukuState: DhizukuState? = null

    private val dhizuku = Dhizuku(e.context) { recheck() }
    private val dhizukuInstaller = DhizukuInstaller(e.context, dhizuku, { e.context.getString(DhizukuInstaller.problemWords(it)) })

    private val _readiness = MutableStateFlow(InstallerReadiness.READY)
    val readiness: StateFlow<InstallerReadiness> get() = _readiness.asStateFlow()

    val routing = RoutingInstaller(
        system = SessionInstaller(e.context),
        others = mapOf(
            InstallerMode.SHIZUKU to ShellInstaller.of(e.context, ShizukuShell(), ::recordedInstaller),
            InstallerMode.DHIZUKU to dhizukuInstaller,
            InstallerMode.ROOT to ShellInstaller.of(e.context, RootShell(), ::recordedInstaller),
            InstallerMode.OTHER_APP to OtherAppInstaller(e.context, ::otherApp) { e.settings.value.otherInstallerActivity },
        ),
        mode = ::effective,
    )

    private val shizukuChanged = { refresh() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> shizukuChanged() }

    /** Listens to Shizuku coming and going, and asks su once when root is the chosen installer. */
    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        recheck()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    /** Asks again, su and Dhizuku included. Runs in the background; the answer arrives through [readiness]. */
    fun recheck() {
        e.scope.launch(Dispatchers.IO) {
            if (e.settings.value.installer == InstallerMode.ROOT) rootGranted = RootShell().ready()
            if (e.settings.value.installer == InstallerMode.DHIZUKU) dhizukuState = dhizukuInstaller.state()
            refresh()
        }
    }

    private fun refresh() {
        _readiness.value = readinessNow()
        e.publish()
    }

    /** The installer the next install goes to. Never blocks: root counts as granted only once su said so. */
    fun effective(): InstallerMode = effectiveInstaller(e.settings.value.installer, readinessNow())

    private fun readinessNow(): InstallerReadiness = when (e.settings.value.installer) {
        InstallerMode.SYSTEM -> InstallerReadiness.READY
        InstallerMode.SHIZUKU -> when (suiThenShizuku()) {
            ShizukuState.READY -> InstallerReadiness.READY
            ShizukuState.NOT_RUNNING -> InstallerReadiness.SHIZUKU_NOT_RUNNING
            ShizukuState.TOO_OLD -> InstallerReadiness.SHIZUKU_TOO_OLD
            ShizukuState.NOT_ALLOWED -> InstallerReadiness.SHIZUKU_NOT_ALLOWED
        }
        InstallerMode.DHIZUKU -> dhizukuState?.readiness ?: InstallerReadiness.DHIZUKU_NOT_ANSWERING
        InstallerMode.ROOT -> if (rootGranted == true) InstallerReadiness.READY else InstallerReadiness.NO_ROOT
        InstallerMode.OTHER_APP -> if (otherApp() != null) InstallerReadiness.READY else InstallerReadiness.NO_OTHER_APP
    }

    @Volatile
    private var suiAsked = false

    /**
     * Shizuku's state, once Sui has been asked for its binder. That asking goes through a hidden
     * part of Android, so it waits until the person chose Shizuku; ShizukuProvider does not do
     * it at start (App.attachBaseContext).
     */
    private fun suiThenShizuku(): ShizukuState {
        if (!suiAsked) {
            suiAsked = true
            try {
                Sui.init(e.context.packageName)
            } catch (ex: RuntimeException) {
                TernLog.w(TAG, "Sui could not be asked: ${ex.javaClass.simpleName}")
            }
        }
        return ShizukuShell.state()
    }

    fun mayInstall(): Boolean = if (effective() == InstallerMode.SYSTEM) e.device.mayInstall() else true

    fun mayInstallUnattended(): Boolean = when (effective()) {
        InstallerMode.SYSTEM -> e.device.mayInstall()
        InstallerMode.SHIZUKU, InstallerMode.DHIZUKU, InstallerMode.ROOT -> true
        InstallerMode.OTHER_APP -> false
    }

    /** Whether updates install without a prompt, when the installer decides that; null when Android's own rules do. */
    fun silent(): Boolean? = when (effective()) {
        InstallerMode.SHIZUKU, InstallerMode.DHIZUKU, InstallerMode.ROOT -> true
        InstallerMode.OTHER_APP -> false
        InstallerMode.SYSTEM -> null
    }

    /** Asks Shizuku to let Tern use it; the answer comes back through [readiness]. False when Shizuku cannot be asked. */
    fun askShizuku(): Boolean = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            false
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            true
        }
    } catch (_: RuntimeException) {
        false
    }

    /** Opens Dhizuku's own question; the answer comes back through [readiness]. False when there is no Dhizuku to ask. */
    fun askDhizuku(): Boolean = dhizuku.ask(::recheck)

    fun choices(): List<InstallerChoice> = OtherAppInstaller.candidates(e.context)

    private fun otherApp(): String? = e.settings.value.otherInstaller?.takeIf(::isInstalled)

    private fun isInstalled(packageName: String): Boolean = try {
        e.context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Google Play when the person asked for that, globally or for the app being installed; Tern otherwise. */
    private fun recordedInstaller(packageName: String): String {
        val forApp = e.stored.values.any { it.config.playInstaller && e.packageOf(it.config) == packageName }
        return if (e.settings.value.playInstaller || forApp) PLAY else e.context.packageName
    }

    private companion object {
        const val PLAY = "com.android.vending"
        const val SHIZUKU_REQUEST = 7301
        const val TAG = "TernInstallers"
    }
}

/**
 * The installer an install goes to: [chosen] while [readiness] allows it, Android's own otherwise.
 * Dhizuku is never left for another installer, so an install stops and says why instead.
 */
internal fun effectiveInstaller(chosen: InstallerMode, readiness: InstallerReadiness): InstallerMode =
    if (chosen == InstallerMode.SYSTEM || chosen == InstallerMode.DHIZUKU || readiness == InstallerReadiness.READY) chosen else InstallerMode.SYSTEM
