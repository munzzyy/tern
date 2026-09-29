package io.github.munzzyy.tern.ui.firstrun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.common.PrimaryButton
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.firstFocus
import io.github.munzzyy.tern.ui.common.rememberScreenFocus
import io.github.munzzyy.tern.ui.icons.Bell
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.Seal
import io.github.munzzyy.tern.ui.theme.LocalLook

const val FIRST_RUN_ADD_TAG = "first_run_add"
const val FIRST_RUN_NOTIFY_TAG = "first_run_notify"

/** Android asks for the permission to notify from this version on. */
private const val ASKS_FROM_SDK = Build.VERSION_CODES.TIRAMISU

/**
 * Whether the first run asks about notifications: only where Android wants to be asked, where
 * the answer is not yes already, and where the user would see one. A television shows none.
 */
fun asksAboutNotifications(television: Boolean, sdk: Int, granted: Boolean): Boolean = !television && sdk >= ASKS_FROM_SDK && !granted

/** Before Android 13 nobody has to be asked. */
fun mayNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < ASKS_FROM_SDK ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** [mayNotify] is what Android says about notifications as the screen opens. A test says it itself, since it cannot take the permission back. */
@Composable
fun FirstRunScreen(
    onAddFirst: () -> Unit,
    onSkip: () -> Unit,
    onWellKnown: () -> Unit = onAddFirst,
    mayNotify: Boolean = mayNotify(LocalContext.current),
) {
    val look = LocalLook.current
    var granted by remember { mutableStateOf(mayNotify) }
    var asked by remember { mutableStateOf(false) }
    val screen = rememberScreenFocus()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    val asks = asksAboutNotifications(look.television, Build.VERSION.SDK_INT, granted)
    val words: @Composable () -> Unit = {
        Seal(size = look.iconHeader, color = MaterialTheme.colorScheme.primary, press = false)
        Text(
            stringResource(R.string.first_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(look.gap)) {
            Line(Glyphs.Download, stringResource(R.string.first_line_1))
            Line(Glyphs.Seal, stringResource(R.string.first_line_2))
            Line(Glyphs.Activity, stringResource(R.string.first_line_3))
        }
        if (asks) {
            NotificationAsk(asked, onAsk = { if (Build.VERSION.SDK_INT >= ASKS_FROM_SDK) ask.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }
    }
    val actions: @Composable () -> Unit = {
        PrimaryButton(stringResource(R.string.action_add_first), onAddFirst, Modifier.fillMaxWidth().testTag(FIRST_RUN_ADD_TAG).firstFocus(screen))
        TonalButton(stringResource(R.string.first_well_known), onWellKnown, Modifier.fillMaxWidth())
        QuietButton(stringResource(R.string.first_skip), onSkip, Modifier.fillMaxWidth())
    }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
            if (maxWidth >= look.contentMaxWidth) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(look.gapSection * 2),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .widthIn(max = look.contentMaxWidth + look.contentMaxWidth / 4)
                        .fillMaxHeight()
                        .padding(horizontal = look.screenPadding + look.gapSmall),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(look.gap, Alignment.CenterVertically),
                        modifier = Modifier
                            .weight(3f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = look.gap),
                    ) { words() }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(look.focusRoom),
                        modifier = Modifier.weight(2f).padding(horizontal = look.focusRoom),
                    ) { actions() }
                }
            } else {
                Column(
                    Modifier
                        .widthIn(max = look.contentMaxWidth * 3 / 4)
                        .fillMaxSize()
                        .padding(horizontal = look.screenPadding + look.gapSmall),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(look.gap, Alignment.CenterVertically),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = look.gapSection),
                    ) { words() }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(look.focusRoom),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = look.focusRoom)
                            .padding(bottom = look.gap),
                    ) { actions() }
                }
            }
        }
    }
}

@Composable
private fun NotificationAsk(asked: Boolean, onAsk: () -> Unit) {
    val look = LocalLook.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag(FIRST_RUN_NOTIFY_TAG),
    ) {
        Column(Modifier.padding(look.cardPadding), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
            Line(Glyphs.Bell, stringResource(if (asked) R.string.first_notify_denied else R.string.first_notify_explain))
            if (!asked) {
                TonalButton(stringResource(R.string.first_notify_allow), onAsk, Modifier.padding(start = look.glyph + look.gap))
            }
        }
    }
}

@Composable
private fun Line(icon: ImageVector, text: String) {
    val look = LocalLook.current
    Row(horizontalArrangement = Arrangement.spacedBy(look.gap)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(look.glyph))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
