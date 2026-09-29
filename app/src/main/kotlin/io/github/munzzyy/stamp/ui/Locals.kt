package io.github.munzzyy.stamp.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.munzzyy.stamp.engine.Engine
import kotlinx.coroutines.CoroutineScope

val LocalEngine = staticCompositionLocalOf<Engine> { error("No engine provided") }

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** False while the engine reports no working internet connection. */
val LocalOnline = staticCompositionLocalOf { true }

/** True when the system asks for no animations, so transitions snap instead. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Outlives single rows and dialogs, so an action started from one finishes after it leaves the screen. */
val LocalActionScope = staticCompositionLocalOf<CoroutineScope> { error("No action scope provided") }
