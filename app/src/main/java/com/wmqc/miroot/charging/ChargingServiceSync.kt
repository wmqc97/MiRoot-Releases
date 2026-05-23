package com.wmqc.miroot.charging

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 根据 Root/Shizuku 就绪状态与开关，启动或停止 [ChargingService]。
 */
object ChargingServiceSync {
    fun sync(context: Context, privileged: Boolean) {
        val app = context.applicationContext
        val intent = Intent(app, ChargingService::class.java)
        if (privileged && ChargingAnimationPrefs.isEnabled(app)) {
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (_: Exception) {
                app.startService(intent)
            }
        } else {
            app.stopService(intent)
        }
    }
}
