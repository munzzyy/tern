package io.github.munzzyy.tern.engine.real

import android.content.pm.PackageManager
import io.github.munzzyy.tern.engine.InstallerChoice
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.InstallerReadiness
import io.github.munzzyy.tern.install.OtherAppInstaller
import io.github.munzzyy.tern.install.RootShell
import io.github.munzzyy.tern.install.RoutingInstaller
import io.github.munzzyy.tern.install.SessionInstaller
import io.github.munzzyy.tern.install.ShellInstaller
import io.github.munzzyy.tern.install.ShizukuShell
import io.github.munzzyy.tern.install.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Which installer an install goes to. The one the person chose, while it can be used; Android's own
 * otherwise, so an install never waits on a Shizuku that is not running or a root grant that was
 * taken back. Whatever installs, the file passed the gate first.
 */
internal class Installers(private val e: RealEngine) {
    /** Unknown until su has been asked once; asking may show the root manager's own question. */
    @Volatile private var rootGranted: Boolean? = null

    private val _readiness = MutableStateFlow(InstallerReadiness.READY)
    val readiness: StateFlow<InstallerReadiness> get() = _readiness.asStateFlow()

    val routing = RoutingInstaller(
        system = SessionInstaller(e.context),
        others = mapOf(
            InstallerMode.SHIZUKU to ShellInstaller.of(e.context, ShizukuShell(), ::recordedInstaller),
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

    /** Asks again, su included. Runs in the background; the answer arrives through [readiness]. */
    fun recheck() {
        e.scope.launch(Dispatchers.IO) {
            if (e.settings.value.installer == InstallerMode.ROOT) rootGranted = RootShell().ready()
            refresh()
        }
    }

    private fun refresh() {
        _readiness.value = readinessNow()
        e.publish()
    }

    /** The installer the next install goes to. Never blocks: root counts as granted only once su said so. */
    fun effective(): InstallerMode {
        val chosen = e.settings.value.installer
        return if (chosen == InstallerMode.SYSTEM || readinessNow() == InstallerReadiness.READY) chosen else InstallerMode.SYSTEM
    }

    private fun readinessNow(): InstallerReadiness = when (e.settings.value.installer) {
        InstallerMode.SYSTEM -> InstallerReadiness.READY
        InstallerMode.SHIZUKU -> when (ShizukuShell.state()) {
            ShizukuState.READY -> InstallerReadiness.READY
            ShizukuState.NOT_RUNNING -> InstallerReadiness.SHIZUKU_NOT_RUNNING
            ShizukuState.TOO_OLD -> InstallerReadiness.SHIZUKU_TOO_OLD
            ShizukuState.NOT_ALLOWED -> InstallerReadiness.SHIZUKU_NOT_ALLOWED
        }
        InstallerMode.ROOT -> if (rootGranted == true) InstallerReadiness.READY else InstallerReadiness.NO_ROOT
        InstallerMode.OTHER_APP -> if (otherApp() != null) InstallerReadiness.READY else InstallerReadiness.NO_OTHER_APP
    }

    fun mayInstall(): Boolean = if (effective() == InstallerMode.SYSTEM) e.device.mayInstall() else true

    fun mayInstallUnattended(): Boolean = when (effective()) {
        InstallerMode.SYSTEM -> e.device.mayInstall()
        InstallerMode.SHIZUKU, InstallerMode.ROOT -> true
        InstallerMode.OTHER_APP -> false
    }

    /** Whether updates install without a prompt, when the installer decides that; null when Android's own rules do. */
    fun silent(): Boolean? = when (effective()) {
        InstallerMode.SHIZUKU, InstallerMode.ROOT -> true
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
    }
}
