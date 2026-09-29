package io.github.munzzyy.stamp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.FileChoice
import io.github.munzzyy.stamp.ui.icons.Glyphs
import io.github.munzzyy.stamp.ui.text.formatBytes
import io.github.munzzyy.stamp.ui.text.isolate
import io.github.munzzyy.stamp.ui.text.ltr
import io.github.munzzyy.stamp.ui.theme.LocalLook
import io.github.munzzyy.stamp.ui.theme.figures

@Composable
fun FileChoiceView(choice: FileChoice, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
        SelectionContainer {
            Text(ltr(choice.asset.name), style = MaterialTheme.typography.bodyLarge)
        }
        choice.asset.size?.let {
            Text(isolate(formatBytes(it)), style = MaterialTheme.typography.bodySmall.figures(), color = quiet)
        }
        if (choice.reasons.isNotEmpty()) {
            Text(
                stringResource(R.string.file_why),
                style = MaterialTheme.typography.labelLarge,
                color = quiet,
                modifier = Modifier.padding(top = look.gapSmall / 2),
            )
            for (reason in choice.reasons) {
                Row(horizontalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                    Icon(Glyphs.Check, contentDescription = null, tint = quiet, modifier = Modifier.padding(top = look.gapSmall / 4).size(look.glyphSmall))
                    Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
