package com.wmqc.miroot.rear

import android.content.Context

object RearAssistPrefs {
    private const val PREFS_NAME = "miroot_rear_assist"
    /** 与 [com.wmqc.miroot.ui.features.FeaturesFragment] 中 FLUTTER_KEEP_SCREEN_ON_KEY 一致 */
    private const val FLUTTER_KEEP_SCREEN_ON_KEY = "flutter.keep_screen_on_enabled"

    const val KEY_PROXIMITY = "proximity_sensor_enabled"
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on_enabled"
    const val KEY_RECORD_SCREENSHOT_KEEP_SCREEN_ON = "record_screenshot_keep_screen_on_enabled"
    const val KEY_ALWAYS_WAKEUP = "always_wakeup_enabled"
    const val KEY_INTERVAL_MS = "rear_wakeup_interval_ms"

    const val DEFAULT_INTERVAL_MS = 1000
    const val MIN_INTERVAL_MS = 100
    const val MAX_INTERVAL_MS = 10_000

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 未写入偏好时默认关闭，避免用户未开「背屏遮盖检测」却仍拉起背屏辅助前台服务 */
    fun isProximityEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PROXIMITY, false)

    /**
     * 「投屏常亮」总开关（投屏/录屏/截图等）：与「始终常亮」共用间隔与 KEYCODE_WAKEUP，仅触发条件不同；原生未写入时与 Flutter 一致，**默认开启**。
     */
    fun isKeepScreenOnEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.contains(KEY_KEEP_SCREEN_ON)) {
            return ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                .getBoolean(FLUTTER_KEEP_SCREEN_ON_KEY, true)
        }
        return p.getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    /**
     * 录屏/截图专属常亮开关：仅影响录屏与截图链路，不影响投屏 Keeper。
     * 首次升级未写入时回落到历史「投屏常亮」值，避免行为突变。
     */
    fun isRecordScreenshotKeepScreenOnEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.contains(KEY_RECORD_SCREENSHOT_KEEP_SCREEN_ON)) {
            return isKeepScreenOnEnabled(ctx)
        }
        return p.getBoolean(KEY_RECORD_SCREENSHOT_KEEP_SCREEN_ON, true)
    }

    fun isAlwaysWakeupEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ALWAYS_WAKEUP, false)

    fun intervalMs(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
        return v.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    /**
     * 截图 / 单次唤醒后等待背屏亮稳：与功能页「发送间隔」同源，但上限 500ms，避免间隔拉到 10s 时截图卡死。
     */
    fun wakeSettleDelayMsAfterKeyevent(ctx: Context): Int =
        intervalMs(ctx).coerceIn(50, 500)

    /**
     * 是否需启动 [RearAssistService]（仅「始终常亮」）。
     * 遮盖检测在投屏期间由 [RearProjectionProximitySession] 处理；「投屏常亮」走 Flutter 与投屏前台服务。
     */
    fun anyFeatureEnabled(ctx: Context): Boolean =
        isAlwaysWakeupEnabled(ctx)
}
