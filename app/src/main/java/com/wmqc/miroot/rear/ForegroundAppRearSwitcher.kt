package com.wmqc.miroot.rear

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LyricsTaskTracking
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.RearAssistPrefs
import com.wmqc.miroot.shell.SwitchToRearQsTileService
import kotlin.concurrent.thread

/**
 * 将主屏当前前台应用迁到背屏（与 [SwitchToRearQsTileService] 磁贴逻辑一致），供磁贴与外部广播共用。
 */
object ForegroundAppRearSwitcher {

    private const val TAG = "FgAppRearSwitcher"

    private fun getAppName(ctx: Context, packageName: String): String =
        try {
            val pm = ctx.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().ifEmpty { packageName }
        } catch (e: Exception) {
            LogHelper.w(TAG, "Failed to get app name: ${e.message}")
            packageName
        }

    /**
     * @param mainHandler 用于 Toast、与磁贴 UI 反馈；仅后台时可传 [Handler]（[Looper.getMainLooper]）
     */
    fun switchCurrentForegroundToRear(
        ctx: Context,
        ts: ITaskService,
        mainHandler: Handler,
        onTileSubtitle: ((String) -> Unit)? = null,
    ) {
        val appCtx = ctx.applicationContext
        val rearDisplayId = 1

        fun feedback(msg: String) {
            onTileSubtitle?.invoke(msg) ?: Unit
        }

        try {
            val prev = SwitchToRearQsTileService.getLastMovedTask()
            if (prev != null && prev.contains(":")) {
                try {
                    val oldParts = prev.split(":")
                    val oldPackageName = oldParts[0]
                    val rearForegroundApp = ts.getForegroundAppOnDisplay(rearDisplayId)
                    if (rearForegroundApp != null && rearForegroundApp == prev) {
                        val oldAppName = getAppName(appCtx, oldPackageName)
                        try {
                            ts.collapseStatusBar()
                        } catch (e: Exception) {
                            LogHelper.w(TAG, "Failed to collapse for toast: ${e.message}")
                        }
                        mainHandler.postDelayed({
                            Toast.makeText(
                                appCtx,
                                appCtx.getString(R.string.toast_please_switch_back, oldAppName),
                                Toast.LENGTH_LONG,
                            ).show()
                        }, 300)
                        feedback(appCtx.getString(R.string.tile_switch_feedback_rear_busy))
                        return
                    }
                } catch (e: Exception) {
                    LogHelper.w(TAG, "Failed to check previous app: ${e.message}")
                }
            }

            try {
                ts.disableSubScreenLauncher()
            } catch (e: Exception) {
                LogHelper.w(TAG, "Failed to disable SubScreenLauncher", e)
            }

            val currentApp = ts.getCurrentForegroundApp()

            val serviceIntent = Intent(appCtx, RearSwitchKeeperService::class.java).apply {
                putExtra("lastMovedTask", currentApp)
                putExtra("keepScreenOnEnabled", RearAssistPrefs.isKeepScreenOnEnabled(appCtx))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(serviceIntent)
            } else {
                appCtx.startService(serviceIntent)
            }

            if (currentApp != null && currentApp.contains(":")) {
                val parts = currentApp.split(":")
                val packageName = parts[0]
                val taskId = parts[1].toInt()
                val appName = getAppName(appCtx, packageName)

                val success = ts.moveTaskToDisplay(taskId, rearDisplayId)

                if (success) {
                    SwitchToRearQsTileService.recordLastMovedTask(currentApp)
                    LyricsTaskTracking.saveLastTask(packageName, taskId)

                    try {
                        thread(name = "MiRoot-CollapseStatusBar") {
                            try {
                                ts.collapseStatusBar()
                            } catch (e: Exception) {
                                LogHelper.w(TAG, "Failed to collapse: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "Failed to start collapse thread: ${e.message}")
                    }

                    mainHandler.postDelayed({
                        Toast.makeText(
                            appCtx,
                            appCtx.getString(R.string.toast_cast_to_rear, appName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }, 300)

                    try {
                        if (!ts.launchWakeActivity(rearDisplayId)) {
                            LogHelper.w(TAG, "TaskService launchWakeActivity returned false")
                        }
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "launchWakeActivity exception: ${e.message}")
                    }

                    feedback(appCtx.getString(R.string.tile_switch_feedback_ok))
                } else {
                    try {
                        ts.collapseStatusBar()
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "Failed to collapse: ${e.message}")
                    }
                    mainHandler.postDelayed({
                        Toast.makeText(
                            appCtx,
                            R.string.toast_switch_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }, 300)
                    feedback(appCtx.getString(R.string.tile_switch_feedback_fail))
                }
            } else {
                LogHelper.w(TAG, "No foreground app found")
                feedback(appCtx.getString(R.string.tile_switch_feedback_no_app))
            }
        } catch (e: Exception) {
            LogHelper.e(TAG, "Error switching app", e)
            feedback(appCtx.getString(R.string.tile_switch_feedback_error))
        }
    }
}
