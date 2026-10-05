package io.github.munzzyy.tern.ui.apps

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import io.github.munzzyy.tern.ui.common.ScreenFocus
import io.github.munzzyy.tern.ui.common.tryFocus
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlinx.coroutines.launch

private const val LAND_FRAMES = 10

/**
 * The keys of [listKey] on the list. The search field keeps every key it is given, so nothing
 * happens while [inSearch] says it has focus; [onSearch] is null where no search is offered.
 */
fun Modifier.listKeys(list: LazyListState, screen: ScreenFocus, inSearch: () -> Boolean, onSearch: ((String) -> Unit)?): Modifier = composed {
    val scope = rememberCoroutineScope()
    val television = LocalLook.current.television
    val searching by rememberUpdatedState(inSearch)
    val search by rememberUpdatedState(onSearch)
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || searching()) return@onPreviewKeyEvent false
        when (val key = event.toListKey(television)) {
            null -> false
            is ListKey.Search -> search?.let {
                it(key.typed)
                true
            } ?: false
            else -> {
                scope.launch { list.move(key, screen) }
                true
            }
        }
    }
}

private fun KeyEvent.toListKey(television: Boolean): ListKey? =
    listKey(key.nativeKeyCode, utf16CodePoint, isCtrlPressed, isAltPressed || isMetaPressed, television)

/** What this key types into the search: a letter, or a digit off a television; empty for any other key. */
internal fun KeyEvent.typedForSearch(television: Boolean): String = (toListKey(television) as? ListKey.Search)?.typed.orEmpty()

/** Scrolls as [key] asks, then gives focus to the first row in view, or to the last once the list is at its end. */
private suspend fun LazyListState.move(key: ListKey, screen: ScreenFocus) {
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    val shown = layoutInfo.visibleItemsInfo.size
    val target = when (key) {
        ListKey.PageUp -> pageTarget(firstVisibleItemIndex, shown, total, down = false)
        ListKey.PageDown -> pageTarget(firstVisibleItemIndex, shown, total, down = true)
        ListKey.Top -> 0
        ListKey.Bottom -> total - 1
        is ListKey.Search -> return
    }
    scrollToItem(target)
    val toLast = key == ListKey.Bottom || (key == ListKey.PageDown && !canScrollForward)
    repeat(LAND_FRAMES) {
        withFrameNanos {}
        if (keysInView(layoutInfo, toLast).any { screen.slots[it]?.tryFocus() == true }) return
    }
}

/** The keys of the items on screen, those shown whole first, from the bottom up when [fromLast]. */
private fun keysInView(info: LazyListLayoutInfo, fromLast: Boolean): List<String> {
    val (whole, cut) = info.visibleItemsInfo.partition { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
    val keys = (if (fromLast) whole.asReversed() else whole) + cut
    return keys.mapNotNull { it.key as? String }
}
