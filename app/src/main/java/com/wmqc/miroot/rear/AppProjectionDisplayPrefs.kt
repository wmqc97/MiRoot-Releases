package com.wmqc.miroot.rear

import android.content.Context
import org.json.JSONObject

object AppProjectionDisplayPrefs {
    private const val PREFS_NAME = "miroot_app_projection_display"
    private const val KEY_PREFIX_CONFIG = "config:"
    private const val KEY_SESSION_ACTIVE = "session.active"
    private const val KEY_SESSION_PACKAGE = "session.package"
    private const val KEY_SESSION_ORIGINAL_DPI = "session.original_dpi"
    private const val KEY_SESSION_ORIGINAL_ROTATION = "session.original_rotation"

    data class AppDisplayConfig(
        val dpi: Int?,
        val rotation: Int,
        /** 是否在第三方投屏时禁用官方背屏中心（仅当全局策略为「仅选定应用」时生效）。 */
        val disableOfficialSubscreen: Boolean = true,
    ) {
        /** 与应用列表胶囊一致：DPI（可选）+ 旋转角度。 */
        val summary: String
            get() {
                val rot = "旋转 ${rotation * 90}°"
                return if (dpi != null && dpi > 0) {
                    "DPI $dpi · $rot"
                } else {
                    rot
                }
            }
    }

    data class ProjectionSessionSnapshot(
        val packageName: String,
        val originalDpi: Int?,
        val originalRotation: Int,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConfig(context: Context, packageName: String): AppDisplayConfig? {
        val raw = prefs(context).getString(KEY_PREFIX_CONFIG + packageName, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val dpiRaw = json.optInt("dpi", 0)
            val dpi = dpiRaw.takeIf { it > 0 }
            val rotation = json.optInt("rotation", -1)
            if (rotation !in 0..3) return null
            AppDisplayConfig(
                dpi = dpi,
                rotation = rotation,
                disableOfficialSubscreen = json.optBoolean("disableOfficial", true),
            )
        }.getOrNull()
    }

    /**
     * 是否存在有效配置且 `disableOfficial==true`（用于「仅选定应用」策略）。
     * 无配置条目则视为未启用。
     */
    fun isDisableOfficialStoredForPackage(context: Context, packageName: String): Boolean {
        val cfg = getConfig(context, packageName) ?: return false
        return cfg.disableOfficialSubscreen
    }

    /** 迁移旧版白名单：合并写入 `disableOfficial`，但不删除已有 DPI/旋转。 */
    internal fun migrateSetDisableOfficial(context: Context, packageName: String, enabled: Boolean) {
        val appCtx = context.applicationContext
        val key = KEY_PREFIX_CONFIG + packageName
        val p = prefs(appCtx)
        val raw = p.getString(key, null)
        val json =
            if (raw != null) {
                runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
            } else {
                JSONObject()
            }
        json.put("disableOfficial", enabled)
        if (!json.has("rotation") || json.optInt("rotation", -1) !in 0..3) {
            json.put("rotation", 0)
        }
        p.edit().putString(key, json.toString()).apply()
    }

    fun setConfig(context: Context, packageName: String, config: AppDisplayConfig) {
        val payload = JSONObject().apply {
            put("rotation", config.rotation)
            put("disableOfficial", config.disableOfficialSubscreen)
            if (config.dpi != null && config.dpi > 0) {
                put("dpi", config.dpi)
            } else {
                remove("dpi")
            }
        }
            .toString()
        prefs(context).edit().putString(KEY_PREFIX_CONFIG + packageName, payload).apply()
    }

    fun clearConfig(context: Context, packageName: String) {
        prefs(context).edit().remove(KEY_PREFIX_CONFIG + packageName).apply()
    }

    fun saveSessionSnapshot(context: Context, snapshot: ProjectionSessionSnapshot) {
        prefs(context).edit().apply {
            putBoolean(KEY_SESSION_ACTIVE, true)
            putString(KEY_SESSION_PACKAGE, snapshot.packageName)
            if (snapshot.originalDpi != null) {
                putInt(KEY_SESSION_ORIGINAL_DPI, snapshot.originalDpi)
            } else {
                remove(KEY_SESSION_ORIGINAL_DPI)
            }
            putInt(KEY_SESSION_ORIGINAL_ROTATION, snapshot.originalRotation)
            apply()
        }
    }

    fun getSessionSnapshot(context: Context): ProjectionSessionSnapshot? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_SESSION_ACTIVE, false)) return null
        val packageName = p.getString(KEY_SESSION_PACKAGE, null) ?: return null
        val hasOriginalDpi = p.contains(KEY_SESSION_ORIGINAL_DPI)
        val originalDpi = if (hasOriginalDpi) p.getInt(KEY_SESSION_ORIGINAL_DPI, 0).takeIf { it > 0 } else null
        val originalRotation = p.getInt(KEY_SESSION_ORIGINAL_ROTATION, -1)
        if (originalRotation !in 0..3) return null
        return ProjectionSessionSnapshot(
            packageName = packageName,
            originalDpi = originalDpi,
            originalRotation = originalRotation,
        )
    }

    fun clearSessionSnapshot(context: Context) {
        prefs(context).edit()
            .remove(KEY_SESSION_ACTIVE)
            .remove(KEY_SESSION_PACKAGE)
            .remove(KEY_SESSION_ORIGINAL_DPI)
            .remove(KEY_SESSION_ORIGINAL_ROTATION)
            .apply()
    }
}
