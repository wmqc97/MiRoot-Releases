package com.wmqc.miroot.rear.heartrate

import android.content.Context
import android.os.Build
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.display.MainDisplayUi
import com.wmqc.miroot.lyrics.DisplayInfoCache
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.OfficialSubscreenMiRootProjectionSession
import com.wmqc.miroot.rear.RearDisplayOpenHelper
import com.wmqc.miroot.rear.RearProjectionLaunchSequence
import com.wmqc.miroot.rear.desktop.RearDesktopLaunchHelper

object RearHeartRateLaunchHelper {

    const val REAR_DISPLAY_ID: Int = RearDesktopLaunchHelper.REAR_DISPLAY_ID

    /**
     * 统一入口：蓝牙未就绪时在主屏弹出授权，就绪后点亮背屏并启动。
     */
    fun requestOpenHeartRate(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            MainDisplayUi.showToast(app, R.string.rear_heart_rate_need_api26, Toast.LENGTH_SHORT)
            return false
        }
        if (!HeartRateBleGate.isReady(app)) {
            HeartRateBleGate.startMainDisplayPermissionFlow(app)
            return true
        }
        return launchHeartRateAfterBleReady(app)
    }

    /** 蓝牙权限与开关已就绪，点亮背屏并启动心率（权限页完成后调用）。 */
    fun launchHeartRateAfterBleReady(context: Context): Boolean {
        val app = context.applicationContext
        return RearDisplayOpenHelper.startLaunchService(
            app,
            RearHeartRateLaunchService::class.java,
            RearHeartRateIntents.ACTION_OPEN_HEART_RATE,
        )
    }

    fun startHeartRateOnRearDisplayWithTaskService(taskService: ITaskService, context: Context): Boolean {
        val app = context.applicationContext
        return try {
            try {
                if (!DisplayInfoCache.getInstance().isInitialized) {
                    DisplayInfoCache.getInstance().initialize(taskService)
                }
            } catch (e: Exception) {
                LogHelper.w(TAG, "DisplayInfoCache init (ignore): ${e.message}")
            }
            val component = "${app.packageName}/${RearHeartRateActivity::class.java.name}"
            val fromService = context is android.app.Service
            val spec =
                RearProjectionLaunchSequence.openOnRearLaunchSpec(
                    "RearHeartRateActivity",
                    component,
                    fromService,
                    REAR_DISPLAY_ID,
                )
            val ok =
                RearProjectionLaunchSequence.runPreferDirectRearLaunchWithPlaceholderFallback(
                    context,
                    taskService,
                    spec,
                    null,
                )
            LogHelper.d(TAG, "heart rate launch ok=$ok")
            ok
        } catch (e: Exception) {
            LogHelper.d(TAG, "startHeartRateOnRearDisplayWithTaskService failed", e)
            OfficialSubscreenMiRootProjectionSession.release(app, taskService)
            false
        }
    }

    fun startHeartRateOnRearDisplay(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            MainDisplayUi.showToast(app, R.string.rear_heart_rate_need_api26, Toast.LENGTH_SHORT)
            return false
        }
        if (!RearDisplayOpenHelper.prepareRearDisplayForOpen(app)) {
            MainDisplayUi.showToast(app, R.string.rear_heart_rate_display_unavailable, Toast.LENGTH_SHORT)
            return false
        }
        return if (RearDisplayOpenHelper.startActivityOnRearDisplay(app, RearHeartRateActivity::class.java)) {
            true
        } else {
            MainDisplayUi.showToast(app, R.string.rear_heart_rate_start_failed, Toast.LENGTH_SHORT)
            false
        }
    }

    private const val TAG = "RearHeartRateLaunch"
}
