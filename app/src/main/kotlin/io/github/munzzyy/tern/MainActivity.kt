package io.github.munzzyy.tern

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings.Global
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
import io.github.munzzyy.tern.ui.BackStack
import io.github.munzzyy.tern.ui.EXTRA_SCENARIO
import io.github.munzzyy.tern.ui.TernApp
import io.github.munzzyy.tern.ui.SCENARIO_FIRST_RUN
import io.github.munzzyy.tern.ui.Scenarios
import io.github.munzzyy.tern.ui.incomingAddInput
import io.github.munzzyy.tern.ui.theme.TernTheme
import io.github.munzzyy.tern.ui.theme.isDark

private const val PREFS = "ui"
private const val KEY_FIRST_RUN_DONE = "first_run_done"

private data class Incoming(val text: String, val nonce: Long)

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Incoming?>(null)
    private var firstRunDone by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        firstRunDone = prefs().getBoolean(KEY_FIRST_RUN_DONE, false)
        applyScenario(intent)
        if (savedInstanceState == null) receive(intent)

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
                TernApp(
                    engine = engine,
                    stack = stack,
                    firstRunDone = firstRunDone,
                    onFirstRunDone = ::finishFirstRun,
                    reducedMotion = animationsOff(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScenario(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
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

    private fun applyScenario(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val name = try {
            intent.getStringExtra(EXTRA_SCENARIO)
        } catch (_: RuntimeException) {
            null
        } ?: return
        (engine as? Scenarios)?.loadScenario(name)
        firstRunDone = name != SCENARIO_FIRST_RUN
        prefs().edit().putBoolean(KEY_FIRST_RUN_DONE, firstRunDone).apply()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun animationsOff(): Boolean =
        Global.getFloat(contentResolver, Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
