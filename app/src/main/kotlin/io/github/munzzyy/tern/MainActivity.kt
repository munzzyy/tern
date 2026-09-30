package io.github.munzzyy.tern

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings.Global
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.munzzyy.tern.data.AppLanguage
import io.github.munzzyy.tern.data.CrashReport
import io.github.munzzyy.tern.ui.BackStack
import io.github.munzzyy.tern.ui.EXTRA_SCENARIO
import io.github.munzzyy.tern.ui.RefreshLink
import io.github.munzzyy.tern.ui.SCENARIO_FIRST_RUN
import io.github.munzzyy.tern.ui.Scenarios
import io.github.munzzyy.tern.ui.TernApp
import io.github.munzzyy.tern.ui.common.CrashDialog
import io.github.munzzyy.tern.ui.common.ProblemsDialog
import io.github.munzzyy.tern.ui.incomingAddInput
import io.github.munzzyy.tern.ui.refreshLink
import io.github.munzzyy.tern.ui.settings.VerificationNote
import io.github.munzzyy.tern.ui.theme.TernTheme
import io.github.munzzyy.tern.ui.theme.isDark
import io.github.munzzyy.tern.widget.Surfaces
import io.github.munzzyy.tern.work.Notifier
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

private const val PREFS = "ui"
private const val KEY_FIRST_RUN_DONE = "first_run_done"
private const val KEY_VERIFICATION_NOTE_SHOWN = "verification_note_shown"
private const val TAG = "TernMain"
private const val MAX_APP_ID = 64
private const val MAX_PROBLEMS = 50

private data class Incoming(val text: String, val nonce: Long)

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Incoming?>(null)

    /** The app a notification asked to show. */
    private var openApp by mutableStateOf<String?>(null)

    /** The apps whose problems a notification asked to show, by their ids. */
    private var problems by mutableStateOf<List<String>?>(null)
    private var firstRunDone by mutableStateOf(true)

    /** Shown once, on the first start after the first run, so it never stands in the way of that run. */
    private var verificationNote by mutableStateOf(false)

    /** What Tern was doing when it last stopped unexpectedly, until the person has seen it. */
    private var crash by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        firstRunDone = prefs().getBoolean(KEY_FIRST_RUN_DONE, false)
        verificationNote = firstRunDone && !prefs().getBoolean(KEY_VERIFICATION_NOTE_SHOWN, false)
        if (savedInstanceState == null) crash = CrashReport.pending(this)
        applyScenario(intent)
        if (savedInstanceState == null) receive(intent)
        if (savedInstanceState == null && firstRunDone && engine.settings.value.checkOnStart) checkOnStart()

        setContent {
            val settings by engine.settings.collectAsStateWithLifecycle()
            TernTheme(settings) {
                val dark = isDark(settings)
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                val stack = rememberSaveable(saver = listSaver(save = { it.encode() }, restore = { BackStack.decode(it) })) {
                    BackStack(emptyList())
                }
                LaunchedEffect(incoming) {
                    incoming?.let {
                        stack.openAdd(it.text, it.nonce)
                        incoming = null
                    }
                }
                LaunchedEffect(openApp) {
                    openApp?.let { id ->
                        if (engine.apps.value.any { it.id == id }) stack.showDetail(id)
                        openApp = null
                    }
                }
                TernApp(
                    engine = engine,
                    stack = stack,
                    firstRunDone = firstRunDone,
                    onFirstRunDone = ::finishFirstRun,
                    reducedMotion = animationsOff(),
                )
                problems?.let { ids -> ProblemsDialog(engine, ids, onOpen = { openApp = it }, onDismiss = { problems = null }) }
                crash?.let { report -> CrashDialog(report, onDismiss = ::sawCrash) }
                if (crash == null && verificationNote) VerificationNote(onDismiss = ::sawVerificationNote)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScenario(intent)
        receive(intent)
    }

    /** The setting asks for a check of the list each time Tern is opened; turning the screen is not opening it. */
    private fun checkOnStart() {
        lifecycleScope.launch {
            try {
                engine.check(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The check on opening could not run: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun receive(intent: Intent) {
        intent.getStringExtra(Surfaces.EXTRA_SHORTCUT)?.let { Surfaces.used(this, it.take(MAX_APP_ID)) }
        intent.getStringExtra(Notifier.EXTRA_OPEN_APP)?.let { id ->
            openApp = id.take(MAX_APP_ID)
            return
        }
        intent.getStringArrayExtra(Notifier.EXTRA_PROBLEMS)?.let { ids ->
            problems = ids.take(MAX_PROBLEMS).map { it.take(MAX_APP_ID) }
            return
        }
        when (intent.action) {
            Surfaces.ACTION_ADD -> {
                finishFirstRun()
                incoming = Incoming("", System.nanoTime())
                return
            }
            Surfaces.ACTION_UPDATE_ALL -> {
                lifecycleScope.launch { engine.installAllUpdates() }
                return
            }
        }
        if (intent.action == Intent.ACTION_VIEW) {
            val refresh = try {
                refreshLink(intent.dataString)
            } catch (_: RuntimeException) {
                null
            }
            if (refresh != null) {
                lifecycleScope.launch {
                    val id = when (refresh) {
                        RefreshLink.All -> null
                        is RefreshLink.One -> engine.apps.value.firstOrNull {
                            it.config.packageName == refresh.packageName || it.installed?.packageName == refresh.packageName
                        }?.id ?: return@launch
                    }
                    engine.check(id)
                }
                return
            }
        }
        val text = try {
            incomingAddInput(intent.action, intent.dataString, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        } catch (_: RuntimeException) {
            null
        } ?: return
        finishFirstRun()
        incoming = Incoming(text, System.nanoTime())
    }

    private fun finishFirstRun() {
        firstRunDone = true
        prefs().edit().putBoolean(KEY_FIRST_RUN_DONE, true).apply()
    }

    private fun sawCrash() {
        crash = null
        CrashReport.dismiss(this)
    }

    private fun sawVerificationNote() {
        verificationNote = false
        prefs().edit().putBoolean(KEY_VERIFICATION_NOTE_SHOWN, true).apply()
    }

    private fun applyScenario(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val name = try {
            intent.getStringExtra(EXTRA_SCENARIO)
        } catch (_: RuntimeException) {
            null
        } ?: return
        (engine as? Scenarios)?.loadScenario(name)
        verificationNote = false
        crash = null
        firstRunDone = name != SCENARIO_FIRST_RUN
        prefs().edit().putBoolean(KEY_FIRST_RUN_DONE, firstRunDone).apply()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun animationsOff(): Boolean =
        Global.getFloat(contentResolver, Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
