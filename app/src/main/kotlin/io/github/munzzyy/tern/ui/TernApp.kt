package io.github.munzzyy.tern.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.ui.activity.ActivityScreen
import io.github.munzzyy.tern.ui.add.AddScreen
import io.github.munzzyy.tern.ui.common.GuardHolder
import io.github.munzzyy.tern.ui.common.InstallPermissionDialog
import io.github.munzzyy.tern.ui.common.PreferenceWishStore
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.verticalFocusStaysInside
import io.github.munzzyy.tern.ui.common.lacksTouch
import io.github.munzzyy.tern.ui.apps.AppsScreen
import io.github.munzzyy.tern.ui.apps.openHandoff
import io.github.munzzyy.tern.ui.detail.DetailScreen
import io.github.munzzyy.tern.ui.firstrun.FirstRunScreen
import io.github.munzzyy.tern.ui.handoff.HandoffScreen
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.importing.ImportScreen
import io.github.munzzyy.tern.ui.look.LookScreen
import io.github.munzzyy.tern.ui.settings.SettingsScreen
import io.github.munzzyy.tern.ui.text.isUpdate
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlinx.coroutines.CancellationException

const val TAB_LABEL_TAG = "tab_label"

private val RAIL_WIDTH = 600.dp
private val TWO_PANE_WIDTH = 840.dp
private val LIST_PANE_WIDTH = 400.dp
private const val LIST_PANE_SHARE = 0.45f
private const val LARGE_TEXT = 1.5f

/** Whether the list and an app's page fit side by side; with large text each needs more room, so the width asked for grows with the text. */
fun twoPanesFit(width: Dp, fontScale: Float): Boolean = width >= TWO_PANE_WIDTH * (if (fontScale >= LARGE_TEXT) fontScale else 1f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TernApp(
    engine: Engine,
    stack: BackStack,
    firstRunDone: Boolean,
    onFirstRunDone: () -> Unit,
    reducedMotion: Boolean,
    dialogs: @Composable () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val noTouch = remember(configuration) { context.lacksTouch() }
    val guarded = viewModel(key = "install-guard") { GuardHolder(engine, PreferenceWishStore(context)) }.guarded
    LaunchedEffect(guarded) { guarded.start(guarded.guard.carriedOver()) }
    val density = LocalDensity.current
    val room = LocalLook.current.focusRoom * 2
    val scrolling = remember(density, room) { ScrollToShow(with(density) { room.toPx() }) }
    CompositionLocalProvider(
        LocalNoTouch provides noTouch,
        LocalEngine provides guarded,
        LocalSnackbar provides snackbar,
        LocalActionScope provides scope,
        LocalReducedMotion provides reducedMotion,
        LocalBringIntoViewSpec provides scrolling,
    ) {
        val look = LocalLook.current
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .focusHighlight()
                .padding(horizontal = look.edgeHorizontal, vertical = look.edgeVertical),
        ) {
            if (!firstRunDone) {
                FirstRunScreen(
                    onAddFirst = {
                        onFirstRunDone()
                        stack.select(Tab.ADD)
                    },
                    onSkip = onFirstRunDone,
                )
            } else {
                Shell(stack)
            }
            InstallPermissionDialog(guarded)
            dialogs()
        }
    }
}

