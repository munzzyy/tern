package io.github.munzzyy.tern.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.ui.ACTION_ADD
import io.github.munzzyy.tern.ui.ACTION_CHECK
import io.github.munzzyy.tern.ui.ACTION_UPDATE_ALL
import io.github.munzzyy.tern.ui.SHORTCUTS
import io.github.munzzyy.tern.log.TernLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The places outside Tern's own window that show its state: the widget, and the shortcuts of its launcher icon. */
object Surfaces {
    /** Which shortcut an intent came from, so the launcher can learn which ones are used. */
    const val EXTRA_SHORTCUT = "io.github.munzzyy.tern.SHORTCUT"
    private const val TAG = "TernSurfaces"
    private const val QUIET_MS = 500L

    /** Keeps the widget in step with the list for as long as the engine runs, and puts up the shortcuts in the language of the moment. */
    @OptIn(FlowPreview::class)
    fun start(context: Context, engine: Engine) {
        val app = context.applicationContext
        shortcuts(app)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(engine.apps, engine.checkingAll) { rows, checking -> WidgetState.of(rows, checking) }
                .distinctUntilChanged()
                .debounce(QUIET_MS)
                .collect { UpdatesWidget.refresh(app) }
        }
    }

    /** Tells the launcher a shortcut was used, which is how it learns what to offer first. */
    fun used(context: Context, id: String) {
        try {
            context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(id)
        } catch (e: IllegalStateException) {
            TernLog.i(TAG, "The launcher took no report: ${e.message}")
        }
    }

    private fun shortcuts(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        // Through the alias, which only Tern and the launcher acting for it can start.
        val main = ComponentName(context, SHORTCUTS)
        fun shortcut(id: String, short: Int, long: Int, icon: Int, intent: Intent) = ShortcutInfo.Builder(context, id)
            .setShortLabel(context.getString(short))
            .setLongLabel(context.getString(long))
            .setIcon(Icon.createWithResource(context, icon))
            .setIntent(intent.setComponent(main).putExtra(EXTRA_SHORTCUT, id))
            .build()
        val list = listOf(
            shortcut("check", R.string.shortcut_check_short, R.string.shortcut_check_long, R.drawable.ic_shortcut_check, Intent(ACTION_CHECK)),
            shortcut("update_all", R.string.action_update_all, R.string.shortcut_update_all_long, R.drawable.ic_shortcut_update, Intent(ACTION_UPDATE_ALL)),
            shortcut("add", R.string.shortcut_add_short, R.string.shortcut_add_long, R.drawable.ic_shortcut_add, Intent(ACTION_ADD)),
        )
        try {
            manager.dynamicShortcuts = list
            // A shortcut pinned to the home screen keeps its intent until it is updated.
            manager.updateShortcuts(list)
        } catch (e: IllegalStateException) {
            TernLog.i(TAG, "The launcher took no shortcuts: ${e.message}")
        } catch (e: IllegalArgumentException) {
            TernLog.i(TAG, "The launcher took no shortcuts: ${e.message}")
        }
    }
}
