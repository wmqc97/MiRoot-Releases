package com.wmqc.miroot.charging

import android.content.Context
import kotlin.jvm.JvmStatic
import kotlin.math.roundToInt

/**
 * 背屏充电动画开关（功能页）。
 * 与 [ChargingService] 共用同一 SharedPreferences 名。
 */
object ChargingAnimationPrefs {
    const val PREFS_NAME: String = "MiRootCharging"
    const val KEY_ENABLED: String = "charging_animation_enabled"

    /** 插电期间充电动画常亮：周期性 KEYCODE_WAKEUP + 不自动 8s 结束（功能页已隐藏，偏好仍可迁移/调试）。 */
    const val KEY_ALWAYS_ON: String = "charging_always_on_enabled"

    /**
     * 涨水动画相对速度（百分比）。
     * 当前功能页滑块映射为「充满全屏约 4~8 秒」：
     * - 80% ≈ 4s（更快）
     * - 40% ≈ 8s（更慢）
     */
    const val KEY_FILL_RISE_SPEED_PERCENT: String = "charging_fill_rise_speed_percent"

    /** 背屏充电信息栏显示项配置（逗号分隔的 item ID 列表，按显示顺序）。 */
    const val KEY_INFO_ITEMS: String = "charging_info_items"
    const val DEFAULT_INFO_ITEMS: String = "power,charging_type,time,temperature"

    /** 所有可用的充电信息项定义（id → 显示名称）。 */
    @JvmStatic
    val ALL_INFO_ITEMS: LinkedHashMap<String, String> = linkedMapOf(
        "power" to "功率",
        "charging_type" to "充电类型",
        "time" to "预估充满时间",
        "temperature" to "温度",
        "voltage" to "电压",
        "current" to "电流",
        "capacity" to "当前容量",
    )

    const val MAX_INFO_ITEMS: Int = 4

    @JvmStatic
    fun getAllInfoItems(): LinkedHashMap<String, String> = ALL_INFO_ITEMS

    private fun parseInfoItems(raw: String): List<String> {
        val items = raw.split(",").map { it.trim() }.filter { it in ALL_INFO_ITEMS }
        // 去重并保留顺序
        val seen = mutableSetOf<String>()
        return items.filter { seen.add(it) }
    }

    @JvmStatic
    fun getInfoItems(context: Context): List<String> {
        migrateFromCredentialProtectedIfNeeded(context)
        val raw = prefs(context).getString(KEY_INFO_ITEMS, DEFAULT_INFO_ITEMS) ?: DEFAULT_INFO_ITEMS
        return parseInfoItems(raw)
    }

    @JvmStatic
    fun setInfoItems(context: Context, items: List<String>) {
        migrateFromCredentialProtectedIfNeeded(context)
        val filtered = items.filter { it in ALL_INFO_ITEMS }.distinct().take(MAX_INFO_ITEMS)
        prefs(context).edit().putString(KEY_INFO_ITEMS, filtered.joinToString(",")).apply()
    }

    const val MIN_FILL_RISE_SPEED_PERCENT: Int = 40
    const val MAX_FILL_RISE_SPEED_PERCENT: Int = 80
    private const val DEFAULT_FILL_RISE_SPEED_PERCENT: Int = 50

    /**
     * 与 [com.wmqc.miroot.charging.RearScreenChargingActivity] 中液面满幅刻度基准时长一致。
     * 实际动画：`duration ∝ targetLevel * FILL_MS_FOR_FULL_SCALE * 100 / speedPercent`。
     */
    const val FILL_MS_FOR_FULL_SCALE: Int = 3200

    private fun prefs(context: Context) =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun migrateFromCredentialProtectedIfNeeded(context: Context) {
        try {
            val app = context.applicationContext
            val dp = app.createDeviceProtectedStorageContext()
            if (dp.moveSharedPreferencesFrom(app, PREFS_NAME)) {
                dp.deleteSharedPreferences(PREFS_NAME + ".bak")
            }
        } catch (_: Throwable) {
            // 迁移失败不影响读取，继续走默认值/已有值。
        }
    }

    /**
     * 当前涨水「速度」参数下，电量满刻度（目标 100%）时液面涨满一段的近似时长（ms），与 Activity 内公式一致。
     * 数值越小表示上涨越快。
     */
    @JvmStatic
    fun fillDurationMsForFullFill(speedPercent: Int): Int {
        val p = speedPercent
            .coerceIn(MIN_FILL_RISE_SPEED_PERCENT, MAX_FILL_RISE_SPEED_PERCENT)
            .toDouble()
            .coerceAtLeast(1.0)
        return (FILL_MS_FOR_FULL_SCALE * 100.0 / p).roundToInt()
    }

    /** 默认开启充电动画。 */
    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        migrateFromCredentialProtectedIfNeeded(context)
        prefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    @JvmStatic
    fun isAlwaysOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALWAYS_ON, false)

    @JvmStatic
    fun setAlwaysOn(context: Context, enabled: Boolean) {
        migrateFromCredentialProtectedIfNeeded(context)
        prefs(context)
            .edit()
            .putBoolean(KEY_ALWAYS_ON, enabled)
            .apply()
    }

    @JvmStatic
    fun getFillRiseSpeedPercent(context: Context): Int {
        val raw = prefs(context)
            .getInt(KEY_FILL_RISE_SPEED_PERCENT, DEFAULT_FILL_RISE_SPEED_PERCENT)
        return raw.coerceIn(MIN_FILL_RISE_SPEED_PERCENT, MAX_FILL_RISE_SPEED_PERCENT)
    }

    @JvmStatic
    fun setFillRiseSpeedPercent(context: Context, percent: Int) {
        val p = percent.coerceIn(MIN_FILL_RISE_SPEED_PERCENT, MAX_FILL_RISE_SPEED_PERCENT)
        migrateFromCredentialProtectedIfNeeded(context)
        prefs(context)
            .edit()
            .putInt(KEY_FILL_RISE_SPEED_PERCENT, p)
            .apply()
    }
}
