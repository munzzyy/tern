package io.github.munzzyy.stamp.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
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
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.Detection
import io.github.munzzyy.stamp.engine.Engine

/** What the user asked for while Android had not yet allowed Stamp to install apps. */
sealed interface Wanted {
    data class One(val appId: String, val releaseId: String? = null, val assetUrl: String? = null) : Wanted

    data object AllUpdates : Wanted
}

/** What was wanted and when, as it is kept while Stamp may be closed. */
data class Kept(val wanted: List<Wanted>, val atMs: Long)

interface WishStore {
    fun read(): Kept?

    fun write(kept: Kept)

    fun clear()

    object None : WishStore {
        override fun read(): Kept? = null

        override fun write(kept: Kept) = Unit

        override fun clear() = Unit
    }
}

/**
 * Android asks once before one app may install another. An install started before that answer
 * ends as cancelled even when the user then says yes, so the question is asked first and what was
 * wanted is kept until the user comes back. Android 11 closes the app when the answer is yes, so
 * what was wanted is also kept in [store] and carried over to the next start.
 */
class InstallGuard(
    private val allowed: () -> Boolean,
    private val store: WishStore = WishStore.None,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    var waiting by mutableStateOf<List<Wanted>>(emptyList())
        private set

    private var carried: List<Wanted> = emptyList()

    init {
        val kept = store.read()
        if (kept != null) {
            when {
                nowMs() - kept.atMs !in 0..KEEP_MS -> store.clear()
                allowed() -> {
                    carried = kept.wanted
                    store.clear()
                }
                else -> waiting = kept.wanted
            }
        }
    }

    /** What was wanted before Stamp was closed, now that Android allows it. Given out once. */
    fun carriedOver(): List<Wanted> = carried.also { carried = emptyList() }

    /** True when [wanted] may start now. Otherwise it is kept and the question is shown. */
    fun admit(wanted: Wanted): Boolean {
        if (allowed()) return true
        if (wanted !in waiting) waiting = waiting + wanted
        store.write(Kept(waiting, nowMs()))
        return false
    }

    /**
     * The user is back from the settings. Returns what to start: everything kept when the answer
     * was yes, nothing otherwise. [anyway] is for a device with no such settings screen, where
     * Android has to ask in its own way.
     */
    fun release(anyway: Boolean = false): List<Wanted> {
        val kept = waiting
        forget()
        return if (anyway || allowed()) kept else emptyList()
    }

    fun forget() {
        waiting = emptyList()
        store.clear()
    }

    companion object {
        const val KEEP_MS = 15 * 60 * 1000L
    }
}

/** One wish a line, fields apart by a tab. A wish that holds a tab or a line break is not kept. */
object WishText {
    private const val ALL = "all"
    private const val ONE = "one"
    private const val MAX_WISHES = 50
    private const val MAX_FIELD = 4096

    fun encode(kept: Kept): String = buildString {
        append(kept.atMs)
        for (wish in kept.wanted.take(MAX_WISHES)) {
            val line = when (wish) {
                Wanted.AllUpdates -> ALL
                is Wanted.One -> listOf(ONE, wish.appId, wish.releaseId.orEmpty(), wish.assetUrl.orEmpty()).takeIf { it.all(::plain) }?.joinToString("\t")
            } ?: continue
            append('\n').append(line)
        }
    }

    fun decode(text: String?): Kept? {
        val lines = text?.split('\n') ?: return null
        val at = lines.firstOrNull()?.toLongOrNull() ?: return null
        val wanted = lines.drop(1).take(MAX_WISHES).mapNotNull { line ->
            val fields = line.split('\t')
            when {
                line == ALL -> Wanted.AllUpdates
                fields.size == 4 && fields[0] == ONE && fields[1].isNotEmpty() && fields.all(::plain) ->
                    Wanted.One(fields[1], fields[2].ifEmpty { null }, fields[3].ifEmpty { null })
                else -> null
            }
        }
        return Kept(wanted.distinct(), at).takeIf { wanted.isNotEmpty() }
    }

    private fun plain(field: String): Boolean = field.length <= MAX_FIELD && field.none { it.isISOControl() }
}

class PreferenceWishStore(context: Context) : WishStore {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): Kept? = WishText.decode(preferences.getString(KEY, null))

    override fun write(kept: Kept) {
        preferences.edit().putString(KEY, WishText.encode(kept)).apply()
    }

    override fun clear() {
        if (preferences.contains(KEY)) preferences.edit().remove(KEY).apply()
    }

    companion object {
        const val FILE = "install-wish"
        private const val KEY = "kept"
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
class GuardHolder(engine: Engine, store: WishStore = WishStore.None) : ViewModel() {
    val guarded = GuardedEngine(engine, InstallGuard(engine::mayInstall, store))
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
