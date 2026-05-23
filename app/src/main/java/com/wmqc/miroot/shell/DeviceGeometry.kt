package com.wmqc.miroot.shell

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.lyrics.DeviceModelHelper
import java.io.File
import java.io.FileOutputStream

/**
 * 带壳合成坐标与底图文件名（与 Flutter 迁移键兼容）。
 */
object DeviceGeometry {

    private const val PREFS_FLUTTER = "FlutterSharedPreferences"

    private const val KEY_SCREENSHOT_SHELL_ENABLED = "flutter.miroot_screenshot_shell_enabled"
    private const val KEY_STICKER_ENABLED = "flutter.miroot_sticker_enabled"
    private const val KEY_STICKER_ROUNDED = "flutter.miroot_sticker_rounded"
    private const val KEY_STICKER_CENTER_HORIZONTAL = "flutter.miroot_sticker_center_horizontal"
    private const val KEY_STICKER_CENTER_VERTICAL = "flutter.miroot_sticker_center_vertical"
    private const val KEY_STICKER_CORNER_DP = "flutter.miroot_sticker_corner_dp"
    private const val KEY_STICKER_SCALE_MODE = "flutter.miroot_sticker_scale_mode"

    private const val DEFAULT_STICKER_CORNER_DP = 45

    private const val PREFS_RECORD = "miroot_record"
    private const val KEY_RECORD_COMPOSITE = "record_composite"
    /** 录屏悬浮窗「贴图」：仅控制带壳导出时是否叠贴图层，与功能页全局「启用贴图」独立。 */
    private const val KEY_RECORD_STICKER_COMPOSITE = "record_sticker_composite"

    /** 用户自定义贴图，与底图同目录。 */
    const val STICKER_OVERLAY_FILE_NAME = "sticker_overlay.png"

    /** 贴图圆角半径 (dp) 上限，与输入校验一致。 */
    const val STICKER_CORNER_RADIUS_DP_MAX = 128

    private const val DEFAULT_STICKER_X = 24
    private const val DEFAULT_STICKER_Y = 940

    /** 宽或高为 0 表示使用贴图文件原始像素尺寸。 */
    private const val DEFAULT_STICKER_W = 0
    private const val DEFAULT_STICKER_H = 0

    /**
     * 带壳截图/底图切换专用目录（应用外置私有）：
     * `/storage/emulated/0/Android/data/<pkg>/files/screenshot_work/`
     */
    const val SCREENSHOT_WORK_DIR_NAME = "screenshot_work"

    fun screenshotWorkDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, SCREENSHOT_WORK_DIR_NAME).apply { mkdirs() }
    }

    /** Pro Max 半屏/全屏合成画布与叠放区参数（与 Flutter `_kPromax*` 一致）。 */
    const val PROMAX_HALF_CANVAS_W = 1214
    const val PROMAX_HALF_CANVAS_H = 1817
    const val PROMAX_HALF_X = 123
    const val PROMAX_HALF_Y = 109
    const val PROMAX_HALF_TARGET_W = 976
    const val PROMAX_HALF_TARGET_H = 596

    const val PROMAX_FULL_X = 125
    const val PROMAX_FULL_Y = 113
    const val PROMAX_FULL_TARGET_W = 976
    const val PROMAX_FULL_TARGET_H = 596
    const val PROMAX_FULL_CANVAS_W = 1217
    const val PROMAX_FULL_CANVAS_H = 2530

    /**
     * Pro 半屏底图 `prol.webp` 等：画布与旧版 APK 内资源一致（1126×2416）。
     * 未保存偏好时背屏画面叠放默认（左上角）；与 [DeviceModelHelper.getCompositeScreenshotCoordinates]（非 ProMax）一致。
     */
    private const val PRO_CANVAS_W = 1126
    private const val PRO_CANVAS_H = 2416

private const val PRO_SCREEN_X = 117
// Pro 半屏默认叠放坐标（用户未保存偏好时使用）
private const val PRO_SCREEN_Y = 107

    /** Pro 全壳 `prol2.webp` 等：旧版资源画布 1122×2332；未保存偏好时默认 (112,112)，可在设置中微调（与半屏默认值独立）。 */
    private const val PRO_FULL_CANVAS_W = 1122
    private const val PRO_FULL_CANVAS_H = 2332
