package com.wmqc.miroot.rear
import com.wmqc.miroot.display.MainDisplayUi

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
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PrivilegedShellRoute
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RearScreenWakeManager
import com.wmqc.miroot.lyrics.RearScreenWakeService
import com.wmqc.miroot.lyrics.RootTaskService
import com.wmqc.miroot.rear.desktop.RearDesktopToAppLaunchHandoff
import com.wmqc.miroot.rear.desktop.RearScreenDesktopActivity

/**
 * 从应用列表启动指定应用到背屏，并自动应用/恢复投屏显示配置（DPI/旋转）。
 *
 * 为什么用 Service：
 * - Fragment 里直接 startActivity(launchDisplayId) 拿不到 taskId，无法接入 Keeper 的监控与结束恢复
 * - 这里用 RootTaskService 执行 `am start --display 1`，并轮询 taskId 后启动 Keeper
 *
 * 背屏桌面转盘：[EXTRA_LAUNCH_FROM_REAR_DESKTOP] 为 true 且存在 per-app 配置时：
 * [RearScreenDesktopActivity] 会先 finish 退场，本服务等待栈顶不再是桌面 Activity 后**先**应用 DPI/旋转再 `am start`，
 * 避免桌面随 wm density 变形，并减少目标应用先按默认密度绘制再跳变。
 *
 * 与 [RearDesktopToAppLaunchHandoff] 配合：桌面 [onDestroy] 在交接窗口内不向 Keeper 发 RELEASE，避免误触发 [RearSwitchKeeperService.performUnifiedExit]。
 *
 * 启动时序统一为：先冻结官方背屏服务，再发起背屏启动，避免启动窗口期被系统通知/官方背屏中心抢占。
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
        val fromRearDesktopForCleanup =
            intent?.action == ACTION_LAUNCH_APP_ON_REAR &&
                intent.getBooleanExtra(EXTRA_LAUNCH_FROM_REAR_DESKTOP, false)
        try {
            handleLaunchAppOnRearIntent(intent)
        } finally {
            if (fromRearDesktopForCleanup) {
                mainHandler().post { RearDesktopToAppLaunchHandoff.end() }
            }
        }
    }

    private fun handleLaunchAppOnRearIntent(intent: Intent?) {
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
                MainDisplayUi.showToast(appCtx, reasonRes, Toast.LENGTH_SHORT)
            }
            return
        }
        LogHelper.d(TAG, "rear launch privilege route=$route pkg=$pkg")

        if (!ensureTaskServiceConnected()) {
            mainHandler().post {
                MainDisplayUi.showToast(
                    appCtx,
                    if (route == PrivilegedShellRoute.ROOT) {
                        R.string.apps_launch_failed_root_channel
                    } else {
                        R.string.apps_launch_failed_shizuku_channel
                    },
                    Toast.LENGTH_SHORT,
                )
            }
            return
        }

        val ts = taskService ?: return
        val fromRearDesktop =
            intent.getBooleanExtra(EXTRA_LAUNCH_FROM_REAR_DESKTOP, false)

        // 1) Per-app 投屏 DPI/旋转：应用列表路径在启动前先应用；背屏桌面路径在桌面 finish 退场后、am start 前应用（见下方 defer 分支）。
        val config = AppProjectionDisplayPrefs.getConfig(appCtx, pkg)
        val deferDisplayConfig = fromRearDesktop && config != null
        if (config != null && !deferDisplayConfig) {
            applyRearProjectionDisplayConfig(ts, appCtx, pkg, config)
        } else if (config == null) {
            AppProjectionDisplayPrefs.clearSessionSnapshot(appCtx)
        }

        // 2) 启动目标应用到背屏
        val component = resolveLaunchComponent(appCtx, pkg)
        if (component == null) {
            mainHandler().post {
                MainDisplayUi.showToast(appCtx, R.string.apps_launch_failed, Toast.LENGTH_SHORT)
            }
            // 启动失败，立即回退一次（避免只应用了配置却没有进入投屏流程）
            restoreRearDisplayDefaults(appCtx, ts)
            return
        }

        // 统一顺序：先冻结官方背屏服务，再启动目标应用到背屏，减少启动窗口期被通知打断。
        if (pkg == BuildConfig.APPLICATION_ID) {
            runCatching { ts.disableSubScreenLauncher() }
        } else {
            runCatching { ts.disableSubScreenLauncherForAppProjection(pkg) }
        }
        if (fromRearDesktop) {
            runCatching {
                ts.executeShellCommand("input -d $REAR_DISPLAY_ID keyevent KEYCODE_WAKEUP")
            }
        }

        if (deferDisplayConfig && config != null) {
            waitUntilRearDesktopNotForeground(ts, maxWaitMs = 2500L)
            if (!applyRearProjectionDisplayConfig(ts, appCtx, pkg, config)) {
                restoreRearDisplayDefaults(appCtx, ts)
                return
            }
        }

        val cmp = "${component.packageName}/${component.className}"
        val launchOk = runCatching { ts.executeShellCommand("am start --display $REAR_DISPLAY_ID -n $cmp") }.getOrDefault(false)
        if (!launchOk) {
            mainHandler().post {
                MainDisplayUi.showToast(
                    appCtx,
                    if (route == PrivilegedShellRoute.ROOT) {
                        R.string.apps_launch_failed_root_channel
                    } else {
                        R.string.apps_launch_failed_shizuku_channel
                    },
                    Toast.LENGTH_SHORT,
                )
            }
            restoreRearDisplayDefaults(appCtx, ts)
            return
        }

        val scheduleReapply = config != null
        if (scheduleReapply && config != null) {
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
                    // 仅应用列表迁第三方：Keeper 初始杀与 disableSubScreenLauncherForAppProjection 共用「应用」页策略
                    putExtra(
                        RearSwitchKeeperService.EXTRA_USE_APP_LIST_OFFICIAL_GESTURE_POLICY,
                        pkg != BuildConfig.APPLICATION_ID,
                    )
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(keeperIntent)
            } else {
                appCtx.startService(keeperIntent)
            }
        } else {
            LogHelper.w(TAG, "taskId not found for pkg=$pkg, keeper not started")
        }
        if (fromRearDesktop) {
            stopDesktopProjectionWakeService(appCtx)
        }

        // 4) 保险：唤醒背屏（不影响主屏）；背屏桌面路径已在启动前发送 KEYCODE_WAKEUP，避免重复拉起唤醒 Activity
        if (!fromRearDesktop) {
            runCatching { ts.launchWakeActivity(REAR_DISPLAY_ID) }
        }
    }

    /**
     * 结束投屏统一恢复固定默认值前会清快照；与迁屏前「先应用」路径共用。
     */
    private fun applyRearProjectionDisplayConfig(
        ts: ITaskService,
        appCtx: Context,
        pkgForLog: String,
        config: AppProjectionDisplayPrefs.AppDisplayConfig,
    ): Boolean {
        AppProjectionDisplayPrefs.clearSessionSnapshot(appCtx)
        val dpiOk =
            if (config.dpi != null && config.dpi > 0) {
                runCatching { ts.setRearDpi(config.dpi) }.getOrDefault(false)
            } else {
                true
            }
        val rotOk =
            runCatching { ts.setDisplayRotation(REAR_DISPLAY_ID, config.rotation) }.getOrDefault(false)
        if (!dpiOk || !rotOk) {
            LogHelper.w(TAG, "apply config failed: pkg=$pkgForLog dpiOk=$dpiOk rotOk=$rotOk config=$config")
            mainHandler().post {
                MainDisplayUi.showToast(appCtx, R.string.apps_projection_apply_failed, Toast.LENGTH_LONG)
            }
        }
        return dpiOk && rotOk
    }

    private fun rearForegroundComponentRaw(ts: ITaskService): String? =
        runCatching { ts.getForegroundComponentOnDisplay(REAR_DISPLAY_ID) }.getOrNull()

    private fun isRearScreenDesktopForeground(ts: ITaskService): Boolean {
        val raw = rearForegroundComponentRaw(ts)
        if (raw != null) {
            val colon = raw.lastIndexOf(':')
            val component = if (colon > 0) raw.substring(0, colon) else raw
            return component.contains("RearScreenDesktopActivity")
        }
        // 解析不到组件时退回包名：非本包则一定不是背屏桌面。
        val legacy = runCatching { ts.getForegroundAppOnDisplay(REAR_DISPLAY_ID) }.getOrNull() ?: return false
        val pkg = legacy.substringBefore(':').trim()
        if (pkg != BuildConfig.APPLICATION_ID) return false
        return true
    }

    /** 等 [RearScreenDesktopActivity] 不再占据背屏栈顶（桌面侧已 finish），便于先改 DPI 再启动目标应用。 */
    private fun waitUntilRearDesktopNotForeground(ts: ITaskService, maxWaitMs: Long) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRearScreenDesktopForeground(ts)) {
                return
            }
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        LogHelper.w(TAG, "wait rear desktop dismiss timeout, proceed with dpi apply")
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

    /**
     * [ITaskService.getForegroundComponentOnDisplay] 形如 `pkg/Activity:taskId`，对冷启动慢、
     * [getForegroundAppOnDisplay] 尚未稳定为包名前缀时更可靠。
     */
    private fun taskIdFromForegroundComponent(ts: ITaskService, packageName: String): Int {
        val raw =
            runCatching { ts.getForegroundComponentOnDisplay(REAR_DISPLAY_ID) }.getOrNull()
                ?: return -1
        val colon = raw.lastIndexOf(':')
        if (colon <= 0 || colon >= raw.length - 1) return -1
        val comp = raw.substring(0, colon).trim()
        val tid = raw.substring(colon + 1).trim().toIntOrNull() ?: return -1
        val slash = comp.indexOf('/')
        if (slash <= 0) return -1
        val pkg = comp.substring(0, slash).trim()
        return if (pkg == packageName && tid > 0) tid else -1
    }

    private fun waitForTaskId(ts: ITaskService, packageName: String): Int {
        // 尾段加长：部分应用（如冷启动音乐客户端）背屏栈顶出现晚于 ~3.2s。
        val waits = longArrayOf(120L, 220L, 360L, 520L, 800L, 1200L, 1800L, 2400L)
        val expectedPrefix = "$packageName:"
        for (w in waits) {
            try {
                Thread.sleep(w)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            // getTaskIdByPackage 取 am stack list 中「首个」匹配行，可能是主屏同名包任务，会导致 Keeper 误判已离背屏并立刻收口。
            val foreground =
                runCatching { ts.getForegroundAppOnDisplay(REAR_DISPLAY_ID) }.getOrNull()
            if (foreground != null && foreground.startsWith(expectedPrefix)) {
                val colon = foreground.indexOf(':')
                if (colon > 0 && colon < foreground.length - 1) {
                    val tid = foreground.substring(colon + 1).trim().toIntOrNull() ?: -1
                    if (tid > 0) {
                        return tid
                    }
                }
            }
            val fromComp = taskIdFromForegroundComponent(ts, packageName)
            if (fromComp > 0) {
                return fromComp
            }
            val tid = runCatching { ts.getTaskIdByPackage(packageName) }.getOrDefault(-1)
            if (tid > 0 &&
                runCatching { ts.isTaskOnDisplay(tid, REAR_DISPLAY_ID) }.getOrDefault(false)
            ) {
                return tid
            }
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

    /** 背屏桌面→应用投屏：桌面侧已 stopWake，此处再收口，避免「投屏中」与 Keeper 双通知并存。 */
    private fun stopDesktopProjectionWakeService(appCtx: Context) {
        try {
            RearScreenWakeManager.getInstance()
                .stopWakeService(appCtx, RearScreenDesktopActivity::class.java)
            appCtx.stopService(Intent(appCtx, RearScreenWakeService::class.java))
        } catch (e: Exception) {
            LogHelper.w(TAG, "stop desktop wake after keeper: ${e.message}")
        }
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

        /** 为 true 时表示从 [com.wmqc.miroot.rear.desktop.RearScreenDesktopActivity] 启动。 */
        const val EXTRA_LAUNCH_FROM_REAR_DESKTOP = "launchFromRearDesktop"
    }
}

