package com.wmqc.miroot.rear

import android.content.Context

/**
 * 与 [com.wmqc.miroot.lyrics.RearScreenWakeService]、[ProjectionOnlyNotificationHelper] 通知 ID 一致。
 * 遮盖拉回主屏等场景下取消投屏相关通知。
 */
object ProjectionOngoingNotifications {
    const val WAKE_PROJECTION_COMBINED_NOTIF_ID = MiRootNotificationIds.MUSIC_OR_CAR_PROJECTION_NOTIFICATION_ID

    @JvmStatic
    fun cancelAll(ctx: Context) {
        MiRootNotificationIds.cancelBusinessProjectionNotifications(ctx)
    }
}
