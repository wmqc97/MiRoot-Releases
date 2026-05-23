package com.wmqc.miroot.rear.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wmqc.miroot.lyrics.LogHelper

/** 外部广播入口：在背屏打开 [RearScreenDesktopActivity]（与设置页「在背屏打开桌面」一致）。 */
class RearDesktopBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val intentAction = intent?.action
        val ts = System.currentTimeMillis()
        if (intentAction != RearDesktopIntents.ACTION_OPEN_REAR_DESKTOP) {
            LogHelper.d(
                TAG,
                "onReceive ignored ts=$ts action=$intentAction expected=${RearDesktopIntents.ACTION_OPEN_REAR_DESKTOP}",
            )
            return
        }
        LogHelper.d(TAG, "onReceive ACTION_OPEN_REAR_DESKTOP ts=$ts extras=${intent?.extras}")
        try {
            val app = context.applicationContext
            app.startService(
                Intent(app, RearDesktopLaunchService::class.java).apply {
                    setAction(RearDesktopIntents.ACTION_OPEN_REAR_DESKTOP)
                },
            )
            LogHelper.d(TAG, "onReceive startService(RearDesktopLaunchService) returned ts=$ts")
        } catch (e: Exception) {
            LogHelper.e(TAG, "start RearDesktopLaunchService failed", e)
        }
    }

    private companion object {
        private const val TAG = "RearDesktopRcvr"
    }
}
