package com.wmqc.miroot.rear.heartrate

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Display

/** 心率广播蓝牙就绪检查；缺权限/蓝牙时在主屏弹出授权页。 */
object HeartRateBleGate {

    fun isReady(context: Context): Boolean {
        val app = context.applicationContext
        return BlePermissionHelper.hasAll(app) && BleHeartRateMonitor(app).isBluetoothEnabled()
    }

    fun startMainDisplayPermissionFlow(context: Context) {
        val app = context.applicationContext
        val intent =
            Intent(app, HeartRateBlePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            app.startActivity(intent, opts.toBundle())
        } else {
            app.startActivity(intent)
        }
    }
}
