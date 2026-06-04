package com.wmqc.miroot.charging

import android.content.Context
import android.graphics.Typeface
import com.wmqc.miroot.lyrics.LyricsFontHelper
import java.io.File
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

    /** 充电动画漂浮物：none=无，image=自定义图片，battery=电量百分比浮于水中。 */
    const val KEY_FLOATING_DISPLAY: String = "charging_floating_display"
    const val FLOATING_NONE: String = "none"
    const val FLOATING_IMAGE: String = "image"
    const val FLOATING_BATTERY: String = "battery"

    /** @deprecated 已由 [KEY_FLOATING_DISPLAY] 与水体透明度替代，仅作迁移读取。 */
    const val KEY_LIQUID_STYLE: String = "charging_liquid_style"
    private const val LIQUID_STYLE_TRANSPARENT: String = "transparent"

    private const val CHARGING_FILES_DIR: String = "charging"
    private const val MASCOT_FILE_NAME: String = "mascot.png"
    private const val BACKGROUND_FILE_NAME: String = "background.png"
    private const val BACKGROUND_VIDEO_FILE_NAME: String = "background.mp4"

    const val KEY_WATER_COLOR: String = "charging_water_color"
    const val KEY_WATER_OPACITY: String = "charging_water_opacity_percent"

    const val MIN_WATER_OPACITY_PERCENT: Int = 10
    const val MAX_WATER_OPACITY_PERCENT: Int = 100
    private const val DEFAULT_WATER_OPACITY_PERCENT: Int = 100

    /** 背屏充电信息栏显示项配置（逗号分隔的 item ID 列表，按显示顺序）。 */
    const val KEY_INFO_ITEMS: String = "charging_info_items"
    const val DEFAULT_INFO_ITEMS: String = "power,time,temperature"

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

    /** 充电动画中央电量 / 漂浮电量字体（独立于背屏歌词字体）。 */
    const val KEY_FONT: String = "charging_font"
    const val KEY_FONT_CUSTOM_PATH: String = "charging_font_custom_path"
    const val DEFAULT_FONT: String = LyricsFontHelper.ID_MFGEHEI

    @JvmStatic
    fun getAllInfoItems(): LinkedHashMap<String, String> = ALL_INFO_ITEMS

    @JvmStatic
    fun getFontId(context: Context): String {
        migrateFromCredentialProtectedIfNeeded(context)
        var fontId = LyricsFontHelper.normalizeFontId(
            prefs(context).getString(KEY_FONT, null),
        )
        if (fontId == LyricsFontHelper.ID_CUSTOM) {
            val path = getCustomFontPath(context)
            if (path.isNullOrEmpty() || !File(path).isFile) {
                fontId = DEFAULT_FONT
            }
        }
        return fontId
    }

    @JvmStatic
    fun getCustomFontPath(context: Context): String? {
        migrateFromCredentialProtectedIfNeeded(context)
        val path = prefs(context).getString(KEY_FONT_CUSTOM_PATH, null)?.trim().orEmpty()
        return path.ifEmpty { null }
    }

    @JvmStatic
    fun setFont(context: Context, fontId: String, customFontPath: String?) {
        migrateFromCredentialProtectedIfNeeded(context)
        val id = LyricsFontHelper.normalizeFontId(fontId)
        val path = if (id == LyricsFontHelper.ID_CUSTOM) {
            customFontPath?.trim()?.takeIf { it.isNotEmpty() && File(it).isFile }
        } else {
            null
        }
        val finalId = if (id == LyricsFontHelper.ID_CUSTOM && path == null) DEFAULT_FONT else id
        prefs(context).edit()
            .putString(KEY_FONT, finalId)
            .putString(KEY_FONT_CUSTOM_PATH, path)
            .apply()
    }

    /** 解析充电动画显示字体（电量、底部信息、漂浮信息等全文案）。 */
    @JvmStatic
    fun resolveTypeface(context: Context): Typeface {
        val app = context.applicationContext
        val fontId = getFontId(app)
        val customPath = if (fontId == LyricsFontHelper.ID_CUSTOM) getCustomFontPath(app) else null
        return LyricsFontHelper.resolveTypeface(app, fontId, customPath)
    }

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

    /** 涨水动画结束后背屏继续展示的时长（与 [com.wmqc.miroot.charging.RearScreenChargingActivity] 一致）。 */
    const val CHARGING_AUTO_FINISH_HOLD_MS: Long = 10_000L

    /**
     * 预估一次充电动画在背屏的总可见时长（涨水 + 停留），用于功能页预览提示。
     * 与 Activity 内涨水时长、自动关闭逻辑使用同一套偏好。
     */
    @JvmStatic
    fun estimateChargingVisibleDurationMs(context: Context, batteryPercent: Int): Long {
        val level = batteryPercent.coerceIn(0, 100)
        val fillMs = fillDurationMsForFullFill(getFillRiseSpeedPercent(context)).toLong() * level / 100L
        return fillMs + CHARGING_AUTO_FINISH_HOLD_MS
    }

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

    @JvmStatic
    fun getFloatingDisplay(context: Context): String {
        migrateFromCredentialProtectedIfNeeded(context)
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_FLOATING_DISPLAY, null)
        if (raw != null) {
            return when (raw) {
                FLOATING_IMAGE, FLOATING_BATTERY -> raw
                else -> FLOATING_NONE
            }
        }
        // 旧版「透明液体」迁移：有自定义形象则保留图片模式，否则无漂浮物
        if (prefs.getString(KEY_LIQUID_STYLE, null) == LIQUID_STYLE_TRANSPARENT) {
            return if (hasCustomMascot(context)) FLOATING_IMAGE else FLOATING_NONE
        }
        return FLOATING_NONE
    }

    @JvmStatic
    fun setFloatingDisplay(context: Context, mode: String) {
        migrateFromCredentialProtectedIfNeeded(context)
        val m = when (mode) {
            FLOATING_IMAGE, FLOATING_BATTERY -> mode
            else -> FLOATING_NONE
        }
        prefs(context).edit().putString(KEY_FLOATING_DISPLAY, m).apply()
    }

    @JvmStatic
    fun customMascotFile(context: Context): File {
        val root = context.applicationContext.createDeviceProtectedStorageContext().filesDir
        return File(File(root, CHARGING_FILES_DIR), MASCOT_FILE_NAME)
    }

    @JvmStatic
    fun hasCustomMascot(context: Context): Boolean {
        val f = customMascotFile(context)
        return f.isFile && f.length() > 0L
    }

    @JvmStatic
    fun customBackgroundFile(context: Context): File {
        val root = context.applicationContext.createDeviceProtectedStorageContext().filesDir
        return File(File(root, CHARGING_FILES_DIR), BACKGROUND_FILE_NAME)
    }

    @JvmStatic
    fun hasCustomBackground(context: Context): Boolean {
        val f = customBackgroundFile(context)
        return f.isFile && f.length() > 0L
    }

    @JvmStatic
    fun customBackgroundVideoFile(context: Context): File {
        val root = context.applicationContext.createDeviceProtectedStorageContext().filesDir
        return File(File(root, CHARGING_FILES_DIR), BACKGROUND_VIDEO_FILE_NAME)
    }

    @JvmStatic
    fun hasCustomBackgroundVideo(context: Context): Boolean {
        val f = customBackgroundVideoFile(context)
        return f.isFile && f.length() > 0L
    }

    /** 旧版默认亮绿色，读取时迁移为 [ChargingWaterColor.DEFAULT_ARGB] 深绿。 */
    private const val LEGACY_DEFAULT_WATER_COLOR: Int = 0xFF30FF8A.toInt()

    @JvmStatic
    fun getWaterColor(context: Context): Int {
        migrateFromCredentialProtectedIfNeeded(context)
        val raw = prefs(context).getInt(KEY_WATER_COLOR, ChargingWaterColor.DEFAULT_ARGB)
        val color = raw or 0xFF000000.toInt()
        return if (color == LEGACY_DEFAULT_WATER_COLOR) {
            ChargingWaterColor.DEFAULT_ARGB
        } else {
            color
        }
    }

    @JvmStatic
    fun setWaterColor(context: Context, argb: Int) {
        migrateFromCredentialProtectedIfNeeded(context)
        prefs(context).edit().putInt(KEY_WATER_COLOR, argb or 0xFF000000.toInt()).apply()
    }

    @JvmStatic
    fun getWaterOpacityPercent(context: Context): Int {
        migrateFromCredentialProtectedIfNeeded(context)
        val raw = prefs(context).getInt(KEY_WATER_OPACITY, DEFAULT_WATER_OPACITY_PERCENT)
        return raw.coerceIn(MIN_WATER_OPACITY_PERCENT, MAX_WATER_OPACITY_PERCENT)
    }

    @JvmStatic
    fun setWaterOpacityPercent(context: Context, percent: Int) {
        migrateFromCredentialProtectedIfNeeded(context)
        val p = percent.coerceIn(MIN_WATER_OPACITY_PERCENT, MAX_WATER_OPACITY_PERCENT)
        prefs(context).edit().putInt(KEY_WATER_OPACITY, p).apply()
    }
}
