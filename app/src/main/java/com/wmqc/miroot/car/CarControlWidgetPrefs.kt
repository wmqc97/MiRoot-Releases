package com.wmqc.miroot.car

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** 车控桌面小组件配置：全局默认 + 按 [appWidgetId] 覆盖。 */
object CarControlWidgetPrefs {

    private const val PREFS_NAME = "CarControlWidgetPrefs"
    private const val KEY_GLOBAL_ALPHA = "bg_alpha_global"
    private const val KEY_GLOBAL_FLAGS = "display_flags_global"
    private const val KEY_GLOBAL_CORNER_DP = "corner_radius_dp_global"

    const val DEFAULT_BG_ALPHA = 100
    const val DEFAULT_CORNER_RADIUS_DP = 16
    const val MIN_CORNER_RADIUS_DP = 0
    const val MAX_CORNER_RADIUS_DP = 32

    const val FLAG_FUEL = 1 shl 0
    const val FLAG_RANGE = 1 shl 1
    const val FLAG_PLATE = 1 shl 2
    /** 车内 / 车外温度（一行展示） */
    const val FLAG_TEMP = 1 shl 3
    /** 旧版「车外温度」勾选位，读取时视为 [FLAG_TEMP] */
    private const val FLAG_EXTERIOR_TEMP_LEGACY = 1 shl 4
    const val FLAG_UPDATE_TIME = 1 shl 5
    const val FLAG_ODOMETER = 1 shl 6
    const val FLAG_BATTERY = 1 shl 7
    const val FLAG_TIRE_PRESSURE = 1 shl 8
    /** 平均油耗 */
    const val FLAG_AVG_CONSUMPTION = 1 shl 9
    /** 发动机冷却液温度 */
    const val FLAG_COOLANT_TEMP = 1 shl 10
    /** 保养剩余里程 */
    const val FLAG_SERVICE_DISTANCE = 1 shl 11
    /** 电子手刹状态 */
    const val FLAG_EPB_STATUS = 1 shl 12
    /** 发动机转速 */
    const val FLAG_ENGINE_SPEED = 1 shl 13

    /** 油量与续航始终展示，不可在配置页关闭。 */
    val MANDATORY_DISPLAY_FLAGS: Int = FLAG_FUEL or FLAG_RANGE

    val DEFAULT_DISPLAY_FLAGS: Int =
        MANDATORY_DISPLAY_FLAGS or FLAG_UPDATE_TIME

    fun withMandatoryFlags(flags: Int): Int = flags or MANDATORY_DISPLAY_FLAGS

    fun globalBgAlpha(context: Context): Int =
        prefs(context).getInt(KEY_GLOBAL_ALPHA, DEFAULT_BG_ALPHA).coerceIn(10, 100)

    fun globalDisplayFlags(context: Context): Int =
        withMandatoryFlags(prefs(context).getInt(KEY_GLOBAL_FLAGS, DEFAULT_DISPLAY_FLAGS))

