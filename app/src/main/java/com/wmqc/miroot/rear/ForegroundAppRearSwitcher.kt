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
    private const val REAR_DISPLAY_ID = 1
    private const val REAR_DEFAULT_DPI = 450
    private const val REAR_DEFAULT_ROTATION = 0

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
        fun feedback(msg: String) {
            onTileSubtitle?.invoke(msg) ?: Unit
        }

        try {
            val prev = SwitchToRearQsTileService.getLastMovedTask()
            if (prev != null && prev.contains(":")) {
                try {
                    val oldParts = prev.split(":")
                    val oldPackageName = oldParts[0]
                    val rearForegroundApp = ts.getForegroundAppOnDisplay(REAR_DISPLAY_ID)
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

            if (currentApp != null && currentApp.contains(":")) {
                val parts = currentApp.split(":")
                val packageName = parts[0]
                val taskId = parts[1].toInt()
                val appName = getAppName(appCtx, packageName)
                val appConfig = AppProjectionDisplayPrefs.getConfig(appCtx, packageName)
                if (appConfig != null) {
                    val appliedConfigOk = applyProjectionDisplayConfig(
                        context = appCtx,
                        ts = ts,
                        config = appConfig,
                    )
                    if (!appliedConfigOk) {
                        mainHandler.post {
                            Toast.makeText(appCtx, R.string.apps_projection_apply_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    // 未为该应用设置投屏参数：保持背屏当前 DPI/旋转不变
                    AppProjectionDisplayPrefs.clearSessionSnapshot(appCtx)
                }

                val serviceIntent = Intent(appCtx, RearSwitchKeeperService::class.java).apply {
                    putExtra("lastMovedTask", currentApp)
                    putExtra("keepScreenOnEnabled", RearAssistPrefs.isKeepScreenOnEnabled(appCtx))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(serviceIntent)
                } else {
                    appCtx.startService(serviceIntent)
                }

                val success = ts.moveTaskToDisplay(taskId, REAR_DISPLAY_ID)

                if (success) {
                    SwitchToRearQsTileService.recordLastMovedTask(currentApp)
                    LyricsTaskTracking.saveLastTask(packageName, taskId)
                    if (appConfig != null) {
                        scheduleReapplyProjectionDisplayConfig(ts, appConfig)
                    }

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
                        if (!ts.launchWakeActivity(REAR_DISPLAY_ID)) {
                            LogHelper.w(TAG, "TaskService launchWakeActivity returned false")
                        }
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "launchWakeActivity exception: ${e.message}")
                    }

                    feedback(appCtx.getString(R.string.tile_switch_feedback_ok))
                } else {
                    restoreProjectionDisplayState(appCtx, ts)
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

    private fun applyProjectionDisplayConfig(
        context: Context,
        ts: ITaskService,
        config: AppProjectionDisplayPrefs.AppDisplayConfig,
    ): Boolean {
        return try {
            // 结束投屏统一恢复固定默认值，不再记录/恢复原始快照。
            AppProjectionDisplayPrefs.clearSessionSnapshot(context)

            val dpiOk = runShellWithDiagnostics(ts, "wm density ${config.dpi} -d $REAR_DISPLAY_ID")
            val rotationOk = ts.setDisplayRotation(REAR_DISPLAY_ID, config.rotation)
            LogHelper.d(
                TAG,
                "apply projection display config: displayId=$REAR_DISPLAY_ID dpi=${config.dpi} dpiOk=$dpiOk rotation=${config.rotation} rotationOk=$rotationOk",
            )
            dpiOk && rotationOk
        } catch (e: Exception) {
            LogHelper.w(TAG, "applyProjectionDisplayConfig failed: ${e.message}")
            false
        }
    }

    private fun restoreProjectionDisplayState(
        context: Context,
        ts: ITaskService,
    ) {
        try {
            // 结束或失败时强制恢复固定默认值：DPI 450 + 旋转 0。
            val dpiOk = runShellWithDiagnostics(ts, "wm density $REAR_DEFAULT_DPI -d $REAR_DISPLAY_ID")
            val rotOk =
                runCatching { ts.setDisplayRotation(REAR_DISPLAY_ID, REAR_DEFAULT_ROTATION) }
                    .getOrDefault(false)
            if (!dpiOk || !rotOk) {
                LogHelper.w(
                    TAG,
                    "restoreProjectionDisplayState fixed-default restore failed: dpiOk=$dpiOk rotOk=$rotOk",
                )
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "restoreProjectionDisplayState failed: ${e.message}")
        } finally {
            AppProjectionDisplayPrefs.clearSessionSnapshot(context)
        }
    }

    private fun scheduleReapplyProjectionDisplayConfig(
        ts: ITaskService,
        config: AppProjectionDisplayPrefs.AppDisplayConfig,
    ) {
        try {
            thread(name = "MiRoot-ReapplyProjectionConfig") {
                val waits = longArrayOf(180L, 420L, 900L)
                for (wait in waits) {
                    Thread.sleep(wait)
                    val dpiOk = runShellWithDiagnostics(ts, "wm density ${config.dpi} -d $REAR_DISPLAY_ID")
                    val rotationOk = try {
                        ts.setDisplayRotation(REAR_DISPLAY_ID, config.rotation)
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "reapply rotation failed: ${e.message}")
                        false
                    }
                    if (dpiOk && rotationOk) {
                        LogHelper.d(TAG, "reapply projection config success after ${wait}ms")
                        return@thread
                    }
                }
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "scheduleReapplyProjectionDisplayConfig failed: ${e.message}")
        }
    }

    private fun runShellWithDiagnostics(ts: ITaskService, rawCmd: String): Boolean {
        return try {
            val cmd = "$rawCmd 2>&1; echo __RC:$?"
            val out = ts.executeShellCommandWithResult(cmd).orEmpty().trim()
            val ok = out.contains("__RC:0")
            if (ok) {
                LogHelper.d(TAG, "shell ok: $rawCmd")
            } else {
                LogHelper.w(TAG, "shell fail: $rawCmd ; out=$out")
            }
            ok
        } catch (e: Exception) {
            LogHelper.w(TAG, "shell ex: $rawCmd ; ${e.message}")
            false
        }
    }
}
