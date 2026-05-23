package com.wmqc.miroot.rear

import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * 统一控制是否允许禁用官方背屏服务（com.xiaomi.subscreencenter）。
 *
 * 约定：
 * - 存储到 FlutterSharedPreferences，便于与现有设置体系一致。
 * - 4.x 起不再暴露手动开关，统一视为开启（true）。
 * - 旧版本遗留偏好值仅保留兼容，不再影响运行时策略。
 */
object OfficialSubscreenServiceGate {
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val KEY_DISABLE_OFFICIAL_SUBSCREEN_SERVICE =
        "flutter.disable_official_subscreen_service"

    @JvmStatic
    fun isDisableEnabled(context: Context?): Boolean {
        return context != null
    }

    @JvmStatic
    fun setDisableEnabled(context: Context, enabled: Boolean) {
        // 手动开关已移除：保留接口用于兼容旧调用，不再写入运行时策略。
        if (!isUserUnlocked(context)) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISABLE_OFFICIAL_SUBSCREEN_SERVICE, true)
            .apply()
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = context.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked == true
    }
}
