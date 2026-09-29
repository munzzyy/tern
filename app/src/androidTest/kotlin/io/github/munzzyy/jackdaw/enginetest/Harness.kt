package io.github.munzzyy.jackdaw.enginetest

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.model.UpdateMode
import io.github.munzzyy.jackdaw.core.net.HttpClient
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.data.AppState
import io.github.munzzyy.jackdaw.data.StoredApp
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.real.RealEngine
import io.github.munzzyy.jackdaw.install.Gate
import io.github.munzzyy.jackdaw.install.Installer
import io.github.munzzyy.jackdaw.install.SessionInstaller
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking

const val PKG = "com.example.app"

val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

fun asset(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

/** Runs a shell command as the shell user and returns what it printed. */
fun shell(command: String, stdin: ByteArray? = null): String {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val fds = automation.executeShellCommandRwe(command)
    if (stdin != null) ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(stdin) } else fds[1].close()
    val out = ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { String(it.readBytes()) }
    val err = ParcelFileDescriptor.AutoCloseInputStream(fds[2]).use { String(it.readBytes()) }
    return out + err
}

/** Installs as the shell user, so Jackdaw is not the installer of record. */
fun shellInstall(bytes: ByteArray): String = shell("pm install -r -d -S ${bytes.size}", bytes)

fun uninstallFixture() {
    shell("pm uninstall $PKG")
}

fun installedVersionCode(): Long? = try {
    targetContext.packageManager.getPackageInfo(PKG, 0).longVersionCode
} catch (_: android.content.pm.PackageManager.NameNotFoundException) {
    null
}

fun grantInstallPermissions() {
    val own = targetContext.packageName
    shell("appops set $own REQUEST_INSTALL_PACKAGES allow")
    shell("pm grant $own android.permission.POST_NOTIFICATIONS")
}

fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(200)
    }
    throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
}

/** Wraps the real installer and counts what reaches it. */
class CountingInstaller(private val real: Installer) : Installer by real {
    val prepared = AtomicInteger()
    val committed = AtomicInteger()
    val lastFiles = mutableListOf<File>()

    override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
        prepared.incrementAndGet()
        synchronized(lastFiles) {
            lastFiles.clear()
            lastFiles.addAll(apks)
        }
        return real.prepare(packageName, apks, claimUpdateOwnership)
    }

    override fun commit(appId: String, sessionId: Int) {
        committed.incrementAndGet()
        real.commit(appId, sessionId)
    }
}

class Harness(name: String, gate: Gate? = null, installer: Installer? = null, http: HttpClient? = null, private val freshPrefs: Boolean = true, private val keepPrefs: Boolean = false) : Closeable {
    val forge = FakeForge()
    val installer = CountingInstaller(installer ?: SessionInstaller(targetContext))
    private val store = "enginetest-$name.db"
    private val prefs = "enginetest-$name-"
    private val downloads = File(targetContext.filesDir, "enginetest-$name")

    init {
        targetContext.deleteDatabase(store)
        downloads.deleteRecursively()
        if (freshPrefs) {
            targetContext.deleteSharedPreferences("${prefs}settings")
            targetContext.deleteSharedPreferences("${prefs}tokens")
        }
    }

    val engine = RealEngine(
        targetContext, http ?: forge, storeName = store, prefsPrefix = prefs,
        installer = this.installer, gate = gate, downloadsDir = downloads,
    )

    init {
        runBlocking { engine.saveSettings(engine.settings.value.copy(checkEveryHours = 0)) }
    }

    fun addFixture(mode: UpdateMode = UpdateMode.NOTIFY, packageName: String? = PKG, id: String = "fixture"): String {
        val config = AppConfig(id = id, source = SourceSpec(SourceTypes.FORGEJO, FakeForge.PROJECT), name = "Fixture", packageName = packageName, updates = mode)
        engine.store.putApp(config, AppState())
        runBlocking { engine.ready() }
        engine.stored[id] = StoredApp(config, AppState())
        engine.publish()
        return id
    }

    fun row(id: String): AppRow = engine.apps.value.first { it.id == id }

    fun state(id: String): AppState = engine.store.app(id)!!.state

    fun eventsFor(id: String) = engine.events.value.filter { it.appId == id }

    override fun close() {
        engine.close()
        targetContext.deleteDatabase(store)
        if (!keepPrefs) {
            targetContext.deleteSharedPreferences("${prefs}settings")
            targetContext.deleteSharedPreferences("${prefs}tokens")
        }
        downloads.deleteRecursively()
    }
}

object Prompt {
    private val INSTALLER = Pattern.compile(".*packageinstaller.*")
    private val CONFIRM = Pattern.compile("(?i)^(install|update|reinstall)$")

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Confirms the system installer's dialog, opening it from the notification when it was posted there. Returns how it was found. */
    fun confirm(timeoutMs: Long = 30_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            device.wait(Until.findObject(By.pkg(INSTALLER).text(CONFIRM)), 2_000)?.let {
                it.click()
                return "dialog"
            }
            device.openNotification()
            val tap = device.wait(Until.findObject(By.textStartsWith("Tap to finish installing")), 2_000)
            if (tap != null) {
                tap.click()
                device.wait(Until.findObject(By.pkg(INSTALLER).text(CONFIRM)), 10_000)?.let {
                    it.click()
                    return "notification"
                }
            } else {
                device.pressBack()
            }
        }
        throw AssertionError("No install confirmation appeared")
    }

    fun visible(): Boolean = device.hasObject(By.pkg(INSTALLER))

    fun appears(timeoutMs: Long): Boolean = device.wait(Until.hasObject(By.pkg(INSTALLER).text(CONFIRM)), timeoutMs) == true

    fun dismiss() {
        if (visible()) device.pressBack()
    }
}