    fun globalCornerRadiusDp(context: Context): Int =
        prefs(context).getInt(KEY_GLOBAL_CORNER_DP, DEFAULT_CORNER_RADIUS_DP)
            .coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)

    fun cornerRadiusDp(context: Context, appWidgetId: Int): Int {
        val p = prefs(context)
        return if (p.contains(keyCorner(appWidgetId))) {
            p.getInt(keyCorner(appWidgetId), DEFAULT_CORNER_RADIUS_DP)
        } else {
            globalCornerRadiusDp(context)
        }.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
    }

    fun bgAlpha(context: Context, appWidgetId: Int): Int {
        val p = prefs(context)
        return if (p.contains(keyAlpha(appWidgetId))) {
            p.getInt(keyAlpha(appWidgetId), DEFAULT_BG_ALPHA)
        } else {
            globalBgAlpha(context)
        }.coerceIn(10, 100)
    }

    fun displayFlags(context: Context, appWidgetId: Int): Int {
        val p = prefs(context)
        val raw = if (p.contains(keyFlags(appWidgetId))) {
            p.getInt(keyFlags(appWidgetId), DEFAULT_DISPLAY_FLAGS)
        } else {
            globalDisplayFlags(context)
        }
        return withMandatoryFlags(raw)
    }

    fun save(
        context: Context,
        appWidgetId: Int,
        bgAlpha: Int,
        displayFlags: Int,
        cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
    ) {
        prefs(context).edit()
            .putInt(keyAlpha(appWidgetId), bgAlpha.coerceIn(10, 100))
            .putInt(keyFlags(appWidgetId), withMandatoryFlags(displayFlags))
            .putInt(keyCorner(appWidgetId), cornerRadiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP))
            .apply()
    }

    fun saveGlobal(
        context: Context,
        bgAlpha: Int,
        displayFlags: Int,
        cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
    ) {
        prefs(context).edit()
            .putInt(KEY_GLOBAL_ALPHA, bgAlpha.coerceIn(10, 100))
            .putInt(KEY_GLOBAL_FLAGS, withMandatoryFlags(displayFlags))
            .putInt(KEY_GLOBAL_CORNER_DP, cornerRadiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP))
            .apply()
    }

    /** 将全局配置写入所有已添加的小组件并刷新。 */
    fun applyGlobalToAllWidgets(context: Context) {
        val appCtx = context.applicationContext
        val alpha = globalBgAlpha(appCtx)
        val flags = globalDisplayFlags(appCtx)
        val cornerDp = globalCornerRadiusDp(appCtx)
        val manager = AppWidgetManager.getInstance(appCtx)
        val components = listOf(
            ComponentName(appCtx, CarControlAppWidgetProvider::class.java),
            ComponentName(appCtx, CarControlWidget6x2Provider::class.java),
            ComponentName(appCtx, CarControlWidget6x4Provider::class.java),
        )
        val ids = components.flatMap {
            manager.getAppWidgetIds(it).toList()
        }.toIntArray()
        if (ids.isEmpty()) return
        val editor = prefs(appCtx).edit()
        for (id in ids) {
            editor.putInt(keyAlpha(id), alpha)
            editor.putInt(keyFlags(id), flags)
            editor.putInt(keyCorner(id), cornerDp)
        }
        editor.apply()
        CarControlAppWidgetProvider.updateAll(appCtx, manager, ids)
    }

    fun remove(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(keyAlpha(appWidgetId))
            .remove(keyFlags(appWidgetId))
            .remove(keyCorner(appWidgetId))
            .remove(keyPendingKey(appWidgetId))
            .remove(keyPendingDisplay(appWidgetId))
            .apply()
    }

    data class PendingConfirm(
        val functionKey: String,
        val displayText: String,
    )

    fun setPending(context: Context, appWidgetId: Int, functionKey: String, displayText: String) {
        prefs(context).edit()
            .putString(keyPendingKey(appWidgetId), functionKey)
            .putString(keyPendingDisplay(appWidgetId), displayText)
            .apply()
    }

    fun getPending(context: Context, appWidgetId: Int): PendingConfirm? {
        val key = prefs(context).getString(keyPendingKey(appWidgetId), null)?.trim().orEmpty()
        if (key.isEmpty()) return null
        val display = prefs(context).getString(keyPendingDisplay(appWidgetId), null)?.trim().orEmpty()
        return PendingConfirm(key, display.ifEmpty { key })
    }

    fun clearPending(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(keyPendingKey(appWidgetId))
            .remove(keyPendingDisplay(appWidgetId))
            .apply()
    }

    fun hasFlag(flags: Int, bit: Int): Boolean = flags and bit != 0

    fun hasTempDisplay(flags: Int): Boolean =
        hasFlag(flags, FLAG_TEMP) || hasFlag(flags, FLAG_EXTERIOR_TEMP_LEGACY)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyAlpha(id: Int) = "bg_alpha_$id"
    private fun keyCorner(id: Int) = "corner_radius_dp_$id"
    private fun keyFlags(id: Int) = "display_flags_$id"
    private fun keyPendingKey(id: Int) = "pending_key_$id"
    private fun keyPendingDisplay(id: Int) = "pending_display_$id"
}
