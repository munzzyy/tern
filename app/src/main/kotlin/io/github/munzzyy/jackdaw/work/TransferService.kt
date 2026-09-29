package io.github.munzzyy.jackdaw.work

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TransferService : Service() {
    override fun onBind(intent: Intent): IBinder? = null
}
