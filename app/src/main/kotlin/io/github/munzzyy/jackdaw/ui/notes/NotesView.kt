package io.github.munzzyy.jackdaw.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.engine.NoteBlock
import io.github.munzzyy.jackdaw.engine.NoteSpan
import io.github.munzzyy.jackdaw.ui.common.LinkDialog

@Composable
fun NotesView(blocks: List<NoteBlock>, modifier: Modifier = Modifier) {
    var pending by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) { NoteColors(link = scheme.primary, codeBackground = scheme.surfaceContainerHighest) }
    val onLink: (String) -> Unit = { pending = it }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) NoteBlockView(block, colors, onLink)
        }
    }
    pending?.let { url -> LinkDialog(url = url, onDismiss = { pending = null }) }
}

@Composable
private fun NoteBlockView(block: NoteBlock, colors: NoteColors, onLink: (String) -> Unit) {
    val type = MaterialTheme.typography
    when (block) {
        is NoteBlock.Heading -> Text(
            rememberAnnotated(block.spans, colors, onLink),
            style = when (block.level) {
                1 -> type.titleLarge
                2 -> type.titleMedium
                else -> type.titleSmall
            },
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        is NoteBlock.Paragraph -> Text(rememberAnnotated(block.spans, colors, onLink), style = type.bodyMedium)
        is NoteBlock.ListItem -> Row(Modifier.padding(start = (16 * block.depth.coerceIn(0, 6)).dp)) {
            Text(
                listMarker(block.ordered, block.number, block.depth),
                style = type.bodyMedium,
                modifier = Modifier.width(if (block.ordered) 32.dp else 20.dp),
            )
            Text(rememberAnnotated(block.spans, colors, onLink), style = type.bodyMedium)
        }
        is NoteBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(block.text, style = type.bodySmall, fontFamily = FontFamily.Monospace, softWrap = false)
        }
        is NoteBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
            )
            Text(
                rememberAnnotated(block.spans, colors, onLink),
                style = type.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        NoteBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun rememberAnnotated(spans: List<NoteSpan>, colors: NoteColors, onLink: (String) -> Unit) =
    remember(spans, colors) { annotate(spans, colors, onLink) }
