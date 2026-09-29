package io.github.munzzyy.stamp.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.munzzyy.stamp.data.SettingsStore

/** Puts the periodic check back after a reboot or an update of Stamp itself. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Scheduler.apply(context, SettingsStore(context).load())
    }
}
