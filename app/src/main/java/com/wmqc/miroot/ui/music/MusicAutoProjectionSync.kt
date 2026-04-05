package com.wmqc.miroot.ui.music

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 根据「自动投屏」开关启动或停止 [MusicAutoProjectionService]。
 */
object MusicAutoProjectionSync {
    fun sync(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, MusicAutoProjectionService::class.java)
        if (LyricsSettingsRepository.load(app).autoProjection) {
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (_: Exception) {
                try {
                    app.startService(intent)
                } catch (_: Exception) {
                }
            }
        } else {
            app.stopService(intent)
        }
    }
}
