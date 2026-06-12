package com.wmqc.miroot.rear.heartrate

import android.app.IntentService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.wmqc.miroot.rear.RearDisplayOpenHelper
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskServiceConnector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 从手势广播或外部 Intent 唤起背屏心率广播显示。
 */
@Suppress("DEPRECATION")
class RearHeartRateLaunchService : IntentService("RearHeartRateLaunchService") {

    private var wakeLock: PowerManager.WakeLock? = null

    @Deprecated("Deprecated in Java")
    override fun onCreate() {
        super.onCreate()
        RootTaskServiceConnector.prewarm(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null || intent.action != RearHeartRateIntents.ACTION_OPEN_HEART_RATE) {
            return
        }
        if (!HeartRateBleGate.isReady(this)) {
            Handler(Looper.getMainLooper()).post {
                HeartRateBleGate.startMainDisplayPermissionFlow(applicationContext)
            }
            return
        }
        acquireWakeLock(30_000L)
        try {
            RearDisplayOpenHelper.wakeRearDisplay()
            val ts = RootTaskServiceConnector.ensureConnected(this, 2_500L)
            val privilegedOk =
                if (ts != null) {
                    RearHeartRateLaunchHelper.startHeartRateOnRearDisplayWithTaskService(ts, this)
                } else {
                    false
                }
            if (privilegedOk) return

            val latch = CountDownLatch(1)
            val launchOk = AtomicReference<Boolean?>(null)
            Handler(Looper.getMainLooper()).post {
                try {
                    launchOk.set(RearHeartRateLaunchHelper.startHeartRateOnRearDisplay(applicationContext))
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
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:RearHeartRateLaunchWake")
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
        private const val TAG = "RearHeartRateLaunchSvc"
    }
}
