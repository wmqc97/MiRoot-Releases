package com.wmqc.miroot.rear

import android.app.NotificationManager
import android.content.Context

/**
 * 与 [com.wmqc.miroot.lyrics.RearScreenWakeService]、[ProjectionOnlyNotificationHelper] 通知 ID 一致。
 * 遮盖拉回主屏等场景下取消投屏相关通知。
 */
object ProjectionOngoingNotifications {
    const val WAKE_PROJECTION_COMBINED_NOTIF_ID = 10003

    @JvmStatic
    fun cancelAll(ctx: Context) {
        val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.cancel(WAKE_PROJECTION_COMBINED_NOTIF_ID)
        nm.cancel(ProjectionOnlyNotificationHelper.MUSIC_PROJECTION_ONLY_ID)
        nm.cancel(ProjectionOnlyNotificationHelper.CAR_PROJECTION_ONLY_ID)
    }
}
