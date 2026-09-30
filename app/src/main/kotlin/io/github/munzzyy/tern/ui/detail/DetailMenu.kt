package io.github.munzzyy.tern.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.interop.ConfigLink
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.common.ChoiceDialog
import io.github.munzzyy.tern.ui.common.GlyphButton
import io.github.munzzyy.tern.ui.common.LinkDialog
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.shareFile
import io.github.munzzyy.tern.ui.common.shareText
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.More
import io.github.munzzyy.tern.ui.icons.Star
import io.github.munzzyy.tern.ui.icons.StarFilled
import io.github.munzzyy.tern.ui.theme.status
import androidx.compose.material3.MaterialTheme

const val DETAIL_FAVORITE_TAG = "detail_favorite"
const val DETAIL_MENU_TAG = "detail_menu"

/** A favourite stays at the top of the list, whatever the order. */
@Composable
fun FavoriteButton(row: AppRow) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val on = row.config.favorite
    GlyphButton(
        if (on) Glyphs.StarFilled else Glyphs.Star,
        stringResource(if (on) R.string.action_unfavorite else R.string.action_favorite),
        onClick = { actions.run { engine.configure(row.id) { it.copy(favorite = !on) } } },
        tint = if (on) MaterialTheme.status.caution.color else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(DETAIL_FAVORITE_TAG),
    )
}

/** What there is to do with an app besides its main action: share it, see its release, and leave for Android's own pages. */
@Composable
fun DetailMenu(row: AppRow) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val context = LocalContext.current
    val online = LocalOnline.current
    var open by remember { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.action_share)
    val noScreen = stringResource(R.string.action_failed)
    val page = row.latest?.pageUrl
    val settingsLink = remember(row.config) { ConfigLink.tern(row.config) }
    val obtainiumLink = remember(row.config) { ConfigLink.web(row.config) }
    val noLink = stringResource(R.string.share_link_none)
    Box {
        GlyphButton(Glyphs.More, stringResource(R.string.action_more), onClick = { open = true }, modifier = Modifier.testTag(DETAIL_MENU_TAG))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.focusHighlight()) {
            val item: @Composable (Int, Boolean, () -> Unit) -> Unit = { label, enabled, run ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled, onClick = { open = false; run() })
            }
            item(R.string.action_check_now, online) { actions.run { engine.check(row.id) } }
            if (page != null) item(R.string.action_release_page, true) { link = page }
            HorizontalDivider()
            item(R.string.action_share_address, true) { shareText(context, shareTitle, row.config.source.url) }
            item(R.string.action_share_link, true) { shareText(context, shareTitle, ConfigLink.ternAddress(row.config.source.url)) }
            item(R.string.action_share_config_link, true) { settingsLink?.let { shareText(context, shareTitle, it) } ?: actions.say(noLink) }
            item(R.string.share_obtainium_link, true) { obtainiumLink?.let { shareText(context, shareTitle, it) } ?: actions.say(noLink) }
            item(R.string.action_share_export, true) { exporting = true }
            if (row.installed != null) {
                HorizontalDivider()
                item(R.string.action_app_info, true) { if (!openAppInfo(context, row.installed.packageName)) actions.say(noScreen) }
                item(R.string.action_uninstall, true) { engine.uninstall(row.id) }
            }
        }
    }
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
    if (exporting) {
        ChoiceDialog(
            title = stringResource(R.string.action_share_export),
            options = ExportFormat.entries,
            selected = ExportFormat.TERN,
            label = { stringResource(if (it == ExportFormat.TERN) R.string.export_format_tern else R.string.export_format_obtainium) },
            onDismiss = { exporting = false },
        ) { format ->
            exporting = false
            actions.run { shareFile(context, shareTitle, engine.shareableExport(listOf(row.id), format)) }
        }
    }
}

private fun openAppInfo(context: android.content.Context, packageName: String): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
