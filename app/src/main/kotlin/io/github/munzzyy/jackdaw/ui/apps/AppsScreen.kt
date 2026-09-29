package io.github.munzzyy.jackdaw.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.LocalOnline
import io.github.munzzyy.jackdaw.ui.common.OfflineBanner
import io.github.munzzyy.jackdaw.ui.LocalSnackbar
import io.github.munzzyy.jackdaw.ui.common.rememberActions
import io.github.munzzyy.jackdaw.ui.common.verticalKeysLeave
import io.github.munzzyy.jackdaw.ui.icons.Glyphs

const val APP_LIST_TAG = "app_list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    selectedId: String?,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "apps") { AppsViewModel(engine) }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val checking by engine.checkingAll.collectAsStateWithLifecycle()
    val online = LocalOnline.current
    val offlineReason = stringResource(R.string.offline_reason)
    val actions = rememberActions()
    var text by rememberSaveable { mutableStateOf(query.text) }
    LaunchedEffect(text) { vm.setText(text) }
    val selection by vm.selection.collectAsStateWithLifecycle()
    val allRows by engine.apps.collectAsStateWithLifecycle()
    val picked = remember(selection, allRows) { selection?.let { ids -> allRows.filter { it.id in ids } }.orEmpty() }
    var pending by rememberSaveable { mutableStateOf<BulkAction?>(null) }
    BackHandler(enabled = selection != null) { vm.stopSelecting() }

    Scaffold(
        topBar = {
            if (selection != null) {
                SelectionTopBar(
                    count = picked.size,
                    onClose = vm::stopSelecting,
                    onSelectAll = { vm.selectAll(state.sections.updates.map { it.id } + state.sections.others.map { it.id }) },
                )
                return@Scaffold
            }
            TopAppBar(
                title = { Text(stringResource(R.string.tab_apps)) },
                actions = {
                    SortMenu(query.sort, vm::setSort)
                    IconButton(
                        onClick = { actions.run { engine.check() } },
                        enabled = !checking && online,
                        modifier = if (online) Modifier else Modifier.semantics { stateDescription = offlineReason },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_check_all))
                    }
                    if (state.total > 0) ScreenMenu(onSelect = { vm.startSelecting() })
                },
            )
        },
        bottomBar = { if (selection != null) BulkBar(picked) { pending = it } },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OfflineBanner(online)
            PullToRefreshBox(
                isRefreshing = checking,
                onRefresh = { if (online) actions.run { engine.check() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    !state.loaded -> Unit
                    state.total == 0 -> EmptyApps(onAdd)
                    else -> AppList(
                        state = state,
                        query = query,
                        text = text,
                        onText = { text = it.take(200) },
                        onFilter = vm::setFilter,
                        selectedId = selectedId,
                        onOpen = onOpen,
                        onUpdateAll = { engine.installAllUpdates() },
                        listState = listState,
                        selection = selection,
                        onSelect = { id -> if (selection == null) vm.startSelecting(id) else vm.toggle(id) },
                    )
                }
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
    pending?.let { action ->
        BulkDialog(action, picked, state.categories, engine, actions, onDismiss = { pending = null }, onDone = vm::stopSelecting)
    }
}

@Composable
private fun ScreenMenu(onSelect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_select)) }, onClick = { open = false; onSelect() })
        }
    }
}

@Composable
private fun AppList(
    state: AppsState,
    query: ListQuery,
    text: String,
    onText: (String) -> Unit,
    onFilter: (AppFilter) -> Unit,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onUpdateAll: () -> Unit,
    listState: LazyListState,
    selection: Set<String>?,
    onSelect: (String) -> Unit,
) {
    val sections = state.sections
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize().testTag(APP_LIST_TAG)) {
        item(key = "search", contentType = "search") { SearchField(text, onText) }
        item(key = "filters", contentType = "filters") { FilterChips(state.categories, query.filter, onFilter) }
        if (sections.isEmpty) {
            item(key = "nomatch", contentType = "message") {
                Text(
                    stringResource(R.string.apps_no_match),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        if (sections.updates.isNotEmpty()) {
            item(key = "h-updates", contentType = "header") {
                UpdatesHeader(sections.updates.size, state.updatable, onUpdateAll)
            }
            rows(sections.updates, selectedId, onOpen, selection, onSelect)
        }
        if (sections.others.isNotEmpty()) {
            if (sections.updates.isNotEmpty()) {
                item(key = "h-others", contentType = "header") { ListHeader(stringResource(R.string.apps_section_others)) }
            }
            rows(sections.others, selectedId, onOpen, selection, onSelect)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rows(
    rows: List<AppRow>,
    selectedId: String?,
    onOpen: (String) -> Unit,
    selection: Set<String>?,
    onSelect: (String) -> Unit,
) {
    items(rows, key = { it.id }, contentType = { "row" }) { row ->
        AppRowItem(
            row,
            selected = row.id == selectedId,
            onOpen = { onOpen(row.id) },
            selecting = selection != null,
            checked = selection?.contains(row.id) == true,
            onSelect = { onSelect(row.id) },
        )
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun UpdatesHeader(count: Int, updatable: Int, onUpdateAll: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.apps_section_updates, count, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (updatable >= 2) {
            val online = LocalOnline.current
            val offlineReason = stringResource(R.string.offline_reason)
            Button(
                onClick = onUpdateAll,
                enabled = online,
                modifier = if (online) Modifier else Modifier.semantics { stateDescription = offlineReason },
            ) { Text(stringResource(R.string.action_update_all)) }
        }
    }
}

@Composable
private fun SearchField(text: String, onText: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.apps_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (text.isNotEmpty()) {
            {
                IconButton(onClick = { onText("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear_search))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .verticalKeysLeave(),
    )
}

@Composable
private fun FilterChips(categories: List<String>, current: AppFilter, onFilter: (AppFilter) -> Unit) {
    val fixed = listOf(
        AppFilter.All to stringResource(R.string.filter_all),
        AppFilter.Updates to stringResource(R.string.filter_updates),
        AppFilter.Installed to stringResource(R.string.filter_installed),
        AppFilter.NotInstalled to stringResource(R.string.filter_not_installed),
    )
    val all = fixed + categories.map { AppFilter.Category(it) to it }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(all, key = { it.second + it.first.javaClass.simpleName }) { (filter, label) ->
            val selected = filter == current
            FilterChip(
                selected = selected,
                onClick = { onFilter(filter) },
                label = { Text(label) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun SortMenu(current: AppSort, onSort: (AppSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Glyphs.Sort, contentDescription = stringResource(R.string.sort_title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (sort in AppSort.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(sortLabel(sort))) },
                    leadingIcon = { if (sort == current) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        onSort(sort)
                        open = false
                    },
                )
            }
        }
    }
}

private fun sortLabel(sort: AppSort): Int = when (sort) {
    AppSort.NAME -> R.string.sort_name
    AppSort.RECENTLY_CHECKED -> R.string.sort_recently_checked
    AppSort.SOURCE -> R.string.sort_source
}

@Composable
private fun EmptyApps(onAdd: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Text(stringResource(R.string.apps_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.apps_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onAdd) { Text(stringResource(R.string.action_add_first)) }
    }
}
