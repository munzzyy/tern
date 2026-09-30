package io.github.munzzyy.tern.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/** Back, title and actions on one line while the title's longest word fits there; otherwise the title goes under them, never broken inside a word. */
@Composable
internal fun TopLine(
    back: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    gap: Dp,
    minHeight: Dp,
    below: Dp,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(back, title, { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap), content = actions) }),
        modifier = modifier,
    ) { (backs, titles, actionRows), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val lineHeight = minHeight.roundToPx()
        val backPlaced = backs.map { it.measure(loose) }
        val actionsPlaced = actionRows.single().measure(loose)
        val backWidth = backPlaced.sumOf { it.width + gapPx }
        val actionsWidth = if (actionsPlaced.width > 0) actionsPlaced.width + gapPx else 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else backWidth + actionsWidth + titles.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val room = (width - backWidth - actionsWidth).coerceAtLeast(0)
        val words = titles.single()
        if (words.minIntrinsicWidth(Constraints.Infinity) <= room) {
            val titlePlaced = words.measure(Constraints(minWidth = room, maxWidth = room))
            val height = maxOf(lineHeight, titlePlaced.height, actionsPlaced.height, backPlaced.maxOfOrNull { it.height } ?: 0)
            layout(width, height) {
                var x = 0
                for (b in backPlaced) {
                    b.placeRelative(x, (height - b.height) / 2)
                    x += b.width + gapPx
                }
                titlePlaced.placeRelative(x, (height - titlePlaced.height) / 2)
                actionsPlaced.placeRelative(width - actionsPlaced.width, (height - actionsPlaced.height) / 2)
            }
        } else {
            val titlePlaced = words.measure(Constraints(maxWidth = width))
            val top = maxOf(lineHeight, actionsPlaced.height, backPlaced.maxOfOrNull { it.height } ?: 0)
            val belowPx = below.roundToPx()
            layout(width, top + titlePlaced.height + belowPx) {
                var x = 0
                for (b in backPlaced) {
                    b.placeRelative(x, (top - b.height) / 2)
                    x += b.width + gapPx
                }
                actionsPlaced.placeRelative(width - actionsPlaced.width, (top - actionsPlaced.height) / 2)
                titlePlaced.placeRelative(0, top)
            }
        }
    }
}
