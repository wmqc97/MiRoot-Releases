package com.wmqc.miroot.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 系统通知监听：用户启用后，音乐投屏可读取活动通知与 MediaSession。
 */
class MiRootNotificationListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: MiRootNotificationListenerService? = null

        @JvmStatic
        fun getInstance(): MiRootNotificationListenerService? = instance
    }
}
