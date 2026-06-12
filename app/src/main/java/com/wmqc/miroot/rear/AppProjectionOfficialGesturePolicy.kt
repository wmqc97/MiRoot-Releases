package com.wmqc.miroot.rear

import android.content.Context
import android.os.Build
import android.os.UserManager
import org.json.JSONArray

/**
 * **仅**「应用列表直开背屏 / [com.wmqc.miroot.rear.RearAppLaunchService]」迁第三方应用时，
 * 是否对官方背屏中心 [com.xiaomi.subscreencenter] 执行 force-stop（与 [com.wmqc.miroot.lyrics.TaskService] 内
 * `disableSubScreenLauncherForAppProjection` / `killLauncherProcessForAppProjection` 配套）。
 *
 * 磁贴迁屏、充电动画、背屏桌面、音乐/车控/平衡球/真心话/心率投屏等 **不** 走本策略，一律由 Session 或 `disableSubScreenLauncher()` 强制禁用。
 *
 * - **总开关**：[OfficialSubscreenServiceGate]。
 * - **范围**（「应用」标签）：`ALL` = 凡应用列表迁屏均禁用官方中心；`SELECTED` = 仅在该应用「投屏参数」中开启「禁用官方…」时禁用。
 *
 * 每应用开关持久化在 [AppProjectionDisplayPrefs] 的 `disableOfficial`。
 */
object AppProjectionOfficialGesturePolicy {
    private const val PREFS_NAME = "miroot_app_projection_display"
    private const val KEY_SCOPE = "official_gesture.scope"
    private const val KEY_SELECTED_JSON = "official_gesture.selected_json"
    private const val KEY_MIGRATED_V2 = "official_gesture.migrated_v2"
    private const val KEY_MIGRATED_V3 = "official_gesture.migrated_v3_per_app_json"
    private const val KEY_LEGACY_GLOBAL_BOOL = "global.disable_official_subscreen_for_app_projection"

    const val SCOPE_ALL = "all"
    const val SCOPE_SELECTED = "selected"

    enum class Scope {
        ALL,
        SELECTED,
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = context.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked == true
    }

    fun ensureMigrated(context: Context) {
        val appCtx = context.applicationContext
        if (!isUserUnlocked(appCtx)) return
        val p = prefs(appCtx)
        if (!p.getBoolean(KEY_MIGRATED_V2, false)) {
            val ed = p.edit()
            if (p.contains(KEY_LEGACY_GLOBAL_BOOL)) {
                val legacyOn = p.getBoolean(KEY_LEGACY_GLOBAL_BOOL, true)
                if (legacyOn) {
                    ed.putString(KEY_SCOPE, SCOPE_ALL)
                } else {
                    ed.putString(KEY_SCOPE, SCOPE_SELECTED)
                    ed.putString(KEY_SELECTED_JSON, "[]")
                }
                ed.remove(KEY_LEGACY_GLOBAL_BOOL)
            } else {
                ed.putString(KEY_SCOPE, SCOPE_ALL)
            }
            ed.putBoolean(KEY_MIGRATED_V2, true).apply()
        }
        migrateV3LegacySelectedJsonToPerApp(appCtx)
    }

    /** 将旧版「勾选列表」白名单写入各应用 JSON 的 `disableOfficial`，然后清空列表。 */
    private fun migrateV3LegacySelectedJsonToPerApp(appCtx: Context) {
        val p = prefs(appCtx)
        if (p.getBoolean(KEY_MIGRATED_V3, false)) return
        val raw = p.getString(KEY_SELECTED_JSON, null) ?: run {
            p.edit().putBoolean(KEY_MIGRATED_V3, true).apply()
            return
        }
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i).trim()
                if (pkg.isNotEmpty()) {
                    AppProjectionDisplayPrefs.migrateSetDisableOfficial(appCtx, pkg, enabled = true)
                }
            }
        }
        p.edit().putString(KEY_SELECTED_JSON, "[]").putBoolean(KEY_MIGRATED_V3, true).apply()
    }

    fun getScope(context: Context): Scope {
        ensureMigrated(context)
        val appCtx = context.applicationContext
        return when (prefs(appCtx).getString(KEY_SCOPE, SCOPE_ALL)) {
            SCOPE_SELECTED -> Scope.SELECTED
            else -> Scope.ALL
        }
    }

    fun setScope(context: Context, scope: Scope) {
        if (!isUserUnlocked(context)) return
        ensureMigrated(context)
        val v = if (scope == Scope.SELECTED) SCOPE_SELECTED else SCOPE_ALL
        prefs(context.applicationContext).edit().putString(KEY_SCOPE, v).apply()
    }

    @JvmStatic
    fun shouldForceStopForThirdPartyProjection(context: Context?, packageName: String?): Boolean {
        if (context == null) return false
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (!OfficialSubscreenServiceGate.isDisableEnabled(context)) return false
        ensureMigrated(context)
        return when (getScope(context)) {
            Scope.ALL -> true
            Scope.SELECTED -> AppProjectionDisplayPrefs.isDisableOfficialStoredForPackage(context, pkg)
        }
    }
}
