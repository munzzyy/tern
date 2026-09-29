package io.github.munzzyy.jackdaw.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.FileChoice
import io.github.munzzyy.jackdaw.ui.text.formatBytes
import io.github.munzzyy.jackdaw.ui.text.isolate

@Composable
fun FileChoiceView(choice: FileChoice, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(choice.asset.name, style = MaterialTheme.typography.bodyLarge)
        }
        choice.asset.size?.let {
            Text(isolate(formatBytes(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (choice.reasons.isNotEmpty()) {
            Text(
                stringResource(R.string.file_why),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            for (reason in choice.reasons) {
                Row {
                    Text("•", modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
