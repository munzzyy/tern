package io.github.munzzyy.tern.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.CheckCount
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.common.BannerRow
import io.github.munzzyy.tern.ui.common.ChoiceChip
import io.github.munzzyy.tern.ui.common.ColorDot
import io.github.munzzyy.tern.ui.common.ConfirmDialog
import io.github.munzzyy.tern.ui.common.EmptyState
import io.github.munzzyy.tern.ui.common.GlyphButton
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.OfflineBanner
import io.github.munzzyy.tern.ui.common.PrimaryButton
import io.github.munzzyy.tern.ui.common.QuietButton
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
import io.github.munzzyy.tern.ui.theme.figures

const val APP_LIST_TAG = "app_list"
const val APP_SEARCH_TAG = "app_search"
const val APP_SEARCH_OPEN_TAG = "app_search_open"
const val APP_FILTERS_TAG = "app_filters"
const val APP_FILTER_OPEN_TAG = "app_filter_open"
const val FILTER_NOTE_TAG = "filter_note"
const val CHECKING_BAR_TAG = "checking_bar"
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
    val checkCount = engine.checkCount.collectAsStateWithLifecycle().value
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
    val settings = engine.settings.collectAsStateWithLifecycle().value
    val swipe = settings.swipeActions && !LocalNoTouch.current
    var filtering by rememberSaveable { mutableStateOf(false) }
    val clearFilters = {
        typed = ""
        vm.clearFilters()
    }
    val plan = if (selection == null) updateAllPlan(settings.updateAllMode, state.updatable, state.firstInstalls.size) else null
    var confirmingAll by rememberSaveable { mutableStateOf(false) }
    // First installs go first, so an update of Tern itself stays where Update all puts it.
    val startAll = {
        if (plan != null && plan.installs > 0) state.firstInstalls.forEach { engine.install(it) }
        engine.installAllUpdates()
    }
    val onUpdateAll = { if (settings.confirmUpdateAll) confirmingAll = true else startAll() }
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
                    onSelectAll = { vm.selectAll(shownIds(state.sections, collapsed)) },
                    onAction = { pending = it },
                )
                return@Scaffold
            }
            ScreenTop(stringResource(R.string.tab_apps)) {
                if (state.total > 0 && !fieldShown) {
                    GlyphButton(Glyphs.Search, stringResource(R.string.apps_search_hint), onClick = { searching = true }, modifier = Modifier.testTag(APP_SEARCH_OPEN_TAG))
                }
                if (state.total > 0) FilterButton(on = state.filter.isOn, onClick = { filtering = true })
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
            if (checking) CheckingBar(checkCount)
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
                        search = if (fieldShown) {
                            Search(text, grab = grab, onText = { typed = it.take(200) }, onClose = if (folded) closeSearch else null)
                        } else {
                            null
                        },
                        onFilter = vm::toggleFilter,
                        onClearFilters = clearFilters,
                        selectedId = selectedId,
                        onOpen = onOpen,
                        plan = plan,
                        onUpdateAll = onUpdateAll,
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
            }
            }
        }
    }
    pending?.let { action ->
        BulkDialog(action, picked, engine, actions, onDismiss = { pending = null }, onDone = vm::stopSelecting)
    }
    if (arranging) ListOptionsDialog(query, vm, onDismiss = { arranging = false })
    if (filtering) FilterDialog(state, onChange = vm::setFilter, onClear = clearFilters, onDismiss = { filtering = false })
    if (confirmingAll && plan != null) UpdateAllDialog(plan, onConfirm = startAll, onDismiss = { confirmingAll = false })
}

/** Opens the filters. While one is on, the glyph takes the accent colour and says so. */
@Composable
private fun FilterButton(on: Boolean, onClick: () -> Unit) {
    val onWords = stringResource(R.string.filter_state_on)
    GlyphButton(
        Glyphs.Filter,
        stringResource(R.string.filter_open),
        onClick = onClick,
        tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        modifier = Modifier
            .testTag(APP_FILTER_OPEN_TAG)
            .then(if (on) Modifier.semantics { stateDescription = onWords } else Modifier),
    )
}

