package com.wmqc.miroot.capability

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 状态页「权限授权状态」：检测与跳转系统设置。
 */
object RuntimePermissionGate {

    fun hasAllFilesAccess(ctx: Context): Boolean = Environment.isExternalStorageManager()

    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** Android 13+：未授权时前台通知可能无法展示，部分机型上会导致 startForeground 失败或录屏服务被系统停掉。 */
    fun canPostNotifications(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** [android.media.AudioPlaybackCaptureConfiguration] / 内录需要（与麦克风无关，但系统仍校验该权限）。 */
    fun hasRecordAudio(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun intentAppNotificationSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        }

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * 省电策略是否已放开（状态页「省电策略」行）。
     *
     * HyperOS / MIUI 在应用详情里设「无限制」时，有时不会同步到
     * [isIgnoringBatteryOptimizations]，但会放开后台相关 AppOps；二者任一满足即视为已授权。
     */
    fun hasBatteryUnrestricted(ctx: Context): Boolean {
        if (isIgnoringBatteryOptimizations(ctx)) return true
        val pkg = ctx.packageName
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isAppOpExplicitlyAllowed(appOps, OPSTR_RUN_ANY_IN_BACKGROUND, pkg)) {
                return true
            }
        }
        return isAppOpExplicitlyAllowed(appOps, OPSTR_RUN_IN_BACKGROUND, pkg)
    }

    /** 优先应用详情（HyperOS 省电策略），再回落到系统「忽略电池优化」列表。 */
    fun batteryPermissionIntents(ctx: Context): List<Intent> = listOf(
        intentAppDetails(ctx),
        intentIgnoreBatteryOptimizations(ctx),
        intentBatteryOptimizationList(),
    )

    fun firstResolvableBatteryIntent(ctx: Context, pm: PackageManager): Intent? =
        batteryPermissionIntents(ctx).firstOrNull { it.resolveActivity(pm) != null }

    fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        val pkg = ctx.packageName
        return flat.split(":")
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == pkg }
    }

    /**
     * Android 11+：应用列表可见性（QUERY_ALL_PACKAGES）。
     * Android 16 部分 ROM 会提供用户侧开关，通常映射到 AppOps。
     */
    fun canQueryAllPackages(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val permGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.QUERY_ALL_PACKAGES,
        ) == PackageManager.PERMISSION_GRANTED
        if (!permGranted) return false

        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val queryAllAllowed = isAppOpAllowed(appOps, OPSTR_QUERY_ALL_PACKAGES, ctx.packageName)
        // Xiaomi HyperOS/MIUI 上常见的“读取应用列表”开关，可能走独立 AppOps。
        val miuiListAllowed = isAppOpAllowed(appOps, OPSTR_MIUI_GET_INSTALLED_APPS, ctx.packageName)
        return queryAllAllowed && miuiListAllowed
    }

    fun intentAppDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", ctx.packageName, null)
        }

    fun intentAllFilesAccess(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    /**
     * 应用列表权限设置：优先小米权限编辑器，再回落到应用详情页。
     */
    fun queryAllPackagesPermissionIntents(ctx: Context): List<Intent> {
        val pkg = ctx.packageName
        return buildList {
            add(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    )
                    putExtra("extra_pkgname", pkg)
                    putExtra("extra_perm_id", "android.permission.QUERY_ALL_PACKAGES")
                },
            )
            add(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                    )
                    putExtra("extra_pkgname", pkg)
                },
            )
            add(intentAppDetails(ctx))
        }
    }

    fun firstResolvableQueryAllPackagesIntent(ctx: Context, pm: PackageManager): Intent? =
        queryAllPackagesPermissionIntents(ctx).firstOrNull { it.resolveActivity(pm) != null }

    /**
     * 悬浮窗设置：优先小米权限编辑器（否则系统页常出现「找不到该应用」），再 AOSP，最后打开总列表。
     */
    fun overlayPermissionIntents(ctx: Context): List<Intent> {
        val pkg = ctx.packageName
        return buildList {
            // 小米设备：应用详情-权限页，直达「显示悬浮窗」（Xiaomi HyperOS 兼容）
            add(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    )
                    putExtra("extra_pkgname", pkg)
                    putExtra("extra_perm_id", "android.permission.SYSTEM_ALERT_WINDOW")
                },
            )
            add(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                    )
                    putExtra("extra_pkgname", pkg)
                },
            )
            // 标准：package 用 fromParts，部分 ROM 对 parse 行为不一致
            add(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.fromParts("package", pkg, null)
                },
            )
            add(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$pkg")
                },
            )
            // 仅打开「显示在其他应用上层」应用列表，用户手动选本应用
            add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    fun firstResolvableOverlayIntent(ctx: Context, pm: PackageManager): Intent? =
        overlayPermissionIntents(ctx).firstOrNull { it.resolveActivity(pm) != null }

    fun intentIgnoreBatteryOptimizations(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    fun intentNotificationListenerSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /**
     * Xiaomi/MIUI/HyperOS：自启动管理入口（不同版本组件名可能不同，调用方需做 resolveActivity 兜底）。
     *
     * 说明：通知监听在 MIUI 上常受「自启动 / 后台限制 / 省电策略」影响导致系统拒绝绑定服务，
     * 即便用户已在系统页勾选了「通知使用权」。
     */
    fun miuiAutostartManagementIntents(ctx: Context): List<Intent> {
        // 组件名参考：MIUI SecurityCenter / PermCenter 常见路径
        val comps = listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
            ),
        )
        val pkg = ctx.packageName
        return buildList {
            for (c in comps) {
                add(
                    Intent("miui.intent.action.OP_AUTO_START").apply {
                        component = c
                        // 不同页面支持的 extra 不完全一致；多塞不会崩
                        putExtra("package_name", pkg)
                        putExtra("extra_pkgname", pkg)
                    },
                )
            }
            // 最后兜底到应用详情页（用户可手动找「自启动/后台弹出界面/省电策略」等）
            add(intentAppDetails(ctx))
        }
    }

    fun firstResolvableMiuiAutostartIntent(ctx: Context, pm: PackageManager): Intent? =
        miuiAutostartManagementIntents(ctx).firstOrNull { it.resolveActivity(pm) != null }

    fun intentBatteryOptimizationList(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun isAppOpAllowed(appOps: AppOpsManager, op: String, packageName: String): Boolean {
        return try {
            val mode = readAppOpMode(appOps, op, packageName)
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        } catch (_: Throwable) {
            // 某些 ROM 对未知 op 直接抛异常；按“未限制”处理，避免状态页崩溃。
            true
        }
    }

    private fun isAppOpExplicitlyAllowed(appOps: AppOpsManager, op: String, packageName: String): Boolean {
        return try {
            readAppOpMode(appOps, op, packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    private fun readAppOpMode(appOps: AppOpsManager, op: String, packageName: String): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(op, Process.myUid(), packageName)
        }
    }

    private const val OPSTR_QUERY_ALL_PACKAGES = "android:query_all_packages"
    private const val OPSTR_MIUI_GET_INSTALLED_APPS = "android:get_installed_apps"
    private const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"
    private const val OPSTR_RUN_IN_BACKGROUND = "android:run_in_background"
}
