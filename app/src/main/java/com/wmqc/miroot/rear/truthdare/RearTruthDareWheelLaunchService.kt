package com.wmqc.miroot.rear.truthdare

import android.app.IntentService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskServiceConnector
import com.wmqc.miroot.rear.RearDisplayOpenHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class RearTruthDareWheelLaunchService : IntentService("RearTruthDareWheelLaunchService") {

    private var wakeLock: PowerManager.WakeLock? = null

    @Deprecated("Deprecated in Java")
    override fun onCreate() {
        super.onCreate()
        RootTaskServiceConnector.prewarm(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null || intent.action != RearTruthDareWheelIntents.ACTION_OPEN_TRUTH_DARE_WHEEL) {
            return
        }
        acquireWakeLock(30_000L)
        try {
            RearDisplayOpenHelper.wakeRearDisplay()
            val ts = RootTaskServiceConnector.ensureConnected(this, 2_500L)
            val privilegedOk =
                if (ts != null) {
                    RearTruthDareWheelLaunchHelper.startTruthDareWheelOnRearDisplayWithTaskService(ts, this)
                } else {
                    false
                }
            if (privilegedOk) return

            val latch = CountDownLatch(1)
            val launchOk = AtomicReference<Boolean?>(null)
            Handler(Looper.getMainLooper()).post {
                try {
                    launchOk.set(RearTruthDareWheelLaunchHelper.startTruthDareWheelOnRearDisplay(applicationContext))
                } finally {
                    latch.countDown()
                }
            }
            latch.await(8, TimeUnit.SECONDS)
            LogHelper.d(TAG, "launch done fallback=${launchOk.get()}")
        } finally {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:RearTruthDareLaunchWake")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeoutMs)
            }
        } catch (t: Throwable) {
            LogHelper.w(TAG, "wakelock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (t: Throwable) {
            LogHelper.w(TAG, "wakelock release: ${t.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "RearTruthDareLaunchSvc"
    }
}
