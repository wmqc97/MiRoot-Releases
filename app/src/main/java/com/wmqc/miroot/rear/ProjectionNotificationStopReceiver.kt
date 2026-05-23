package com.wmqc.miroot.rear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wmqc.miroot.car.RearScreenCarControlActivity
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity

/**
 * 通知栏「结束投屏」走广播而非 [PendingIntent.getActivity]，避免系统把投屏 Activity 拉到主屏闪一下。
 */
class ProjectionNotificationStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            ACTION_STOP_MUSIC_PROJECTION_FROM_NOTIFICATION ->
                RearScreenLyricsActivity.finishProjectionFromNotificationTap(app)
            ACTION_STOP_CAR_PROJECTION_FROM_NOTIFICATION ->
                RearScreenCarControlActivity.finishProjectionFromNotificationTap(app)
        }
    }

    companion object {
        const val ACTION_STOP_MUSIC_PROJECTION_FROM_NOTIFICATION =
            "com.wmqc.miroot.action.STOP_MUSIC_PROJECTION_FROM_NOTIFICATION"
        const val ACTION_STOP_CAR_PROJECTION_FROM_NOTIFICATION =
            "com.wmqc.miroot.action.STOP_CAR_PROJECTION_FROM_NOTIFICATION"
    }
}
