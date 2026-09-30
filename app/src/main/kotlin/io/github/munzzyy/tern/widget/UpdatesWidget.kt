package io.github.munzzyy.tern.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.real.RealEngine
import io.github.munzzyy.tern.log.TernLog
import io.github.munzzyy.tern.ui.text.canUpdateNow
import io.github.munzzyy.tern.ui.text.isUpdate
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the widget says: how many apps have an update, how many of them can start now, and when the list was last checked. */
data class WidgetState(val updates: Int, val startable: Int, val checkedAtMs: Long?, val checking: Boolean) {
    companion object {
        fun of(rows: List<AppRow>, checking: Boolean): WidgetState = WidgetState(
            updates = rows.count(::isUpdate),
            startable = rows.count(::canUpdateNow),
            checkedAtMs = rows.mapNotNull { it.lastCheckedMs }.maxOrNull(),
            checking = checking,
        )
    }
}

/**
 * A widget for the home screen: how many updates there are, with a button that checks the list
 * and one that starts every update that can start. Obtainium has none. Tapping the rest opens Tern.
 */
class UpdatesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK, ACTION_UPDATE_ALL -> act(context, intent.action!!)
            else -> super.onReceive(context, intent)
        }
    }

    private fun act(context: Context, action: String) {
        val done = goAsync()
        scope.launch {
            try {
                val engine = context.applicationContext.engine
                (engine as? RealEngine)?.ready()
                if (action == ACTION_CHECK) {
                    refresh(context, checking = true)
                    engine.check(null, CheckCause.WIDGET)
                } else {
                    engine.installAllUpdates()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TernLog.w(TAG, "The widget's button did nothing: ${e.javaClass.simpleName}")
            } finally {
                refresh(context)
                done.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TernWidget"
        private const val ACTION_CHECK = "io.github.munzzyy.tern.widget.CHECK"
        private const val ACTION_UPDATE_ALL = "io.github.munzzyy.tern.widget.UPDATE_ALL"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Draws every widget again from the list as it is now; nothing happens when there is none. */
        fun refresh(context: Context, checking: Boolean? = null) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, UpdatesWidget::class.java))
            if (ids.isEmpty()) return
            draw(context, manager, ids, checking)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, checking: Boolean? = null) {
            val engine = context.applicationContext.engine
            val state = WidgetState.of(engine.apps.value, checking ?: engine.checkingAll.value)
            for (id in ids) manager.updateAppWidget(id, views(context, state))
        }

        private fun views(context: Context, state: WidgetState): RemoteViews {
            val r = context.resources
            val views = RemoteViews(context.packageName, R.layout.widget_updates)
            views.setTextViewText(
                R.id.widget_count,
                if (state.updates == 0) r.getString(R.string.widget_up_to_date) else r.getQuantityString(R.plurals.widget_updates, state.updates, state.updates),
            )
            views.setTextViewText(
                R.id.widget_checked,
                when {
                    state.checking -> r.getString(R.string.tile_checking)
                    state.checkedAtMs == null -> r.getString(R.string.widget_never_checked)
                    else -> r.getString(R.string.widget_checked, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.checkedAtMs)))
                },
            )
            views.setTextViewText(R.id.widget_check_label, r.getString(R.string.widget_check))
            views.setTextViewText(R.id.widget_update_all, r.getString(R.string.action_update_all))
            views.setViewVisibility(R.id.widget_update_all, if (state.startable > 0) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_check, broadcast(context, ACTION_CHECK))
            views.setOnClickPendingIntent(R.id.widget_update_all, broadcast(context, ACTION_UPDATE_ALL))
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { open ->
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
            }
            return views
        }

        private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, UpdatesWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
