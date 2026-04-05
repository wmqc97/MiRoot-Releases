package com.wmqc.miroot.capability

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
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

    fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        val pkg = ctx.packageName
        return flat.split(":")
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == pkg }
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

    fun intentBatteryOptimizationList(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
