package io.github.munzzyy.tern.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.common.BannerRow
import io.github.munzzyy.tern.ui.common.ChoiceChip
import io.github.munzzyy.tern.ui.common.ColorDot
import io.github.munzzyy.tern.ui.common.EmptyState
import io.github.munzzyy.tern.ui.common.GlyphButton
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.OfflineBanner
import io.github.munzzyy.tern.ui.common.PrimaryButton
import io.github.munzzyy.tern.ui.common.RevealWithRoom
import io.github.munzzyy.tern.ui.common.ScreenFocus
import io.github.munzzyy.tern.ui.common.ScreenTop
import io.github.munzzyy.tern.ui.common.backupFocus
import io.github.munzzyy.tern.ui.common.confirmInstall
import io.github.munzzyy.tern.ui.common.drivenByKeys
import io.github.munzzyy.tern.ui.common.firstFocus
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.rememberScreenFocus
import io.github.munzzyy.tern.ui.common.returnFocus
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.icons.Close
import io.github.munzzyy.tern.ui.icons.Collapse
import io.github.munzzyy.tern.ui.icons.Expand
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.More
import io.github.munzzyy.tern.ui.icons.Search
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.LocalOutlines
import io.github.munzzyy.tern.ui.theme.categoryColor

const val APP_LIST_TAG = "app_list"
const val APP_SEARCH_TAG = "app_search"
const val APP_SEARCH_OPEN_TAG = "app_search_open"
const val APP_FILTERS_TAG = "app_filters"
const val WAITING_BANNER_TAG = "waiting_banner"

