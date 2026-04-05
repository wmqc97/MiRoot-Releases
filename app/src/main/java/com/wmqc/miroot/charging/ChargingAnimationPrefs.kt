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
    /** 开启后插电时仅在主屏启动充电动画，不迁背屏、不操作副屏桌面，便于对照背屏异常 */
    const val KEY_DEBUG_MAIN_SCREEN_ONLY: String = "charging_debug_main_screen_only"

    /** SharedPreferences 键：充电动画常亮。 */
    const val KEY_ALWAYS_ON: String = "charging_always_on_enabled"

    /**
     * 涨水动画相对速度（百分比）。100 = 内置基准（约 100% 电量时总时长约 3.2s），越大越快。
     * 取值范围 [MIN_FILL_RISE_SPEED_PERCENT], [MAX_FILL_RISE_SPEED_PERCENT]。
     */
    const val KEY_FILL_RISE_SPEED_PERCENT: String = "charging_fill_rise_speed_percent"

    const val MIN_FILL_RISE_SPEED_PERCENT: Int = 25
    const val MAX_FILL_RISE_SPEED_PERCENT: Int = 300
    private const val DEFAULT_FILL_RISE_SPEED_PERCENT: Int = 100

    /**
     * 与 [com.wmqc.miroot.charging.RearScreenChargingActivity] 中液面满幅刻度基准时长一致。
     * 实际动画：`duration ∝ targetLevel * FILL_MS_FOR_FULL_SCALE * 100 / speedPercent`。
     */
    const val FILL_MS_FOR_FULL_SCALE: Int = 3200

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
    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isDebugMainScreenOnly(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_MAIN_SCREEN_ONLY, false)

    fun setDebugMainScreenOnly(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_MAIN_SCREEN_ONLY, enabled)
            .apply()
    }

    fun isAlwaysOn(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_ON, false)

    fun setAlwaysOn(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALWAYS_ON, enabled)
            .apply()
    }

    @JvmStatic
    fun getFillRiseSpeedPercent(context: Context): Int {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FILL_RISE_SPEED_PERCENT, DEFAULT_FILL_RISE_SPEED_PERCENT)
        return raw.coerceIn(MIN_FILL_RISE_SPEED_PERCENT, MAX_FILL_RISE_SPEED_PERCENT)
    }

    @JvmStatic
    fun setFillRiseSpeedPercent(context: Context, percent: Int) {
        val p = percent.coerceIn(MIN_FILL_RISE_SPEED_PERCENT, MAX_FILL_RISE_SPEED_PERCENT)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FILL_RISE_SPEED_PERCENT, p)
            .apply()
    }
}
