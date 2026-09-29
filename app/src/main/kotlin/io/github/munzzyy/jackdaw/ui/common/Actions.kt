package io.github.munzzyy.jackdaw.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.ui.LocalActionScope
import io.github.munzzyy.jackdaw.ui.LocalSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Runs engine calls from a click; a failure becomes a snackbar instead of a crash. */
class Actions(private val scope: CoroutineScope, private val snackbar: SnackbarHostState, private val failed: String) {
    fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                snackbar.showSnackbar(failed)
            }
        }
    }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
}

/** Reopens Android's confirmation; when nothing waits any more, a check makes the row tell the truth again. */
fun confirmInstall(engine: Engine, appId: String, actions: Actions) {
    if (!engine.resumeInstall(appId)) actions.run { engine.check(appId) }
}

@Composable
fun rememberActions(): Actions {
    val scope = LocalActionScope.current
    val snackbar = LocalSnackbar.current
    val failed = stringResource(R.string.action_failed)
    return remember(scope, snackbar, failed) { Actions(scope, snackbar, failed) }
}
