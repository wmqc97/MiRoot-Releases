package com.wmqc.miroot.rear

import android.content.Context

/**
 * 统一控制是否允许禁用官方背屏服务（com.xiaomi.subscreencenter）。
 *
 * 约定：
 * - 存储到 FlutterSharedPreferences，便于与现有设置体系一致。
 * - 默认开启（true）：对官方背屏服务执行 force-stop；用户可关闭以跳过。
 */
object OfficialSubscreenServiceGate {
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val KEY_DISABLE_OFFICIAL_SUBSCREEN_SERVICE =
        "flutter.disable_official_subscreen_service"

    @JvmStatic
    fun isDisableEnabled(context: Context?): Boolean {
        if (context == null) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_OFFICIAL_SUBSCREEN_SERVICE, true)
    }

    @JvmStatic
    fun setDisableEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISABLE_OFFICIAL_SUBSCREEN_SERVICE, enabled)
            .apply()
    }
}
