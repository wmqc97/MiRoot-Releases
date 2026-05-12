package com.wmqc.miroot.rear

import android.app.IntentService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PrivilegedShellRoute
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskService

/**
 * 从应用列表启动指定应用到背屏，并自动应用/恢复投屏显示配置（DPI/旋转）。
 *
 * 为什么用 Service：
 * - Fragment 里直接 startActivity(launchDisplayId) 拿不到 taskId，无法接入 Keeper 的监控与结束恢复
 * - 这里用 RootTaskService 执行 `am start --display 1`，并轮询 taskId 后启动 Keeper
 */
@Suppress("DEPRECATION")
class RearAppLaunchService : IntentService("RearAppLaunchService") {

    private var taskService: ITaskService? = null
    private val rearDefaultDpi = 450
    private val rearDefaultRotation = 0

    private val taskConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                taskService = ITaskService.Stub.asInterface(binder)
                LogHelper.d(TAG, "TaskService connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                taskService = null
            }
        }

    @Deprecated("Deprecated in Java")
    override fun onCreate() {
        super.onCreate()
        bindTaskService()
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null || intent.action != ACTION_LAUNCH_APP_ON_REAR) return

        val appCtx = applicationContext
        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val rootReady = EnvironmentProbe.probeRootSync()
        val shizukuRunning = EnvironmentProbe.shizukuServiceRunning()
        val shizukuGranted = EnvironmentProbe.shizukuPermissionGranted()
        val route =
            when {
                rootReady -> PrivilegedShellRoute.ROOT
                shizukuRunning && shizukuGranted -> PrivilegedShellRoute.SHIZUKU
                else -> PrivilegedShellRoute.NONE
            }
        if (route == PrivilegedShellRoute.NONE) {
            val reasonRes =
                when {
                    !rootReady && shizukuRunning && !shizukuGranted -> R.string.apps_launch_shizuku_unauthorized
                    !rootReady && !shizukuRunning -> R.string.apps_launch_root_denied
                    else -> R.string.privilege_shell_required
                }
            LogHelper.w(
                TAG,
                "no privileged shell channel, skip rear launch: pkg=$pkg rootReady=$rootReady shizukuRunning=$shizukuRunning shizukuGranted=$shizukuGranted",
            )
            mainHandler().post {
                Toast.makeText(appCtx, reasonRes, Toast.LENGTH_SHORT).show()
            }
            return
        }
        LogHelper.d(TAG, "rear launch privilege route=$route pkg=$pkg")

        if (!ensureTaskServiceConnected()) {
            mainHandler().post {
                Toast.makeText(
                    appCtx,
                    if (route == PrivilegedShellRoute.ROOT) {
                        R.string.apps_launch_failed_root_channel
                    } else {
                        R.string.apps_launch_failed_shizuku_channel
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }

        val ts = taskService ?: return

        // 1) 应用列表设置：若存在 per-app 配置，启动前就先应用（并保存快照，结束投屏再恢复）
        val config = AppProjectionDisplayPrefs.getConfig(appCtx, pkg)
        if (config != null) {
            // 结束投屏统一恢复固定默认值，不再记录/恢复原始快照。
            AppProjectionDisplayPrefs.clearSessionSnapshot(appCtx)

            val dpiOk =
                if (config.dpi != null && config.dpi > 0) {
                    runCatching { ts.setRearDpi(config.dpi) }.getOrDefault(false)
                } else {
                    true
                }
            val rotOk = runCatching { ts.setDisplayRotation(REAR_DISPLAY_ID, config.rotation) }.getOrDefault(false)
            if (!dpiOk || !rotOk) {
                LogHelper.w(TAG, "apply config failed: pkg=$pkg dpiOk=$dpiOk rotOk=$rotOk config=$config")
                mainHandler().post {
                    Toast.makeText(appCtx, R.string.apps_projection_apply_failed, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // 未设置：保持当前背屏状态不变，同时清理历史快照，避免结束时误回退
            AppProjectionDisplayPrefs.clearSessionSnapshot(appCtx)
        }

        // 2) 启动目标应用到背屏
        val component = resolveLaunchComponent(appCtx, pkg)
        if (component == null) {
            mainHandler().post {
                Toast.makeText(appCtx, R.string.apps_launch_failed, Toast.LENGTH_SHORT).show()
            }
            // 启动失败，立即回退一次（避免只应用了配置却没有进入投屏流程）
            restoreRearDisplayDefaults(appCtx, ts)
            return
        }

        // 为了减少背屏中心抢占，尝试先禁用 subscreen launcher（失败不致命）
        runCatching { ts.disableSubScreenLauncher() }

        val cmp = "${component.packageName}/${component.className}"
        val launchOk = runCatching { ts.executeShellCommand("am start --display $REAR_DISPLAY_ID -n $cmp") }.getOrDefault(false)
        if (!launchOk) {
            mainHandler().post {
                Toast.makeText(
                    appCtx,
                    if (route == PrivilegedShellRoute.ROOT) {
                        R.string.apps_launch_failed_root_channel
                    } else {
                        R.string.apps_launch_failed_shizuku_channel
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
            restoreRearDisplayDefaults(appCtx, ts)
            return
        }
        if (config != null) {
            // 启动切栈后 ROM 可能短暂覆盖 display rotation，这里按 3.4 思路做重试兜底。
            scheduleReapplyProjectionDisplayConfig(ts, config)
        }

        // 3) 等待 taskId 出现，然后启动 Keeper 以便“结束投屏恢复默认 + 监控应用退出”
        val taskId = waitForTaskId(ts, pkg)
        if (taskId > 0) {
            val monitored = "$pkg:$taskId"
            val keeperIntent =
                Intent(appCtx, RearSwitchKeeperService::class.java).apply {
                    putExtra("lastMovedTask", monitored)
                    putExtra("keepScreenOnEnabled", RearAssistPrefs.isKeepScreenOnEnabled(appCtx))
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(keeperIntent)
            } else {
                appCtx.startService(keeperIntent)
            }
        } else {
            LogHelper.w(TAG, "taskId not found for pkg=$pkg, keeper not started")
        }

        // 4) 保险：唤醒背屏（不影响主屏）
        runCatching { ts.launchWakeActivity(REAR_DISPLAY_ID) }
    }

    private fun restoreRearDisplayDefaults(context: Context, ts: ITaskService) {
        try {
            // 启动失败/异常时强制恢复固定默认值：DPI 450 + 旋转 0。
            val dpiOk =
                runCatching {
                    ts.executeShellCommand("wm density $rearDefaultDpi -d $REAR_DISPLAY_ID")
                }.getOrDefault(false)
            val rotOk =
                runCatching { ts.setDisplayRotation(REAR_DISPLAY_ID, rearDefaultRotation) }
                    .getOrDefault(false)
            if (!dpiOk || !rotOk) {
                LogHelper.w(TAG, "restore fixed defaults failed: dpiOk=$dpiOk rotOk=$rotOk")
            }
        } finally {
            AppProjectionDisplayPrefs.clearSessionSnapshot(context)
        }
    }

    private fun resolveLaunchComponent(context: Context, packageName: String): ComponentName? {
        return try {
            val pm = context.packageManager
            val i = pm.getLaunchIntentForPackage(packageName) ?: return null
            i.component
        } catch (e: Exception) {
            LogHelper.w(TAG, "resolveLaunchComponent failed: ${e.message}")
            null
        }
    }

    private fun waitForTaskId(ts: ITaskService, packageName: String): Int {
        val waits = longArrayOf(120L, 220L, 360L, 520L, 800L, 1200L)
        for (w in waits) {
            try {
                Thread.sleep(w)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            val tid = runCatching { ts.getTaskIdByPackage(packageName) }.getOrDefault(-1)
            if (tid > 0) return tid
        }
        return -1
    }

    private fun scheduleReapplyProjectionDisplayConfig(
        ts: ITaskService,
        config: AppProjectionDisplayPrefs.AppDisplayConfig,
    ) {
        try {
            Thread {
                val waits = longArrayOf(180L, 420L, 900L)
                for (wait in waits) {
                    Thread.sleep(wait)
                    val dpiOk =
                        if (config.dpi != null && config.dpi > 0) {
                            runCatching { ts.executeShellCommand("wm density ${config.dpi} -d $REAR_DISPLAY_ID") }
                                .getOrDefault(false)
                        } else {
                            true
                        }
                    val rotationOk =
                        runCatching { ts.setDisplayRotation(REAR_DISPLAY_ID, config.rotation) }
                            .getOrDefault(false)
                    if (dpiOk && rotationOk) {
                        LogHelper.d(TAG, "reapply projection config success after ${wait}ms")
                        return@Thread
                    }
                }
                LogHelper.w(
                    TAG,
                    "reapply projection config exhausted: dpi=${config.dpi}, rotation=${config.rotation}",
                )
            }.start()
        } catch (e: Exception) {
            LogHelper.w(TAG, "scheduleReapplyProjectionDisplayConfig failed: ${e.message}")
        }
    }

    private fun bindTaskService() {
        if (taskService != null) return
        try {
            bindService(Intent(this, RootTaskService::class.java), taskConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            LogHelper.e(TAG, "bind RootTaskService failed", e)
        }
    }

    private fun ensureTaskServiceConnected(): Boolean {
        if (taskService != null) return true
        bindTaskService()
        var attempts = 0
        val maxAttempts = 20
        while (taskService == null && attempts < maxAttempts) {
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            attempts++
        }
        return taskService != null
    }

    private fun mainHandler(): Handler = Handler(Looper.getMainLooper())

    @Deprecated("Deprecated in Java")
    override fun onDestroy() {
        try {
            unbindService(taskConnection)
        } catch (_: Exception) {
        }
        taskService = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RearAppLaunchSvc"
        private const val REAR_DISPLAY_ID = 1

        const val ACTION_LAUNCH_APP_ON_REAR = "com.wmqc.miroot.rear.ACTION_LAUNCH_APP_ON_REAR"
        const val EXTRA_PACKAGE_NAME = "packageName"
    }
}

