package com.wmqc.miroot.ui.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * 开机后若已开启自动投屏，则拉起 [MusicAutoProjectionService]，避免从未进入应用时无法监听媒体会话。
 */
class MusicAutoProjectionBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val pendingResult = goAsync()
        thread(name = "MiRootMusicAutoBoot") {
            val app = context.applicationContext
            if (!LyricsSettingsRepository.load(app).autoProjection) {
                pendingResult.finish()
                return@thread
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    MusicAutoProjectionSync.sync(app)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
