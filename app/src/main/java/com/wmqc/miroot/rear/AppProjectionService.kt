package com.wmqc.miroot.rear

import android.app.IntentService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskService

/**
 * 处理 [AppProjectionIntents.ACTION_APP_PROJECTION]：迁屏或请求迁回主屏（与迁屏 Keeper 通知一致）。
 */
@Suppress("DEPRECATION")
class AppProjectionService : IntentService("AppProjectionService") {

    private var taskService: ITaskService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val taskConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            taskService = ITaskService.Stub.asInterface(binder)
            LogHelper.d(TAG, "TaskService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            taskService = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCreate() {
        super.onCreate()
        bindTaskService()
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleIntent(intent: Intent?) {
        acquireWakeLock(30_000)
        try {
            if (intent == null || intent.action != AppProjectionIntents.ACTION_APP_PROJECTION) return

            var op = intent.getStringExtra(AppProjectionIntents.EXTRA_APP_PROJECTION_OP)
            if (op.isNullOrEmpty()) {
                op = AppProjectionIntents.OP_START
            }

            if (op.equals(AppProjectionIntents.OP_STOP, ignoreCase = true)) {
                val returnIntent = Intent(applicationContext, RearSwitchKeeperService::class.java).apply {
                    action = RearSwitchKeeperService.ACTION_RETURN_TO_MAIN
                }
                try {
                    startService(returnIntent)
                } catch (e: Exception) {
                    LogHelper.w(TAG, "return to main: ${e.message}")
                }
                return
            }

            if (!op.equals(AppProjectionIntents.OP_START, ignoreCase = true)) {
                LogHelper.w(TAG, "unknown op: $op")
                return
            }

            if (!ensureTaskServiceConnected()) {
                LogHelper.w(TAG, "TaskService not ready")
                return
            }

            val ts = taskService ?: return
            val mainHandler = Handler(Looper.getMainLooper())
            ForegroundAppRearSwitcher.switchCurrentForegroundToRear(
                this,
                ts,
                mainHandler,
                onTileSubtitle = null,
            )
        } finally {
            releaseWakeLock()
        }
    }

    private fun bindTaskService() {
        if (taskService != null) return
        try {
            bindService(Intent(this, RootTaskService::class.java), taskConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            LogHelper.e(TAG, "bind RootTaskService failed", e)
        }
    }

    private fun ensureTaskServiceConnected(): Boolean {
        if (taskService != null) return true
        bindTaskService()
        var attempts = 0
        val maxAttempts = 20
        while (taskService == null && attempts < maxAttempts) {
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            attempts++
        }
        return taskService != null
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:AppProjectionWake")
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
        try {
            unbindService(taskConnection)
        } catch (_: Exception) {
        }
        taskService = null
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "AppProjectionSvc"
    }
}