private const val STACK_FONT_SCALE = 1.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    selectedId: String?,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onHandoff: () -> Unit = onAdd,
) {
    val engine = LocalEngine.current
    val removals = rememberRemovals()
    val vm = viewModel(key = "apps") { AppsViewModel(engine, removals.hidden) }
    // Read here and handed on as plain values: the content of a Scaffold is composed by itself and would otherwise see a newer list than the top does.
    val state = vm.state.collectAsStateWithLifecycle().value
    val query = vm.query.collectAsStateWithLifecycle().value
    val checking = engine.checkingAll.collectAsStateWithLifecycle().value
    val online = LocalOnline.current
    val offlineReason = stringResource(R.string.offline_reason)
    val actions = rememberActions()
    val remove = rememberRemove()
    var typed by rememberSaveable { mutableStateOf(query.text) }
    val text = typed
    LaunchedEffect(text) { vm.setText(text) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val folded = foldsSearch(state.total, LocalLook.current.television)
    val fieldShown = state.total > 0 && (!folded || searching || text.isNotEmpty())
    val grab = folded && searching
    val closeSearch = {
        typed = ""
        searching = false
    }
    val selection = vm.selection.collectAsStateWithLifecycle().value
    val allRows = engine.apps.collectAsStateWithLifecycle().value
    val picked = remember(selection, allRows) { selection?.let { ids -> allRows.filter { it.id in ids } }.orEmpty() }
    var pending by rememberSaveable { mutableStateOf<BulkAction?>(null) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    val collapsed = vm.collapsed.collectAsStateWithLifecycle().value
    val swipe = engine.settings.collectAsStateWithLifecycle().value.swipeActions && !LocalNoTouch.current
    BackHandler(enabled = selection == null && folded && fieldShown, onBack = closeSearch)
    BackHandler(enabled = selection != null) { vm.stopSelecting() }
    val screen = rememberScreenFocus(active = selectedId == null)
    val keys = drivenByKeys()
    var landAgain by remember { mutableIntStateOf(0) }
    LaunchedEffect(landAgain) { if (landAgain > 0 && keys) screen.land() }
    LaunchedEffect(grab) { if (grab) listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            if (selection != null) {
                SelectionTopBar(
                    picked = picked,
                    onClose = vm::stopSelecting,
                    onSelectAll = { vm.selectAll(state.sections.updates.map { it.id } + state.sections.others.map { it.id }) },
                    onAction = { pending = it },
                )
                return@Scaffold
            }
            ScreenTop(stringResource(R.string.tab_apps)) {
                if (state.total > 0 && !fieldShown) {
                    GlyphButton(Glyphs.Search, stringResource(R.string.apps_search_hint), onClick = { searching = true }, modifier = Modifier.testTag(APP_SEARCH_OPEN_TAG))
                }
                GlyphButton(
                    Glyphs.Busy,
                    stringResource(R.string.action_check_all),
                    onClick = { actions.run { engine.check() } },
                    enabled = !checking && online,
                    modifier = (if (online) Modifier else Modifier.semantics { stateDescription = offlineReason })
                        .then(if (fieldShown || !state.loaded) Modifier else Modifier.backupFocus(screen)),
                )
                if (state.total > 0) ScreenMenu(onArrange = { arranging = true }, onSelect = { vm.startSelecting() })
            }
        },
        bottomBar = { if (selection != null) BulkBar(picked) { pending = it } },
        snackbarHost = { TernSnackbarHost(onActionGone = { landAgain++ }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OfflineBanner(online)
            if (selection == null) WaitingBanner(state.waiting, onGone = { landAgain++ }) { confirmInstall(engine, it.id, actions) }
            RevealWithRoom {
            val pull = rememberPullToRefreshState()
            val noTouch = LocalNoTouch.current
            PullToRefreshBox(
                isRefreshing = checking,
                onRefresh = { if (online) actions.run { engine.check() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = pull,
                // Without touch nobody pulls, and the bar below already shows the check.
                indicator = { if (!noTouch) PullToRefreshDefaults.Indicator(state = pull, isRefreshing = checking, modifier = Modifier.align(Alignment.TopCenter)) },
            ) {
                when {
                    !state.loaded -> Unit
                    state.total == 0 -> EmptyApps(onAdd, onHandoff, Modifier.firstFocus(screen))
                    else -> AppList(
                        state = state,
                        query = query,
                        search = if (fieldShown) {
                            Search(text, grab = grab, onText = { typed = it.take(200) }, onClose = if (folded) closeSearch else null)
                        } else {
                            null
                        },
                        onFilter = vm::setFilter,
                        selectedId = selectedId,
                        onOpen = onOpen,
                        onUpdateAll = { engine.installAllUpdates() },
                        onRemove = remove,
                        listState = listState,
                        selection = selection,
                        onSelect = { id -> if (selection == null) vm.startSelecting(id) else vm.toggle(id) },
                        screen = screen,
                        collapsed = collapsed,
                        onToggleGroup = vm::toggleGroup,
                        swipe = swipe,
                    )
                }
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            }
        }
    }
    pending?.let { action ->
        BulkDialog(action, picked, state.categories, engine, actions, onDismiss = { pending = null }, onDone = vm::stopSelecting)
    }
    if (arranging) ListOptionsDialog(query, vm, onDismiss = { arranging = false })
}

/** What the search field holds and does. [grab] is true when the user has just opened it, so it takes focus. */
private class Search(val text: String, val grab: Boolean, val onText: (String) -> Unit, val onClose: (() -> Unit)?)

/** Says which install waits for the user, or how many do. Its action confirms the first of them. [onGone] is called when it leaves with the focus on it. */
@Composable
private fun WaitingBanner(waiting: List<AppRow>, onGone: () -> Unit, onConfirm: (AppRow) -> Unit) {
    val first = waiting.firstOrNull() ?: return
    val words = if (waiting.size == 1) {
        stringResource(R.string.waiting_banner_one, first.config.name)
    } else {
        pluralStringResource(R.plurals.waiting_banner_several, waiting.size, waiting.size)
    }
    BannerRow(
        text = words,
        action = stringResource(R.string.action_confirm),
        onAction = { onConfirm(first) },
        modifier = Modifier
            .testTag(WAITING_BANNER_TAG)
            .whenGoneWithFocus(onGone)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ScreenMenu(onArrange: () -> Unit, onSelect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlyphButton(Glyphs.More, stringResource(R.string.action_more), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.focusHighlight()) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_select)) }, onClick = { open = false; onSelect() })
            DropdownMenuItem(text = { Text(stringResource(R.string.list_arrange)) }, onClick = { open = false; onArrange() })
        }
    }
}

@Composable
private fun AppList(
    state: AppsState,
    query: ListQuery,
    search: Search?,
    onFilter: (AppFilter) -> Unit,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onRemove: (AppRow) -> Unit,
    listState: LazyListState,
    selection: Set<String>?,
    onSelect: (String) -> Unit,
    screen: ScreenFocus,
    collapsed: Set<String> = emptySet(),
    onToggleGroup: (String) -> Unit = {},
    swipe: Boolean = false,
) {
    val look = LocalLook.current
    val sections = state.sections
    val categoryColors = LocalEngine.current.settings.collectAsStateWithLifecycle().value.categoryColors
    val firstId = (sections.updates.firstOrNull() ?: sections.others.firstOrNull())?.id
    val rowFocus: (String) -> Modifier = { id ->
        Modifier.returnFocus(screen, id).then(if (id == firstId) Modifier.firstFocus(screen) else Modifier)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val place = actionPlace(maxWidth, look.iconList, LocalDensity.current.fontScale, LocalNoTouch.current)
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = look.gapSection), modifier = Modifier.fillMaxSize().testTag(APP_LIST_TAG)) {
        if (search != null) {
            item(key = "search", contentType = "search") { SearchField(search, Modifier.backupFocus(screen)) }
        }
        if (state.filters.isNotEmpty()) {
            item(key = "filters", contentType = "filters") { FilterChips(state.filters, query.filter, onFilter) }
        }
        if (sections.isEmpty) {
            item(key = "nomatch", contentType = "message") {
                Text(
                    stringResource(R.string.apps_no_match),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = look.screenPadding, vertical = look.gapSection),
                )
            }
        }
        if (sections.updates.isNotEmpty()) {
            item(key = "h-updates", contentType = "header") {
                UpdatesHeader(sections.updates.size, if (selection == null) state.updatable else 0, onUpdateAll)
            }
            rows(sections.updates, selectedId, onOpen, onRemove, selection, onSelect, rowFocus, place, swipe)
        }
        if (sections.groups.isNotEmpty()) {
            for (group in sections.groups) {
                val folded = group.key in collapsed
                item(key = "g-${group.key}", contentType = "group") {
                    val dot = group.title?.takeIf { group.key.startsWith(CATEGORY_GROUP) }?.let { categoryColor(it, categoryColors) }
                    GroupHeader(group.title ?: stringResource(R.string.group_other), group.rows.size, folded, dot) { onToggleGroup(group.key) }
                }
                if (!folded) rows(group.rows, selectedId, onOpen, onRemove, selection, onSelect, rowFocus, place, swipe, keyPrefix = group.key)
            }
        } else if (sections.others.isNotEmpty()) {
            if (sections.updates.isNotEmpty()) {
                item(key = "h-others", contentType = "header") { ListHeader(stringResource(R.string.apps_section_others)) }
            }
            rows(sections.others, selectedId, onOpen, onRemove, selection, onSelect, rowFocus, place, swipe)
        }
    }
    }
}

