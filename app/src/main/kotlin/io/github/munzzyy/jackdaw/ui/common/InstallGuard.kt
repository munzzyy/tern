package io.github.munzzyy.jackdaw.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.engine.Engine

/** What the user asked for while Android had not yet allowed Jackdaw to install apps. */
sealed interface Wanted {
    data class One(val appId: String, val releaseId: String? = null, val assetUrl: String? = null) : Wanted

    data object AllUpdates : Wanted
}

/**
 * Android asks once before one app may install another. An install started before that answer
 * ends as cancelled even when the user then says yes, so the question is asked first and what was
 * wanted is kept until the user comes back.
 */
class InstallGuard(private val allowed: () -> Boolean) {
    var waiting by mutableStateOf<List<Wanted>>(emptyList())
        private set

    /** True when [wanted] may start now. Otherwise it is kept and the question is shown. */
    fun admit(wanted: Wanted): Boolean {
        if (allowed()) return true
        if (wanted !in waiting) waiting = waiting + wanted
        return false
    }

    /**
     * The user is back from the settings. Returns what to start: everything kept when the answer
     * was yes, nothing otherwise. [anyway] is for a device with no such settings screen, where
     * Android has to ask in its own way.
     */
    fun release(anyway: Boolean = false): List<Wanted> {
        val kept = waiting
        waiting = emptyList()
        return if (anyway || allowed()) kept else emptyList()
    }

    fun forget() {
        waiting = emptyList()
    }
}

/** The engine as the screens see it: every way of starting an install passes the guard first. */
class GuardedEngine(private val real: Engine, val guard: InstallGuard) : Engine by real {
    override fun install(appId: String, releaseId: String?, assetUrl: String?) {
        if (guard.admit(Wanted.One(appId, releaseId, assetUrl))) real.install(appId, releaseId, assetUrl)
    }

    override fun installAllUpdates() {
        if (guard.admit(Wanted.AllUpdates)) real.installAllUpdates()
    }

    override suspend fun add(found: Detection.Found, install: Boolean): String {
        if (!install || real.mayInstall()) return real.add(found, install)
        val id = real.add(found, install = false)
        guard.admit(Wanted.One(id))
        return id
    }

    fun start(wanted: List<Wanted>) {
        for (one in wanted) {
            when (one) {
                Wanted.AllUpdates -> real.installAllUpdates()
                is Wanted.One -> real.install(one.appId, one.releaseId, one.assetUrl)
            }
        }
    }
}

/** Kept with the screens' own view models, which hold the guarded engine across a rotation. */
class GuardHolder(engine: Engine) : ViewModel() {
    val guarded = GuardedEngine(engine, InstallGuard(engine::mayInstall))
}

@Composable
fun InstallPermissionDialog(engine: GuardedEngine) {
    val guard = engine.guard
    if (guard.waiting.isEmpty()) return
    val context = LocalContext.current
    val start = engine::start
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { start(guard.release()) }
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = guard::forget,
        title = { Text(stringResource(R.string.install_permission_title)) },
        text = { Text(stringResource(R.string.install_permission_body)) },
        confirmButton = {
            TextButton(
                modifier = Modifier.focusWhenShown(),
                onClick = {
                    try {
                        settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } catch (_: ActivityNotFoundException) {
                        start(guard.release(anyway = true))
                    }
                },
            ) { Text(stringResource(R.string.install_permission_open)) }
        },
        dismissButton = { TextButton(onClick = guard::forget) { Text(stringResource(R.string.first_skip)) } },
    )
}