private const val PRO_FULL_X = 115
private const val PRO_FULL_Y = 105

    @Volatile
    private var cachedIsProMax: Boolean? = null

    fun isProMaxModel(): Boolean {
        cachedIsProMax?.let { return it }
        val market = EnvironmentProbe.readSystemProperty("ro.product.marketname")?.lowercase().orEmpty()
        if (market.isNotEmpty()) {
            if (market.contains("promax") || market.contains("pro max")) {
                cachedIsProMax = true
                return true
            }
            if (market.contains("17 pro") && !market.contains("max")) {
                cachedIsProMax = false
                return false
            }
        }
        val all = buildString {
            append(Build.MODEL, " ")
            append(EnvironmentProbe.readSystemProperty("ro.product.model").orEmpty(), " ")
            append(EnvironmentProbe.readSystemProperty("ro.product.name").orEmpty(), " ")
            append(Build.DEVICE, " ", Build.PRODUCT, " ")
            append(EnvironmentProbe.readSystemProperty("ro.product.brand").orEmpty(), " ")
            append(market, " ")
            append(EnvironmentProbe.readSystemProperty("ro.miui.product.name").orEmpty())
        }.lowercase()
        val isMax = all.contains("promax") || all.contains("pro max")
        cachedIsProMax = if (isMax) true else if (all.contains("pro")) false else true
        return cachedIsProMax!!
    }

    fun isProMaxShellFull(context: Context): Boolean {
        if (!isProMaxModel()) return false
        return flutterPrefs(context).getBoolean("flutter.promax_shell_full", false)
    }

    /** Pro 机型是否使用全壳底图（`pro*2.webp`）。 */
    fun isProShellFull(context: Context): Boolean {
        if (isProMaxModel()) return false
        return flutterPrefs(context).getBoolean("flutter.pro_shell_full", false)
    }

    /** ProMax / Pro 当前是否为「全壳」底图模式（用于 UI 与合成）。 */
    fun isShellFullBackdrop(context: Context): Boolean =
        if (isProMaxModel()) isProMaxShellFull(context) else isProShellFull(context)

    /**
     * 带壳叠放左上角 [x,y]（截图与录屏共用）。
     * 按当前底图半屏/全壳模式读取；两套数值在功能页弹窗中分别编辑。
     */
    fun compositeXY(context: Context): IntArray {
        val prefs = flutterPrefs(context)
        if (!isProMaxModel()) {
            return if (isProShellFull(context)) {
                val x = readFlutterInt(
                    prefs,
                    "pro_full_overlay_x",
                    readFlutterInt(prefs, "pro_overlay_x", PRO_FULL_X),
                )
                val y = readFlutterInt(
                    prefs,
                    "pro_full_overlay_y",
                    readFlutterInt(prefs, "pro_overlay_y", PRO_FULL_Y),
                )
                intArrayOf(x, y)
            } else {
                val x = readFlutterInt(
                    prefs,
                    "pro_focus_overlay_x",
                    readFlutterInt(
                        prefs,
                        "pro_overlay_x",
                        readFlutterInt(
                            prefs,
                            "pro_screenshot_overlay_x",
                            readFlutterInt(prefs, "pro_record_overlay_x", PRO_SCREEN_X),
                        ),
                    ),
                )
                val y = readFlutterInt(
                    prefs,
                    "pro_focus_overlay_y",
                    readFlutterInt(
                        prefs,
                        "pro_overlay_y",
                        readFlutterInt(
                            prefs,
                            "pro_screenshot_overlay_y",
                            readFlutterInt(prefs, "pro_record_overlay_y", PRO_SCREEN_Y),
                        ),
                    ),
                )
                intArrayOf(x, y)
            }
        }
        return if (isProMaxShellFull(context)) {
            val x = readFlutterInt(
                prefs,
                "promax_full_overlay_x",
                readFlutterInt(
                    prefs,
                    "promax_full_screenshot_overlay_x",
                    readFlutterInt(prefs, "promax_full_record_overlay_x", PROMAX_FULL_X),
                ),
            )
            val y = readFlutterInt(
                prefs,
                "promax_full_overlay_y",
                readFlutterInt(
                    prefs,
                    "promax_full_screenshot_overlay_y",
                    readFlutterInt(prefs, "promax_full_record_overlay_y", PROMAX_FULL_Y),
                ),
            )
            intArrayOf(x, y)
        } else {
            val x = readFlutterInt(
                prefs,
                "promax_focus_overlay_x",
                readFlutterInt(
                    prefs,
                    "promax_focus_screenshot_overlay_x",
                    readFlutterInt(prefs, "promax_focus_record_overlay_x", PROMAX_HALF_X),
                ),
            )
            val y = readFlutterInt(
                prefs,
                "promax_focus_overlay_y",
                readFlutterInt(
                    prefs,
                    "promax_focus_screenshot_overlay_y",
                    readFlutterInt(prefs, "promax_focus_record_overlay_y", PROMAX_HALF_Y),
                ),
            )
            intArrayOf(x, y)
        }
    }

    /** 半屏（聚焦）底图对应的叠放 XY；Pro / ProMax 各读各的键，逻辑一致。 */
    fun compositeXYForHalfBackdrop(context: Context): IntArray {
        val prefs = flutterPrefs(context)
        if (!isProMaxModel()) {
            val x = readFlutterInt(
                prefs,
                "pro_focus_overlay_x",
                readFlutterInt(
                    prefs,
                    "pro_overlay_x",
                    readFlutterInt(
                        prefs,
                        "pro_screenshot_overlay_x",
                        readFlutterInt(prefs, "pro_record_overlay_x", PRO_SCREEN_X),
                    ),
                ),
            )
            val y = readFlutterInt(
                prefs,
                "pro_focus_overlay_y",
                readFlutterInt(
                    prefs,
                    "pro_overlay_y",
                    readFlutterInt(
                        prefs,
                        "pro_screenshot_overlay_y",
                        readFlutterInt(prefs, "pro_record_overlay_y", PRO_SCREEN_Y),
                    ),
                ),
            )
            return intArrayOf(x, y)
        }
        val x = readFlutterInt(
            prefs,
            "promax_focus_overlay_x",
            readFlutterInt(
                prefs,
                "promax_focus_screenshot_overlay_x",
                readFlutterInt(prefs, "promax_focus_record_overlay_x", PROMAX_HALF_X),
            ),
        )
        val y = readFlutterInt(
            prefs,
            "promax_focus_overlay_y",
            readFlutterInt(
                prefs,
                "promax_focus_screenshot_overlay_y",
                readFlutterInt(prefs, "promax_focus_record_overlay_y", PROMAX_HALF_Y),
            ),
        )
        return intArrayOf(x, y)
    }

    /** 全壳底图对应的叠放 XY；Pro / ProMax 各读各的键。 */
    fun compositeXYForFullBackdrop(context: Context): IntArray {
        val prefs = flutterPrefs(context)
        if (!isProMaxModel()) {
            val x = readFlutterInt(
                prefs,
                "pro_full_overlay_x",
                readFlutterInt(prefs, "pro_overlay_x", PRO_FULL_X),
            )
            val y = readFlutterInt(
                prefs,
                "pro_full_overlay_y",
                readFlutterInt(prefs, "pro_overlay_y", PRO_FULL_Y),
            )
            return intArrayOf(x, y)
        }
        val x = readFlutterInt(
            prefs,
            "promax_full_overlay_x",
            readFlutterInt(
                prefs,
                "promax_full_screenshot_overlay_x",
                readFlutterInt(prefs, "promax_full_record_overlay_x", PROMAX_FULL_X),
            ),
        )
        val y = readFlutterInt(
            prefs,
            "promax_full_overlay_y",
            readFlutterInt(
                prefs,
                "promax_full_screenshot_overlay_y",
                readFlutterInt(prefs, "promax_full_record_overlay_y", PROMAX_FULL_Y),
            ),
        )
        return intArrayOf(x, y)
    }

    /** 写入半屏（聚焦）模式 XY（当前机型对应 Pro 或 ProMax 键）。 */
    fun persistCompositeXYHalf(context: Context, x: Int, y: Int) {
        val e = flutterPrefs(context).edit()
        if (isProMaxModel()) {
            e.putInt("flutter.promax_focus_overlay_x", x)
            e.putInt("flutter.promax_focus_overlay_y", y)
        } else {
            e.putInt("flutter.pro_focus_overlay_x", x)
            e.putInt("flutter.pro_focus_overlay_y", y)
        }
        e.apply()
    }

    /** 写入全壳模式 XY（当前机型对应 Pro 或 ProMax 键）。 */
    fun persistCompositeXYFull(context: Context, x: Int, y: Int) {
        val e = flutterPrefs(context).edit()
        if (isProMaxModel()) {
            e.putInt("flutter.promax_full_overlay_x", x)
            e.putInt("flutter.promax_full_overlay_y", y)
        } else {
            e.putInt("flutter.pro_full_overlay_x", x)
            e.putInt("flutter.pro_full_overlay_y", y)
        }
        e.apply()
    }

    /** 清除已保存的叠放坐标（含旧版分截图/录屏键），恢复内置默认。 */
    fun resetCompositeXYOverrides(context: Context) {
        val e = flutterPrefs(context).edit()
        listOf(
            "flutter.pro_overlay_x",
            "flutter.pro_overlay_y",
            "flutter.pro_screenshot_overlay_x",
            "flutter.pro_screenshot_overlay_y",
            "flutter.pro_record_overlay_x",
            "flutter.pro_record_overlay_y",
            "flutter.promax_focus_overlay_x",
            "flutter.promax_focus_overlay_y",
            "flutter.promax_focus_screenshot_overlay_x",
            "flutter.promax_focus_screenshot_overlay_y",
            "flutter.promax_focus_record_overlay_x",
            "flutter.promax_focus_record_overlay_y",
            "flutter.promax_full_overlay_x",
            "flutter.promax_full_overlay_y",
            "flutter.promax_full_screenshot_overlay_x",
            "flutter.promax_full_screenshot_overlay_y",
            "flutter.promax_full_record_overlay_x",
            "flutter.promax_full_record_overlay_y",
            "flutter.pro_focus_overlay_x",
            "flutter.pro_focus_overlay_y",
            "flutter.pro_full_overlay_x",
            "flutter.pro_full_overlay_y",
        ).forEach { e.remove(it) }
        e.apply()
    }

    /**
     * 当前底图 PNG 文件的真实像素宽高（与合成、贴图坐标系一致）。
     * 若无法读取则返回 null（可回退 [canvasSize]）。
     */
    fun phoneBackBitmapPixelSize(context: Context): IntArray? {
        val path = resolvePhoneBackPath(context) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return intArrayOf(o.outWidth, o.outHeight)
    }

    /** 底图画布尺寸 [w,h]（与 PNG 像素一致）。 */
    fun canvasSize(context: Context): IntArray {
        if (!isProMaxModel()) {
            return if (isProShellFull(context)) {
                intArrayOf(PRO_FULL_CANVAS_W, PRO_FULL_CANVAS_H)
            } else {
                intArrayOf(PRO_CANVAS_W, PRO_CANVAS_H)
            }
        }
        return if (isProMaxShellFull(context)) {
            intArrayOf(PROMAX_FULL_CANVAS_W, PROMAX_FULL_CANVAS_H)
        } else {
            intArrayOf(PROMAX_HALF_CANVAS_W, PROMAX_HALF_CANVAS_H)
        }
    }

    /**
     * ProMax 下将录屏/截图缩放到叠放区尺寸；Pro 返回 [0,0] 表示不缩放（使用视频原始像素对齐坐标区）。
     */
    fun targetScaleSize(context: Context): IntArray {
        if (!isProMaxModel()) return intArrayOf(0, 0)
        return if (isProMaxShellFull(context)) {
            intArrayOf(PROMAX_FULL_TARGET_W, PROMAX_FULL_TARGET_H)
        } else {
            intArrayOf(PROMAX_HALF_TARGET_W, PROMAX_HALF_TARGET_H)
        }
    }

    fun phoneBackFileName(context: Context): String {
        val prefs = flutterPrefs(context)
        if (isProMaxModel()) {
            val type = prefs.getString("flutter.promax_back_image_type", "green") ?: "green"
            val base = when (type) {
                "white" -> "promaxb.webp"
                "gray" -> "promaxh.webp"
                "purple" -> "promaxz.webp"
                else -> "promaxl.webp"
            }
            return if (isProMaxShellFull(context) && base.endsWith(".webp")) {
                base.substring(0, base.length - 5) + "2.webp"
            } else {
                base
            }
        }
        val type = prefs.getString("flutter.pro_back_image_type", "green") ?: "green"
        val base = when (type) {
            "white" -> "prob.webp"
            "gray" -> "proh.webp"
            "purple" -> "proz.webp"
            else -> "prol.webp"
        }
        return if (isProShellFull(context) && base.endsWith(".webp")) {
            base.substring(0, base.length - 5) + "2.webp"
        } else {
            base
        }
    }

    fun readProBackColorKey(context: Context): String =
        flutterPrefs(context).getString("flutter.pro_back_image_type", "green") ?: "green"

    fun readPromaxBackColorKey(context: Context): String =
        flutterPrefs(context).getString("flutter.promax_back_image_type", "green") ?: "green"

    /** 功能页截图与快捷设置磁贴是否使用带壳合成（默认开启，与旧版磁贴行为一致）。 */
    fun isScreenshotShellEnabled(context: Context): Boolean =
        flutterPrefs(context).getBoolean(KEY_SCREENSHOT_SHELL_ENABLED, true)

    fun persistScreenshotShellEnabled(context: Context, enabled: Boolean) {
        flutterPrefs(context).edit().putBoolean(KEY_SCREENSHOT_SHELL_ENABLED, enabled).apply()
    }

    /**
     * 录屏悬浮窗「带壳」是否开启（与 [com.wmqc.miroot.record.RearScreenRecordService] 读取逻辑一致）。
     * 贴图叠加以 [isRecordStickerCompositeEnabled] 与 [isStickerEnabled] 为准。
     */
    fun isRecordShellCompositeEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS_RECORD, Context.MODE_PRIVATE)
        if (p.contains(KEY_RECORD_COMPOSITE)) {
            return p.getBoolean(KEY_RECORD_COMPOSITE, true)
        }
        val fp = flutterPrefs(context)
        readOptionalBoolPref(fp, "record_composite_enabled")?.let { return it }
        readOptionalBoolPref(fp, "flutter.record_composite_enabled")?.let { return it }
        return true
    }

    /**
     * 录屏悬浮窗「贴图」开关：为 false 时带壳导出不叠贴图层（仍受 [isStickerEnabled] 与贴图文件影响）。
     * 未写入时默认 true，与旧版「全局贴图开则录屏叠贴图」一致。
     */
    fun isRecordStickerCompositeEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS_RECORD, Context.MODE_PRIVATE)
        if (p.contains(KEY_RECORD_STICKER_COMPOSITE)) {
            return p.getBoolean(KEY_RECORD_STICKER_COMPOSITE, true)
        }
        return true
    }

    fun persistRecordStickerComposite(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_RECORD, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RECORD_STICKER_COMPOSITE, enabled).apply()
    }

    fun isStickerCenterHorizontal(context: Context): Boolean =
        flutterPrefs(context).getBoolean(KEY_STICKER_CENTER_HORIZONTAL, false)

    fun persistStickerCenterHorizontal(context: Context, enabled: Boolean) {
        flutterPrefs(context).edit().putBoolean(KEY_STICKER_CENTER_HORIZONTAL, enabled).apply()
    }

    fun isStickerCenterVertical(context: Context): Boolean =
        flutterPrefs(context).getBoolean(KEY_STICKER_CENTER_VERTICAL, false)

    fun persistStickerCenterVertical(context: Context, enabled: Boolean) {
        flutterPrefs(context).edit().putBoolean(KEY_STICKER_CENTER_VERTICAL, enabled).apply()
    }

    private fun readOptionalBoolPref(prefs: SharedPreferences, key: String): Boolean? {
        if (!prefs.contains(key)) return null
        return try {
            prefs.getBoolean(key, false)
        } catch (_: ClassCastException) {
            try {
                val s = prefs.getString(key, null)?.trim() ?: return null
                when {
                    s.equals("true", ignoreCase = true) -> true
                    s.equals("false", ignoreCase = true) -> false
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 写入底图颜色及半屏/全壳偏好，并把对应 `assets/shell/<文件名>` 复制到 [screenshotWorkDir]（与旧版切换底图流程一致）。
     * @param shellFull 为 null 时不改半屏/全壳开关。
     * @return 是否已成功写入磁盘
     */
    fun persistShellBackdropSelection(
        context: Context,
        colorKey: String,
        shellFull: Boolean? = null,
    ): Boolean {
        val prefs = flutterPrefs(context).edit()
        if (isProMaxModel()) {
            prefs.putString("flutter.promax_back_image_type", colorKey)
            if (shellFull != null) {
                prefs.putBoolean("flutter.promax_shell_full", shellFull)
            }
        } else {
            prefs.putString("flutter.pro_back_image_type", colorKey)
            if (shellFull != null) {
                prefs.putBoolean("flutter.pro_shell_full", shellFull)
            }
        }
        if (!prefs.commit()) return false
        return copyCurrentShellAssetToScreenshotWork(context)
    }

    private fun copyCurrentShellAssetToScreenshotWork(context: Context): Boolean {
        val name = phoneBackFileName(context)
        val out = File(screenshotWorkDir(context), name)
        return try {
            DeviceModelHelper.openPhoneBackAssetInputStream(context.assets, name).use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            out.isFile && out.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 底图 PNG 绝对路径：优先 [screenshotWorkDir]（用户切换后的拷贝）、files、旧外置路径；最后从 assets 解压到 files。
     */
    fun stickerOverlayFile(context: Context): File =
        File(screenshotWorkDir(context), STICKER_OVERLAY_FILE_NAME)

    fun isStickerEnabled(context: Context): Boolean =
        flutterPrefs(context).getBoolean(KEY_STICKER_ENABLED, false)

    fun persistStickerEnabled(context: Context, enabled: Boolean) {
        flutterPrefs(context).edit().putBoolean(KEY_STICKER_ENABLED, enabled).apply()
    }

    fun isStickerRounded(context: Context): Boolean =
        flutterPrefs(context).getBoolean(KEY_STICKER_ROUNDED, true)

    fun persistStickerRounded(context: Context, rounded: Boolean) {
        flutterPrefs(context).edit().putBoolean(KEY_STICKER_ROUNDED, rounded).apply()
    }

    /** 贴图圆角半径（dp），开启「贴图圆角」时使用；范围 0–[MAX_STICKER_CORNER_DP]。 */
    fun stickerCornerRadiusDp(context: Context): Int =
        readFlutterInt(flutterPrefs(context), "miroot_sticker_corner_dp", DEFAULT_STICKER_CORNER_DP)
            .coerceIn(0, STICKER_CORNER_RADIUS_DP_MAX)

    fun persistStickerCornerRadiusDp(context: Context, dp: Int) {
        flutterPrefs(context).edit()
            .putInt(KEY_STICKER_CORNER_DP, dp.coerceIn(0, STICKER_CORNER_RADIUS_DP_MAX))
            .apply()
    }

    /** W、H 均大于 0 时生效；否则仍按原始像素绘制，模式无效。 */
    fun stickerScaleMode(context: Context): StickerScaleMode {
        val raw = flutterPrefs(context).getString(KEY_STICKER_SCALE_MODE, null)
        return StickerScaleHelper.fromPreferenceValue(raw)
    }

    fun persistStickerScaleMode(context: Context, mode: StickerScaleMode) {
        flutterPrefs(context).edit()
            .putString(KEY_STICKER_SCALE_MODE, StickerScaleHelper.toPreferenceValue(mode))
            .apply()
    }

    /** 半屏底图模式下的贴图左上角坐标。 */
    fun stickerXYForHalfBackdrop(context: Context): IntArray {
        val prefs = flutterPrefs(context)
        if (!isProMaxModel()) {
            val x = readFlutterInt(prefs, "pro_sticker_half_x", DEFAULT_STICKER_X)
            val y = readFlutterInt(prefs, "pro_sticker_half_y", DEFAULT_STICKER_Y)
            return intArrayOf(x, y)
        }
        val x = readFlutterInt(prefs, "promax_sticker_half_x", DEFAULT_STICKER_X)
        val y = readFlutterInt(prefs, "promax_sticker_half_y", DEFAULT_STICKER_Y)
        return intArrayOf(x, y)
    }

    /** 全壳底图模式下的贴图左上角坐标。 */
    fun stickerXYForFullBackdrop(context: Context): IntArray {
        val prefs = flutterPrefs(context)
        if (!isProMaxModel()) {
            val x = readFlutterInt(prefs, "pro_sticker_full_x", DEFAULT_STICKER_X)
            val y = readFlutterInt(prefs, "pro_sticker_full_y", DEFAULT_STICKER_Y)
            return intArrayOf(x, y)
        }
        val x = readFlutterInt(prefs, "promax_sticker_full_x", DEFAULT_STICKER_X)
        val y = readFlutterInt(prefs, "promax_sticker_full_y", DEFAULT_STICKER_Y)
        return intArrayOf(x, y)
    }

    /** 当前半屏/全壳模式下用于合成的贴图坐标。 */
    fun stickerXY(context: Context): IntArray =
        if (isShellFullBackdrop(context)) stickerXYForFullBackdrop(context) else stickerXYForHalfBackdrop(context)

    /** 半屏底图下贴图目标宽高；[0] 或 [1] 为 0 表示该维用原始尺寸。 */
    fun stickerSizeForHalfBackdrop(context: Context): IntArray {
        val prefs = flutterPrefs(context)
        if (!isProMaxModel()) {
            val w = readFlutterInt(prefs, "pro_sticker_half_w", DEFAULT_STICKER_W)
            val h = readFlutterInt(prefs, "pro_sticker_half_h", DEFAULT_STICKER_H)
            return intArrayOf(w, h)
        }
        val w = readFlutterInt(prefs, "promax_sticker_half_w", DEFAULT_STICKER_W)
        val h = readFlutterInt(prefs, "promax_sticker_half_h", DEFAULT_STICKER_H)
        return intArrayOf(w, h)
    }

    fun stickerSizeForFullBackdrop(context: Context): IntArray {
        val prefs = flutterPrefs(context)
        if (!isProMaxModel()) {
            val w = readFlutterInt(prefs, "pro_sticker_full_w", DEFAULT_STICKER_W)
            val h = readFlutterInt(prefs, "pro_sticker_full_h", DEFAULT_STICKER_H)
            return intArrayOf(w, h)
        }
        val w = readFlutterInt(prefs, "promax_sticker_full_w", DEFAULT_STICKER_W)
        val h = readFlutterInt(prefs, "promax_sticker_full_h", DEFAULT_STICKER_H)
        return intArrayOf(w, h)
    }

    fun stickerTargetSize(context: Context): IntArray =
        if (isShellFullBackdrop(context)) stickerSizeForFullBackdrop(context) else stickerSizeForHalfBackdrop(context)

    fun persistStickerXYHalf(context: Context, x: Int, y: Int) {
        val e = flutterPrefs(context).edit()
        if (isProMaxModel()) {
            e.putInt("flutter.promax_sticker_half_x", x)
            e.putInt("flutter.promax_sticker_half_y", y)
        } else {
            e.putInt("flutter.pro_sticker_half_x", x)
            e.putInt("flutter.pro_sticker_half_y", y)
        }
        e.apply()
    }

    fun persistStickerXYFull(context: Context, x: Int, y: Int) {
        val e = flutterPrefs(context).edit()
        if (isProMaxModel()) {
            e.putInt("flutter.promax_sticker_full_x", x)
            e.putInt("flutter.promax_sticker_full_y", y)
        } else {
            e.putInt("flutter.pro_sticker_full_x", x)
            e.putInt("flutter.pro_sticker_full_y", y)
        }
        e.apply()
    }

    fun persistStickerSizeHalf(context: Context, w: Int, h: Int) {
        val e = flutterPrefs(context).edit()
        if (isProMaxModel()) {
            e.putInt("flutter.promax_sticker_half_w", w)
            e.putInt("flutter.promax_sticker_half_h", h)
        } else {
            e.putInt("flutter.pro_sticker_half_w", w)
            e.putInt("flutter.pro_sticker_half_h", h)
        }
        e.apply()
    }

    fun persistStickerSizeFull(context: Context, w: Int, h: Int) {
        val e = flutterPrefs(context).edit()
        if (isProMaxModel()) {
            e.putInt("flutter.promax_sticker_full_w", w)
            e.putInt("flutter.promax_sticker_full_h", h)
        } else {
            e.putInt("flutter.pro_sticker_full_w", w)
            e.putInt("flutter.pro_sticker_full_h", h)
        }
        e.apply()
    }

    /** 清除贴图偏好与文件。 */
    fun resetStickerOverlay(context: Context) {
        val e = flutterPrefs(context).edit()
        listOf(
            KEY_STICKER_ENABLED,
            KEY_STICKER_ROUNDED,
            KEY_STICKER_CENTER_HORIZONTAL,
            KEY_STICKER_CENTER_VERTICAL,
            KEY_STICKER_CORNER_DP,
            KEY_STICKER_SCALE_MODE,
            "flutter.pro_sticker_half_x",
            "flutter.pro_sticker_half_y",
            "flutter.pro_sticker_full_x",
            "flutter.pro_sticker_full_y",
            "flutter.pro_sticker_half_w",
            "flutter.pro_sticker_half_h",
            "flutter.pro_sticker_full_w",
            "flutter.pro_sticker_full_h",
            "flutter.promax_sticker_half_x",
            "flutter.promax_sticker_half_y",
            "flutter.promax_sticker_full_x",
            "flutter.promax_sticker_full_y",
            "flutter.promax_sticker_half_w",
            "flutter.promax_sticker_half_h",
            "flutter.promax_sticker_full_w",
            "flutter.promax_sticker_full_h",
        ).forEach { e.remove(it) }
        e.apply()
        try {
            stickerOverlayFile(context).delete()
        } catch (_: Exception) {
        }
    }

    fun resolvePhoneBackPath(context: Context): String? {
        val name = phoneBackFileName(context)
        val work = File(screenshotWorkDir(context), name)
        if (work.isFile && work.length() > 0L) {
            return work.absolutePath
        }
        val internal = File(context.filesDir, name)
        if (internal.isFile && internal.length() > 0) {
            return internal.absolutePath
        }
        val ext = File(
            context.getExternalFilesDir(null),
            "screenshots/$name",
        )
        if (ext.isFile && ext.length() > 0) {
            return ext.absolutePath
        }
        val legacy = File("/sdcard/Android/data/${context.packageName}/files/screenshots/$name")
        if (legacy.isFile && legacy.length() > 0) {
            return legacy.absolutePath
        }
        return copyPhoneBackFromAssets(context, name, internal)
    }

    private fun copyPhoneBackFromAssets(context: Context, fileName: String, target: File): String? {
        return try {
            DeviceModelHelper.openPhoneBackAssetInputStream(context.assets, fileName).use { input ->
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            if (target.isFile && target.length() > 0) target.absolutePath else null
        } catch (_: Exception) {
            null
        }
    }

    private fun flutterPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FLUTTER, Context.MODE_PRIVATE)

    private fun readFlutterInt(prefs: SharedPreferences, name: String, defaultValue: Int): Int {
        val fullKey = "flutter.$name"
        readIntKey(prefs, fullKey)?.let { return it }
        readIntKey(prefs, name)?.let { return it }
        return defaultValue
    }

    private fun readIntKey(prefs: SharedPreferences, key: String): Int? {
        if (!prefs.contains(key)) return null
        return try {
            prefs.getInt(key, 0)
        } catch (_: ClassCastException) {
            try {
                prefs.getLong(key, 0L).toInt()
            } catch (_: ClassCastException) {
                try {
                    prefs.getString(key, null)?.trim()?.toInt()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