private fun LazyListScope.rows(
    rows: List<AppRow>,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onRemove: (AppRow) -> Unit,
    selection: Set<String>?,
    onSelect: (String) -> Unit,
    rowFocus: (String) -> Modifier,
    place: ActionPlace,
    swipe: Boolean,
    keyPrefix: String = "",
) {
    // An app filed under two categories is shown twice, so its key names the group too.
    items(rows, key = { keyPrefix + it.id }, contentType = { "row" }) { row ->
        val item: @Composable () -> Unit = {
            AppRowItem(
                row,
                selected = row.id == selectedId,
                onOpen = { onOpen(row.id) },
                modifier = if (keyPrefix.isEmpty()) rowFocus(row.id) else Modifier,
                selecting = selection != null,
                checked = selection?.contains(row.id) == true,
                onSelect = { onSelect(row.id) },
                onRemove = { onRemove(row) },
                actionPlace = place,
            )
        }
        if (swipe && selection == null) SwipeRow(row, onRemove = { onRemove(row) }, content = item) else item()
    }
}

/** The name of a group, how many apps it holds, and a press to fold it away or open it again. */
@Composable
private fun GroupHeader(title: String, count: Int, folded: Boolean, dot: Color?, onToggle: () -> Unit) {
    val look = LocalLook.current
    val spoken = stringResource(if (folded) R.string.group_folded else R.string.group_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .focusLook(MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = spoken
            }
            .padding(horizontal = look.rowPaddingHorizontal - look.focusRoom, vertical = look.gapSmall),
    ) {
        if (dot != null) ColorDot(dot)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (folded) Glyphs.Expand else Glyphs.Collapse,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(look.glyph),
        )
    }
}

