package com.wmqc.miroot.rear.desktop

import android.app.IntentService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskServiceConnector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 从广播经 [RearDesktopBroadcastReceiver] 唤起。
 *
 * 与 [com.wmqc.miroot.rear.AppProjectionService] / [com.wmqc.miroot.lyrics.MusicProjectionService] 对齐：
 * 绑定 [com.wmqc.miroot.lyrics.RootTaskService] 后走「唤醒背屏 + am start --display 1」稳定链路；TaskService 未就绪时再回退到
 * [RearDesktopLaunchHelper.startDesktopOnRearDisplay]（主线程 ActivityOptions）。
 */
@Suppress("DEPRECATION")
class RearDesktopLaunchService : IntentService("RearDesktopLaunchService") {

    private var wakeLock: PowerManager.WakeLock? = null

    @Deprecated("Deprecated in Java")
    override fun onCreate() {
        super.onCreate()
        RootTaskServiceConnector.prewarm(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null || intent.action != RearDesktopIntents.ACTION_OPEN_REAR_DESKTOP) {
            LogHelper.d(
                TAG,
                "onHandleIntent skip intentNull=${intent == null} action=${intent?.action}",
            )
            return
        }
        LogHelper.d(TAG, "onHandleIntent begin")
        acquireWakeLock(30_000L)
        try {
            val ts = RootTaskServiceConnector.ensureConnected(this, 2_500L)
            val privilegedOk =
                if (ts != null) {
                    val shellOk =
                        RearDesktopLaunchHelper.startDesktopOnRearDisplayWithTaskService(
                            ts,
                            this,
                        )
                    LogHelper.d(TAG, "privileged shell launch ok=$shellOk")
                    shellOk
                } else {
                    LogHelper.w(TAG, "TaskService not ready")
                    false
                }

            if (privilegedOk) {
                return
            }

            LogHelper.d(TAG, "fallback to ActivityOptions on main thread")
            val latch = CountDownLatch(1)
            val launchOk = AtomicReference<Boolean?>(null)
            Handler(Looper.getMainLooper()).post {
                try {
                    launchOk.set(RearDesktopLaunchHelper.startDesktopOnRearDisplay(applicationContext))
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(8, TimeUnit.SECONDS)) {
                LogHelper.w(TAG, "rear desktop launch wait timeout")
            }
            LogHelper.d(TAG, "onHandleIntent done fallback startDesktopOnRearDisplay=${launchOk.get()}")
        } finally {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:RearDesktopLaunchWake")
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
        private const val TAG = "RearDesktopLaunchSvc"
    }
}
