package io.github.munzzyy.tern.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

fun openNotificationSettings(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Puts [text] on the clipboard, for a device where no app takes a share, as most TVs are. */
fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Offers [text] to whatever app the person picks. False when no app takes text. */
fun shareText(context: Context, title: String, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    return share(context, Intent.createChooser(send, title))
}

/** Offers the file at [uri], read-only, to whatever app the person picks. */
fun shareFile(context: Context, title: String, uri: android.net.Uri, type: String = "application/json"): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return share(context, Intent.createChooser(send, title))
}

private fun share(context: Context, chooser: Intent): Boolean = try {
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    true
} catch (_: ActivityNotFoundException) {
    false
}