@Composable
private fun HeaderWords(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun ListHeader(text: String) {
    val look = LocalLook.current
    HeaderWords(
        text,
        Modifier
            .fillMaxWidth()
            .padding(start = look.rowPaddingHorizontal, end = look.rowPaddingHorizontal, top = look.gap, bottom = look.gapSmall / 2),
    )
}

@Composable
private fun UpdatesHeader(count: Int, updatable: Int, onUpdateAll: () -> Unit) {
    val look = LocalLook.current
    val words = pluralStringResource(R.plurals.apps_section_updates, count, count)
    if (updatable < 2) {
        ListHeader(words)
        return
    }
    val online = LocalOnline.current
    val offlineReason = stringResource(R.string.offline_reason)
    val button: @Composable () -> Unit = {
        PrimaryButton(
            stringResource(R.string.action_update_all),
            onClick = onUpdateAll,
            enabled = online,
            modifier = if (online) Modifier else Modifier.semantics { stateDescription = offlineReason },
        )
    }
    val frame = Modifier
        .fillMaxWidth()
        .padding(start = look.rowPaddingHorizontal, end = look.rowPaddingHorizontal, top = look.gapSmall)
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(frame, verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
            HeaderWords(words)
            button()
        }
    } else {
        Row(frame, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gap)) {
            HeaderWords(words, Modifier.weight(1f))
            button()
        }
    }
}

@Composable
private fun SearchField(search: Search, focus: Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val grab = remember { FocusRequester() }
    if (search.grab) LaunchedEffect(Unit) { grab.requestFocus() }
    TextField(
        value = search.text,
        onValueChange = search.onText,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.apps_search_hint)) },
        leadingIcon = { Icon(Glyphs.Search, contentDescription = null, modifier = Modifier.size(look.glyph)) },
        trailingIcon = when {
            search.text.isNotEmpty() -> {
                { GlyphButton(Glyphs.Close, stringResource(R.string.action_clear_search), onClick = { search.onText("") }) }
            }
            search.onClose != null -> {
                { GlyphButton(Glyphs.Close, stringResource(R.string.apps_search_close), onClick = search.onClose) }
            }
            else -> null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = LocalOutlines.current.button,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerHigh,
            unfocusedContainerColor = scheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.screenPadding, vertical = look.gapSmall / 2)
            .then(focus)
            .focusRequester(grab)
            .textFieldKeys()
            .testTag(APP_SEARCH_TAG),
    )
}

@Composable
private fun filterLabel(filter: AppFilter): String = when (filter) {
    AppFilter.All -> stringResource(R.string.filter_all)
    AppFilter.Updates -> stringResource(R.string.filter_updates)
    AppFilter.Installed -> stringResource(R.string.filter_installed)
    AppFilter.NotInstalled -> stringResource(R.string.filter_not_installed)
    AppFilter.Favorites -> stringResource(R.string.filter_favorites)
    AppFilter.TrackOnly -> stringResource(R.string.filter_track_only)
    AppFilter.Problems -> stringResource(R.string.filter_problems)
    is AppFilter.Category -> filter.name
    is AppFilter.Source -> sourceLabel(filter.type)
}

@Composable
private fun FilterChips(filters: List<AppFilter>, current: AppFilter, onFilter: (AppFilter) -> Unit) {
    val colors = LocalEngine.current.settings.collectAsStateWithLifecycle().value.categoryColors
    val look = LocalLook.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2),
        contentPadding = PaddingValues(horizontal = look.screenPadding, vertical = look.gapSmall / 2 + look.focusRoom / 2),
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .testTag(APP_FILTERS_TAG),
    ) {
        items(filters, key = { it.toString() }) { filter ->
            ChoiceChip(
                filterLabel(filter),
                selected = filter == current,
                onClick = { onFilter(filter) },
                dot = (filter as? AppFilter.Category)?.let { categoryColor(it.name, colors) },
            )
        }
    }
}

@Composable
private fun EmptyApps(onAdd: () -> Unit, onHandoff: () -> Unit, focus: Modifier) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.apps_empty_title),
            text = stringResource(R.string.apps_empty_text),
            action = stringResource(R.string.empty_add),
            onAction = onAdd,
            actionModifier = focus,
            secondAction = stringResource(R.string.empty_well_known),
            onSecondAction = onAdd,
            thirdAction = if (LocalNoTouch.current) stringResource(R.string.empty_handoff) else null,
            onThirdAction = onHandoff,
        )
    }
}