/** How far a check of the whole list has got, in words and as a bar that fills. Until the engine has counted, the bar only moves. */
@Composable
private fun CheckingBar(count: CheckCount?) {
    val look = LocalLook.current
    val words = if (count != null && count.total > 0) stringResource(R.string.checking_count, count.done, count.total) else stringResource(R.string.status_checking)
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.screenPadding, vertical = look.gapSmall / 2)
            .testTag(CHECKING_BAR_TAG)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(words, style = MaterialTheme.typography.labelLarge.figures(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null && count.total > 0) {
            LinearProgressIndicator(progress = { count.fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** What the search field holds and does. [grab] is true when the user has just opened it, so it takes focus. */
private class Search(val text: String, val grab: Boolean, val onText: (String) -> Unit, val onClose: (() -> Unit)?)

/** Says which install waits for the user, or how many do. Its action confirms the first of them. [onGone] is called when it leaves with the focus on it. */
@Composable
private fun WaitingBanner(waiting: List<AppRow>, onGone: () -> Unit, onConfirm: (AppRow) -> Unit) {
    val first = waiting.firstOrNull() ?: return
    val words = if (waiting.size == 1) {
        stringResource(R.string.waiting_banner_one, first.config.shownName)
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
    search: Search?,
    onFilter: (AppFilter) -> Unit,
    onClearFilters: () -> Unit,
    selectedId: String?,
    onOpen: (String) -> Unit,
    plan: UpdateAllPlan?,
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
    val landing = remember(sections, collapsed) { firstPlace(sections, collapsed) }
    val rowFocus: (String) -> Modifier = { key ->
        Modifier.returnFocus(screen, key).then(if (key == landing) Modifier.firstFocus(screen) else Modifier)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val place = actionPlace(maxWidth, look.iconList, LocalDensity.current.fontScale, LocalNoTouch.current)
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = look.gapSection), modifier = Modifier.fillMaxSize().testTag(APP_LIST_TAG)) {
        if (search != null) {
            item(key = "search", contentType = "search") { SearchField(search, Modifier.backupFocus(screen)) }
        }
        if (state.filters.isNotEmpty()) {
            item(key = "filters", contentType = "filters") { FilterChips(state.filters, state.filter, onFilter) }
        }
        if (state.filter.isOn) {
            item(key = "filtered", contentType = "filtered") { FilterNote(state.shown, state.total, onClearFilters) }
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
                ActionHeader(pluralStringResource(R.plurals.apps_section_updates, sections.updates.size, sections.updates.size), plan, onUpdateAll)
            }
            rows(sections.updates, selectedId, onOpen, onRemove, selection, onSelect, rowFocus, place, swipe)
        } else if (plan != null && plan.installs > 0) {
            item(key = "h-installs", contentType = "header") {
                ActionHeader(pluralStringResource(R.plurals.apps_section_installs, plan.installs, plan.installs), plan, onUpdateAll)
            }
        }
        if (sections.groups.isNotEmpty()) {
            for (group in sections.groups) {
                val folded = group.key in collapsed
                item(key = headerKey(group), contentType = "group") {
                    val dot = group.title?.takeIf { group.key.startsWith(CATEGORY_GROUP) }?.let { categoryColor(it, categoryColors) }
                    GroupHeader(group.title ?: stringResource(R.string.group_other), group.rows.size, folded, dot, rowFocus(headerKey(group))) { onToggleGroup(group.key) }
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
                modifier = rowFocus(keyPrefix + row.id),
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
internal fun GroupHeader(title: String, count: Int, folded: Boolean, dot: Color?, focus: Modifier, onToggle: () -> Unit) {
    val look = LocalLook.current
    val spoken = stringResource(if (folded) R.string.group_folded else R.string.group_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .then(focus)
            .focusLook(MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = spoken
            }
            .heightIn(min = look.touchTarget)
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

/** A header with the Update all button of [plan] beside it, or alone where there is no such button. */
@Composable
private fun ActionHeader(words: String, plan: UpdateAllPlan?, onUpdateAll: () -> Unit) {
    val look = LocalLook.current
    if (plan == null) {
        ListHeader(words)
        return
    }
    val online = LocalOnline.current
    val offlineReason = stringResource(R.string.offline_reason)
    val button: @Composable () -> Unit = {
        PrimaryButton(
            stringResource(updateAllLabel(plan)),
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

/** Asks before Update all starts, naming how many apps it installs or updates. */
@Composable
private fun UpdateAllDialog(plan: UpdateAllPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = when {
        plan.installs == 0 -> pluralStringResource(R.plurals.bulk_update_title, plan.updates, plan.updates)
        plan.updates == 0 -> pluralStringResource(R.plurals.bulk_install_title, plan.installs, plan.installs)
        else -> pluralStringResource(R.plurals.update_all_title, plan.total, plan.total)
    }
    ConfirmDialog(
        title = title,
        text = stringResource(if (plan.installs == 0) R.string.bulk_update_text else R.string.bulk_install_text),
        confirm = stringResource(updateAllLabel(plan)),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Says that a filter is on and how much of the list it leaves, with one press that clears it and the search. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterNote(shown: Int, total: Int, onClear: () -> Unit) {
    val look = LocalLook.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.screenPadding, vertical = look.focusRoom / 2)
            .testTag(FILTER_NOTE_TAG),
    ) {
        Text(
            pluralStringResource(R.plurals.filter_shown, total, shown, total),
            style = MaterialTheme.typography.bodyMedium.figures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        QuietButton(stringResource(R.string.filter_clear), onClick = onClear)
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

/** The quick filters. Any number can be on at once; All is on while none is, and clears them all. */
@Composable
private fun FilterChips(filters: List<AppFilter>, current: ListFilter, onFilter: (AppFilter) -> Unit) {
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
                selected = current.has(filter),
                onClick = { onFilter(filter) },
                role = Role.Checkbox,
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
