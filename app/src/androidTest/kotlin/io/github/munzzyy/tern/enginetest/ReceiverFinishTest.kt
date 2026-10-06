package io.github.munzzyy.tern.enginetest

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.install.InstallReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An installer's answer can arrive while the engine is closing. Android holds every later broadcast
 * to the app until this one is finished, and kills the app after a minute, so it has to finish.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverFinishTest {
    @Test
    fun anAnswerThatReachesAClosingEngineStillFinishes() {
        Harness("receiver-finish").use { h ->
            h.engine.scope.cancel()
            val finished = CountDownLatch(1)
            val thread = HandlerThread("receiver-finish").apply { start() }
            try {
                val intent = Intent(targetContext, InstallReceiver::class.java)
                    .setAction(InstallReceiver.ACTION)
                    .putExtra(InstallReceiver.EXTRA_APP_ID, "closing")
                    .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, 1)
                val last = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = finished.countDown()
                }
                targetContext.sendOrderedBroadcast(intent, null, last, Handler(thread.looper), Activity.RESULT_OK, null, null)
                assertTrue("the receiver never finished the broadcast", finished.await(10, TimeUnit.SECONDS))
            } finally {
                thread.quitSafely()
            }
        }
    }
}
