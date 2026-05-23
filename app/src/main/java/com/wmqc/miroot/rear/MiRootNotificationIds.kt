package com.wmqc.miroot.rear

import android.app.NotificationManager
import android.content.Context

/**
 * MiRoot 通知固定 ID 集中管理：
 * 1) 主服务通知
 * 2) 应用投屏通知
 * 3) 音乐/车控投屏通知（互斥共用一个 ID）
 * 4) 始终常亮通知
 */
object MiRootNotificationIds {
    const val MAIN_SERVICE_NOTIFICATION_ID = 10031
    const val APP_PROJECTION_NOTIFICATION_ID = 10001
    const val MUSIC_OR_CAR_PROJECTION_NOTIFICATION_ID = 10003
    const val ALWAYS_WAKE_NOTIFICATION_ID = 10021

    @JvmStatic
    fun cancelBusinessProjectionNotifications(ctx: Context) {
        val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.cancel(APP_PROJECTION_NOTIFICATION_ID)
        nm.cancel(MUSIC_OR_CAR_PROJECTION_NOTIFICATION_ID)
    }
}
