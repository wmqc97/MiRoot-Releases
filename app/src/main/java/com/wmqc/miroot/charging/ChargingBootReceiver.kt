package com.wmqc.miroot.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import androidx.core.content.ContextCompat
import com.wmqc.miroot.AppExecutors
import com.wmqc.miroot.capability.EnvironmentProbe

/**
 * 开机后若用户已开启充电动画且特权通道可用，则拉起 [ChargingService]，
 * 避免「从未进过应用 → 动态注册收不到插电」导致概率性不触发。
 */
class ChargingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED
            && action != Intent.ACTION_LOCKED_BOOT_COMPLETED
            && action != Intent.ACTION_USER_UNLOCKED
        ) {
            return
        }
        val pendingResult = goAsync()
        AppExecutors.runInBackground {
            val app = context.applicationContext
            if (!isUserUnlocked(app)) {
                pendingResult.finish()
                return@runInBackground
            }
            if (!ChargingAnimationPrefs.isEnabled(app)) {
                pendingResult.finish()
                return@runInBackground
            }
            if (!EnvironmentProbe.hasPrivilegedShellChannelSync()) {
                pendingResult.finish()
                return@runInBackground
            }
            // 在主线程排队启动 FGS，减少与系统调度乱序导致 onCreate 延迟、触发超时杀进程的概率。
            Handler(Looper.getMainLooper()).post {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, ChargingService::class.java))
                } catch (_: Exception) {
                    try {
                        app.startService(Intent(app, ChargingService::class.java))
                    } catch (_: Exception) {
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = context.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked == true
    }
}
