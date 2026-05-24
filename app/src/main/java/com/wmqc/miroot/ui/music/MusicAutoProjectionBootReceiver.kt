package com.wmqc.miroot.ui.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import com.wmqc.miroot.AppExecutors

/**
 * 开机后若已开启自动投屏，则拉起 [MusicAutoProjectionService]，避免从未进入应用时无法监听媒体会话。
 */
class MusicAutoProjectionBootReceiver : BroadcastReceiver() {

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
            if (!LyricsSettingsRepository.load(app).autoProjection) {
                pendingResult.finish()
                return@runInBackground
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

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = context.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked == true
    }
}