@Composable
private fun Shell(stack: BackStack) {
    val engine = LocalEngine.current
    val rows by engine.apps.collectAsStateWithLifecycle()
    val online by engine.online.collectAsStateWithLifecycle()
    val settings by engine.settings.collectAsStateWithLifecycle()
    // A television keeps its rail: a bar at the bottom is far from where the remote starts.
    val phoneLayout = settings.phoneLayout && !LocalNoTouch.current
    val updates = remember(rows) { rows.count(::isUpdate) }
    val holder = rememberSaveableStateHolder()
    val reducedMotion = LocalReducedMotion.current
    var backProgress by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }

    PredictiveBackHandler(enabled = stack.canPop) { events ->
        try {
            events.collect {
                backProgress = it.progress
                fromLeft = it.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            val gone = stack.top
            stack.pop()
            holder.removeState(encodeRoute(gone))
        } catch (e: CancellationException) {
            throw e
        } finally {
            backProgress = 0f
        }
    }

    val backModifier = if (reducedMotion) {
        Modifier
    } else {
        Modifier.graphicsLayer {
            val p = backProgress
            val scale = 1f - 0.08f * p
            scaleX = scale
            scaleY = scale
            translationX = (if (fromLeft) 1 else -1) * 24.dp.toPx() * p
        }
    }

    CompositionLocalProvider(LocalOnline provides online) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = !phoneLayout && maxWidth >= RAIL_WIDTH
            val twoPane = !phoneLayout && twoPanesFit(maxWidth, LocalDensity.current.fontScale)
            val current = stack.top
            val select: (Tab) -> Unit = { tab ->
                stack.routes.forEach { if (it != Route.Apps) holder.removeState(encodeRoute(it)) }
                stack.select(tab)
            }
            if (!wide) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        NavigationBar {
                            for (tab in Tab.entries) {
                                NavigationBarItem(
                                    selected = current.tab() == tab,
                                    onClick = { select(tab) },
                                    icon = { TabIcon(tab, updates) },
                                    label = { TabLabel(tab) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .then(backModifier),
                    ) {
                        Pane(stack, holder, current, twoPane = false)
                    }
                }
            } else {
                val railInsets = WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(windowInsets = railInsets) {
                        for (tab in Tab.entries) {
                            NavigationRailItem(
                                selected = current.tab() == tab,
                                onClick = { select(tab) },
                                icon = { TabIcon(tab, updates) },
                                label = { TabLabel(tab) },
                            )
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .consumeWindowInsets(railInsets.only(WindowInsetsSides.Start))
                            .then(backModifier)
                            .verticalFocusStaysInside(),
                    ) {
                        Pane(stack, holder, current, twoPane)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pane(stack: BackStack, holder: SaveableStateHolder, current: Route, twoPane: Boolean) {
    val listState = rememberLazyListState()
    var reopened by remember { mutableIntStateOf(0) }
    val showList = twoPane && (current == Route.Apps || current is Route.Detail)
    if (showList) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val listWidth = minOf(LIST_PANE_WIDTH, maxWidth * LIST_PANE_SHARE)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight().verticalFocusStaysInside()) {
                    holder.SaveableStateProvider("apps") {
                        AppsScreen(
                            selectedId = (current as? Route.Detail)?.appId,
                            onOpen = { if ((current as? Route.Detail)?.appId == it) reopened++ else stack.showDetail(it) },
                            onAdd = { stack.select(Tab.ADD) },
                            onHandoff = { openHandoff(stack) },
                            listState = listState,
                        )
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight().verticalFocusStaysInside()) {
                    if (current is Route.Detail) {
                        Screens(stack, holder, current, listState, twoPane = true, reopened = reopened)
                    } else {
                        NothingSelected()
                    }
                }
            }
        }
    } else {
        Screens(stack, holder, current, listState, twoPane = false)
    }
}

@Composable
private fun Screens(
    stack: BackStack,
    holder: SaveableStateHolder,
    current: Route,
    listState: LazyListState,
    twoPane: Boolean,
    reopened: Int = 0,
) {
    val reducedMotion = LocalReducedMotion.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedContent(
        targetState = Shown(current, stack.routes.size),
        transitionSpec = {
            val deeper = targetState.depth > initialState.depth
            val back = targetState.depth < initialState.depth
            val moving = !targetState.route.isTabRoot || !initialState.route.isTabRoot
            when {
                reducedMotion -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                // Deeper comes in from the side a line of text runs to, and back returns the way it came.
                moving && (deeper || back) -> {
                    val toward = (if (deeper) 1 else -1) * (if (rightToLeft) -1 else 1)
                    (slideInHorizontally(tween(SLIDE_MS)) { toward * it / SLIDE_PART } + fadeIn(tween(150, delayMillis = 40))) togetherWith
                        (slideOutHorizontally(tween(SLIDE_MS)) { -toward * it / SLIDE_PART } + fadeOut(tween(90)))
                }
                else -> fadeIn(tween(150)) togetherWith fadeOut(tween(90))
            }
        },
        contentKey = { encodeRoute(it.route) },
        label = "screen",
    ) { shown ->
        holder.SaveableStateProvider(encodeRoute(shown.route)) {
            Screen(stack, shown.route, listState, twoPane, reopened)
        }
    }
}

/** A screen and how deep in the stack it sits, which says whether a change goes in or comes back. */
private data class Shown(val route: Route, val depth: Int)

/** The screens the tabs lead to; moving between them is a change of place, not a step in or out. */
private val Route.isTabRoot: Boolean
    get() = this is Route.Apps || this is Route.Add || this is Route.Activity || this is Route.Settings

private const val SLIDE_MS = 220
private const val SLIDE_PART = 8

@Composable
private fun Screen(stack: BackStack, route: Route, listState: LazyListState, twoPane: Boolean, reopened: Int) {
    when (route) {
        Route.Apps -> AppsScreen(
            selectedId = null,
            onOpen = { stack.showDetail(it) },
            onAdd = { stack.select(Tab.ADD) },
            onHandoff = { openHandoff(stack) },
            listState = listState,
        )
        is Route.Add -> AddScreen(
            prefill = route.input,
            nonce = route.nonce,
            onAdded = { id -> stack.showAdded(id) },
            onShow = { id -> stack.showAdded(id) },
            onHandoff = { stack.push(Route.Handoff) },
            onBack = if (stack.addIsOnTop) ({ stack.pop() }) else null,
        )
        is Route.Detail -> DetailScreen(
            appId = route.appId,
            onBack = if (twoPane) null else ({ stack.pop() }),
            focusAgain = reopened,
            onRemoved = { stack.pop() },
        )
        Route.Activity -> ActivityScreen(onOpenApp = { stack.showDetail(it) })
        Route.Settings -> SettingsScreen(
            onImport = { stack.push(Route.Import) },
            onLook = { stack.push(Route.Look) },
            onAdd = { stack.lookAt(it, System.nanoTime()) },
        )
        Route.Import -> ImportScreen(onBack = { stack.pop() }, onOpenApp = { stack.showDetail(it) }, onHandoff = { stack.push(Route.Handoff) })
        Route.Look -> LookScreen(onBack = { stack.pop() })
        Route.Handoff -> HandoffScreen(
            onBack = { stack.pop() },
            onLook = { stack.lookAt(it, System.nanoTime()) },
            onOpenApp = { stack.showDetail(it) },
        )
    }
}

@Composable
private fun NothingSelected() {
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.pane_nothing_selected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val Tab.label: Int
    get() = when (this) {
        Tab.APPS -> R.string.tab_apps
        Tab.ADD -> R.string.tab_add
        Tab.ACTIVITY -> R.string.tab_activity
        Tab.SETTINGS -> R.string.tab_settings
    }

/** Large text shrinks to fit the slot rather than being cut off; it never grows past the bar's own size. */
@Composable
private fun TabLabel(tab: Tab) {
    val style = LocalTextStyle.current
    BasicText(
        stringResource(tab.label),
        style = style.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
        maxLines = 1,
        modifier = Modifier.testTag(TAB_LABEL_TAG),
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize),
    )
}

@Composable
private fun TabIcon(tab: Tab, updates: Int) {
    val icon = when (tab) {
        Tab.APPS -> Glyphs.Apps
        Tab.ADD -> Icons.Filled.Add
        Tab.ACTIVITY -> Glyphs.Activity
        Tab.SETTINGS -> Icons.Filled.Settings
    }
    if (tab == Tab.APPS && updates > 0) {
        val spoken = pluralStringResource(R.plurals.tab_updates_badge, updates, updates)
        BadgedBox(badge = {
            Badge(Modifier.semantics { contentDescription = spoken }) { Text(if (updates > 99) "99+" else updates.toString()) }
        }) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}
