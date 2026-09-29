package io.github.munzzyy.stamp.enginetest

import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.model.UpdateMode
import io.github.munzzyy.stamp.core.net.HttpClient
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.data.AppState
import io.github.munzzyy.stamp.data.StoredApp
import io.github.munzzyy.stamp.engine.AppRow
import io.github.munzzyy.stamp.engine.real.RealEngine
import io.github.munzzyy.stamp.install.Gate
import io.github.munzzyy.stamp.install.Installer
import io.github.munzzyy.stamp.install.SessionInstaller
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

/** Installs as the shell user, so Stamp is not the installer of record. */
fun shellInstall(bytes: ByteArray): String = shell("pm install -r -d -S ${bytes.size}", bytes)

fun uninstallFixture() {
    shell("pm uninstall $PKG")
}

fun installedVersionCode(): Long? = try {
    targetContext.packageManager.getPackageInfo(PKG, 0).longVersionCode
} catch (_: android.content.pm.PackageManager.NameNotFoundException) {
    null
}

/**
 * Starts a test on a device no earlier test has left its mark on: a run that stopped halfway
 * leaves its session and its notification behind, and the next run would tap the stale one.
 * Android also refuses a second silent update of one package by one installer within 30 seconds,
 * and every test here updates the same fixture, so that limit is lifted for this installer.
 */
fun prepareDevice() {
    val own = targetContext.packageName
    shell("appops set $own REQUEST_INSTALL_PACKAGES allow")
    shell("pm grant $own android.permission.POST_NOTIFICATIONS")
    shell("pm set-silent-updates-policy --reset")
    shell("pm set-silent-updates-policy --allow-unlimited-silent-updates $own")
    val installer = targetContext.packageManager.packageInstaller
    for (session in installer.mySessions) runCatching { installer.abandonSession(session.sessionId) }
    // Android takes a moment to let a session go.
    waitUntil(10_000, "the leftover sessions to be gone") { installer.mySessions.isEmpty() }
    targetContext.getSystemService(NotificationManager::class.java).cancelAll()
}

/** Without the marks that hold a name or a hash left to right, which the eye does not see either. */
fun plain(text: String?): String? = text?.filterNot { it in '\u2066'..'\u2069' }

/** Puts Android's limit on silent updates back, holding it for [seconds] after each one. */
fun throttleSilentUpdates(seconds: Int) {
    shell("pm set-silent-updates-policy --reset")
    shell("pm set-silent-updates-policy --throttle-time $seconds")
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

    /** True once an install has been answered and everything that follows it has been written and drawn. */
    fun settledOn(id: String, versionCode: Long): Boolean {
        val state = state(id)
        val row = row(id)
        return installedVersionCode() == versionCode && state.pending == null && state.record?.versionCode == versionCode &&
            row.progress == null && row.status == io.github.munzzyy.stamp.engine.AppStatus.UP_TO_DATE && row.installed?.versionCode == versionCode
    }

    /** Confirms the system installer's dialog for [id], and says what the engine held when none came. */
    fun confirm(id: String): String = try {
        Prompt.confirm()
    } catch (e: AssertionError) {
        throw AssertionError("${e.message}; ${describe(id)}; on screen: ${Prompt.onScreen()}", e)
    }

    /** What a failed assertion should say about the app, so a failure on a device explains itself. */
    fun describe(id: String): String {
        val row = row(id)
        val state = state(id)
        val events = engine.store.events().filter { it.appId == id }.joinToString(" | ") { "${it.kind}: ${it.message}" }
        return "status=${row.status} mode=${row.config.updates} silent=${row.silentUpdate} problem=${row.problem} progress=${row.progress} " +
            "latest=${row.latest?.id} installed=${row.installed?.versionCode} pending=${state.pending} installProblem=${state.installProblem} " +
            "block=${state.block} events=[$events]"
    }

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

    fun onScreen(): String = device.findObjects(By.textContains("")).mapNotNull { it.text?.takeIf(String::isNotBlank) }.take(40).joinToString(" / ")

    fun appears(timeoutMs: Long): Boolean = device.wait(Until.hasObject(By.pkg(INSTALLER).text(CONFIRM)), timeoutMs) == true

    fun dismiss() {
        if (visible()) device.pressBack()
    }
}
