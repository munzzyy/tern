package io.github.munzzyy.tern.widget

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine
import io.github.munzzyy.tern.engine.real.RealEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A tile for the quick settings: it says how many updates there are, and a tap checks every app.
 * A check only reads the sources; nothing is installed from here.
 */
class CheckTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val watching = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = watching
        val engine = applicationContext.engine
        watching.launch {
            combine(engine.apps, engine.checkingAll) { rows, checking -> WidgetState.of(rows, checking) }.collect(::draw)
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val engine = applicationContext.engine
        if (engine.checkingAll.value) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                (engine as? RealEngine)?.ready()
                engine.check(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The tile's check did not run: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun draw(state: WidgetState) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_check)
        tile.subtitle = when {
            state.checking -> getString(R.string.tile_checking)
            state.updates == 0 -> getString(R.string.tile_up_to_date)
            else -> resources.getQuantityString(R.plurals.widget_updates, state.updates, state.updates)
        }
        tile.state = if (state.checking || state.updates > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private companion object {
        const val TAG = "TernTile"
    }
}
