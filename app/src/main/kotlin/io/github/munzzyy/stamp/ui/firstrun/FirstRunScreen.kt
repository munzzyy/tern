package io.github.munzzyy.stamp.ui.firstrun

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.ui.common.firstFocus
import io.github.munzzyy.stamp.ui.common.rememberScreenFocus
import io.github.munzzyy.stamp.ui.icons.Glyphs

const val FIRST_RUN_ADD_TAG = "first_run_add"

@Composable
fun FirstRunScreen(onAddFirst: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var granted by remember {
        mutableStateOf(!needsAsking || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val screen = rememberScreenFocus()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(horizontal = 24.dp)
                .widthIn(max = 600.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
            ) {
                Text(
                    stringResource(R.string.first_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Line(Glyphs.Download, stringResource(R.string.first_line_1))
                Line(Icons.Filled.CheckCircle, stringResource(R.string.first_line_2))
                Line(Glyphs.Activity, stringResource(R.string.first_line_3))
                if (!granted) {
                    NotificationAsk(asked, onAsk = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) })
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            ) {
                Button(onClick = onAddFirst, modifier = Modifier.fillMaxWidth().testTag(FIRST_RUN_ADD_TAG).firstFocus(screen)) {
                    Text(stringResource(R.string.action_add_first))
                }
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.first_skip))
                }
            }
        }
    }
}

@Composable
private fun NotificationAsk(asked: Boolean, onAsk: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Line(Icons.Filled.Notifications, stringResource(if (asked) R.string.first_notify_denied else R.string.first_notify_explain))
            if (!asked) {
                OutlinedButton(onClick = onAsk) { Text(stringResource(R.string.first_notify_allow)) }
            }
        }
    }
}

@Composable
private fun Line(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
