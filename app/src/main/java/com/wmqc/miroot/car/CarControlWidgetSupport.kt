package com.wmqc.miroot.car

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.sqrt
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.wmqc.miroot.R

/** 小组件 RemoteViews 构建：日夜配色、车模、显示项、按钮图标。 */
object CarControlWidgetSupport {

    private const val BASE_WIDTH_DP = 250f
    private const val BASE_HEIGHT_DP = 130f
    private const val BASE_ICON_DP = 36f

    /** 背景位图：尽量接近小组件实际像素，避免 fitXY 放大导致圆角发糊。 */
    private const val MAX_WIDGET_BG_BITMAP_W = 720
    private const val MAX_WIDGET_BG_BITMAP_H = 480
    /** ARGB 背景最大像素数（压力测试上限，约 560KB）。 */
    private const val MAX_WIDGET_BG_PIXELS = 140_000
    /** RemoteViews 经 Binder 传输，图标/车模位图总和需低于 ~1MB。 */
    private const val MAX_WIDGET_BITMAP_W = 320
    private const val MAX_WIDGET_BITMAP_H = 200
    /** 车模较长边上限（按钮位图先绑定，车模可适度加大）。 */
    private const val MAX_WIDGET_CAR_LONG_SIDE_PX = 1440
    /** 车模 ARGB 最大像素数（约 2.6MB，仅车模单张位图）。 */
    private const val MAX_WIDGET_CAR_PIXELS = 720_000
    /** 输出位图相对布局区域的超采样，fitCenter 略缩小后更清晰。 */
    private const val WIDGET_CAR_OUTPUT_OVERSAMPLE = 1.45f
    /** 解码边长相对输出较长边的倍率，保证缩放始终向下。 */
    private const val WIDGET_CAR_DECODE_OVERSAMPLE = 1.35f
    /** 与 widget_car_control.xml 右侧车图列 layout_weight 一致。 */
    private const val WIDGET_CAR_COLUMN_WEIGHT = 0.56f
    /** 与 widget_car_control.xml 按钮图标 36dp 一致。 */
    private const val WIDGET_BTN_ICON_LAYOUT_DP = 36f
    /** 位图略大于 ImageView，fitCenter 缩小后边缘更清晰；禁止压到 48px 再放大。 */
    private const val WIDGET_BTN_ICON_OVERSAMPLE = 1.35f
    /** 按钮图标位图边长安全上限（ARGB，约 130KB/个）。 */
    private const val MAX_WIDGET_BTN_ICON_PX = 168

    data class WidgetLayoutMetrics(
        val widthPx: Int,
        val heightPx: Int,
        val iconPx: Int,
        val refreshIconPx: Int,
        val fuelIconPx: Int,
        val carMaxWidthPx: Int,
        val carMaxHeightPx: Int,
        val rangeMainSp: Float,
        val rangeLabelSp: Float,
        val fuelPercentSp: Float,
        val timeTextSp: Float,
        val infoTextSp: Float,
        val locationTextSp: Float,
        val cornerRadiusPx: Float,
    )

    data class WidgetTheme(
        val bgColor: Int,
        val bgGradientTop: Int,
        val bgGradientBottom: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val fuelAccent: Int,
        val statusParked: Int,
        val statusRunning: Int,
        val btnBg: Int,
        val btnIconIdle: Int,
        val btnIconActive: Int,
    )

    /** 小组件固定深色配色；文字用高对比色，适配半透明背景。 */
    fun themeForWidget(@Suppress("UNUSED_PARAMETER") context: Context): WidgetTheme =
        WidgetTheme(
            bgColor = 0xFF1E1E1E.toInt(),
            bgGradientTop = 0xFF23262D.toInt(),
            bgGradientBottom = 0xFF1E1E1E.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFFEBEEF3.toInt(),
            fuelAccent = 0xFF9EC9FF.toInt(),
            statusParked = 0xFFFFB74D.toInt(),
            statusRunning = 0xFF81C784.toInt(),
            btnBg = 0xFF2A2D35.toInt(),
            btnIconIdle = 0xFFFFFFFF.toInt(),
            btnIconActive = 0xFF7AB8FF.toInt(),
        )

    fun applyWidgetTextColor(views: RemoteViews, viewId: Int, color: Int) {
        views.setTextColor(viewId, color)
    }

    fun themeFor(context: Context): WidgetTheme {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            WidgetTheme(
                bgColor = 0xFF23262D.toInt(),
                bgGradientTop = 0xFF2A2E36.toInt(),
                bgGradientBottom = 0xFF1E2128.toInt(),
                textPrimary = 0xFFF0F0F0.toInt(),
                textSecondary = 0xFF8A8F98.toInt(),
                fuelAccent = 0xFF3482FF.toInt(),
                statusParked = 0xFFFF9800.toInt(),
                statusRunning = 0xFF4CAF50.toInt(),
                btnBg = 0xFF2A2D35.toInt(),
                btnIconIdle = 0xFFE0E0E0.toInt(),
                btnIconActive = 0xFF3482FF.toInt(),
            )
        } else {
            WidgetTheme(
                bgColor = 0xFFE8EDF3.toInt(),
                bgGradientTop = 0xFFEEF2F7.toInt(),
                bgGradientBottom = 0xFFE2E8F0.toInt(),
                textPrimary = 0xFF1A1D23.toInt(),
                textSecondary = 0xFF6B7280.toInt(),
                fuelAccent = 0xFF2563EB.toInt(),
                statusParked = 0xFFFF9800.toInt(),
                statusRunning = 0xFF16A34A.toInt(),
                btnBg = 0xFFE8E8E8.toInt(),
                btnIconIdle = 0xFF000000.toInt(),
                btnIconActive = 0xFF2563EB.toInt(),
            )
        }
    }

    fun applyBgAlpha(rgb: Int, alphaPercent: Int): Int {
        val a = (alphaPercent.coerceIn(10, 100) * 255 / 100)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 与桌面混色后仍输出不透明 ARGB（RemoteViews 背景不能用真透明）。 */
    fun opaqueWidgetBackgroundColor(rgb: Int, alphaPercent: Int): Int {
        val t = alphaPercent.coerceIn(10, 100) / 100f
        val base = rgb and 0x00FFFFFF
        val r = ((base shr 16) and 0xFF) * t
        val g = ((base shr 8) and 0xFF) * t
        val b = (base and 0xFF) * t
        return 0xFF000000.toInt() or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    fun opaqueWidgetBackgroundColor(theme: WidgetTheme, alphaPercent: Int): Int =
        opaqueWidgetBackgroundColor(theme.bgGradientBottom, alphaPercent)

    fun applyWidgetBackground(
        views: RemoteViews,
        theme: WidgetTheme,
        alphaPercent: Int,
        cornerRadiusPx: Float,
        widthPx: Int,
        heightPx: Int,
    ) {
        views.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT)
        views.setViewVisibility(R.id.widget_bg, android.view.View.VISIBLE)
        createWidgetBackground(widthPx, heightPx, theme, alphaPercent, cornerRadiusPx)
            ?.let { views.setImageViewBitmap(R.id.widget_bg, it) }
    }

    /** res/drawable 下部分图标为占位矢量，与 assets/car PNG 不一致；小组件按钮必须用 assets+着色。 */
    private fun widgetButtonDrawableFallbackRes(
        displayText: String,
        vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
    ): Int {
        return when (iconAssetName(displayText, vehicleStatus)) {
            "ic_car_index_lock" -> R.drawable.ic_car_index_lock
            "ic_car_index_lock_on" -> R.drawable.ic_car_index_lock_on
            "ic_car_index_find_car" -> R.drawable.ic_car_index_find_car
            "ic_car_index_engine" -> R.drawable.ic_car_index_engine
            "ic_car_index_engine_on" -> R.drawable.ic_car_index_engine_on
            "ic_car_index_open_window" -> R.drawable.ic_car_index_open_window
            "ic_car_index_open_window_on" -> R.drawable.ic_car_index_open_window_on
            "ic_car_index_wind" -> R.drawable.ic_car_index_wind
            "ic_car_index_wind_on" -> R.drawable.ic_car_index_wind_on
            "ic_car_index_trunk" -> R.drawable.ic_car_index_trunk
            "ic_car_index_trunk_on" -> R.drawable.ic_car_index_trunk_on
            else -> 0
        }
    }

    // 与背屏一致：assets/car 下 PNG + SRC_IN 着色；RemoteViews 位图数量有限，应先绑定按钮。
    fun loadWidgetButtonIcon(
        context: Context,
        displayText: String,
        vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
        tintColor: Int,
        sizePx: Int,
    ): Bitmap? {
        val assetName = iconAssetName(displayText, vehicleStatus)
        val fromAsset = CarControlAssets.decodeBitmap(context, CarControlAssets.pngPath(assetName))
        if (fromAsset != null) {
            return tintIconForWidget(fromAsset, tintColor, sizePx)
        }
        val resId = widgetButtonDrawableFallbackRes(displayText, vehicleStatus)
        if (resId != 0) {
            return loadTintedVectorIcon(context, resId, tintColor, sizePx)
        }
        return null
    }

    fun bindWidgetButtonIcon(
        views: RemoteViews,
        viewId: Int,
        context: Context,
        displayText: String,
        vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
        tintColor: Int,
        sizePx: Int,
    ): Boolean {
        val bitmap = loadWidgetButtonIcon(context, displayText, vehicleStatus, tintColor, sizePx)
        if (bitmap == null) return false
        bindWidgetBitmap(views, viewId, bitmap)
        return true
    }

    /** 车模、按钮等小图标直接绑定，不走共享预算（预算过小会导致后两个按钮丢失）。 */
    fun bindWidgetBitmap(views: RemoteViews, viewId: Int, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(viewId, bitmap)
        }
    }

    fun bindCarModelBitmap(views: RemoteViews, bitmap: Bitmap?) {
        bindWidgetBitmap(views, R.id.widget_car_model, bitmap)
    }

    /** 车模目标输出尺寸（尽量与显示区域一致）。 */
    fun carModelOutputSize(maxWidthPx: Int, maxHeightPx: Int): Pair<Int, Int> {
        var w = (maxWidthPx * WIDGET_CAR_OUTPUT_OVERSAMPLE).toInt().coerceAtLeast(1)
        var h = (maxHeightPx * WIDGET_CAR_OUTPUT_OVERSAMPLE).toInt().coerceAtLeast(1)
        val longSide = maxOf(w, h)
        if (longSide > MAX_WIDGET_CAR_LONG_SIDE_PX) {
            val s = MAX_WIDGET_CAR_LONG_SIDE_PX.toFloat() / longSide
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        val area = w * h
        if (area > MAX_WIDGET_CAR_PIXELS) {
            val s = sqrt(MAX_WIDGET_CAR_PIXELS.toFloat() / area)
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        return w to h
    }

    /** 按小组件尺寸生成背景位图宽高（保持比例，限制最大像素以免 Binder 过大）。 */
    private fun widgetBackgroundBitmapSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        var w = widthPx.coerceIn(1, MAX_WIDGET_BG_BITMAP_W)
        var h = (w * heightPx.toFloat() / widthPx.coerceAtLeast(1)).toInt().coerceIn(1, MAX_WIDGET_BG_BITMAP_H)
        val area = w * h
        if (area > MAX_WIDGET_BG_PIXELS) {
            val scale = sqrt(MAX_WIDGET_BG_PIXELS.toFloat() / area)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }
        return w to h
    }

    /** 小组件背景：ARGB 透明底 + 配置透明度/圆角；圆角半径随位图同比例缩放。 */
    fun createWidgetBackground(
        widthPx: Int,
        heightPx: Int,
        theme: WidgetTheme,
        alphaPercent: Int,
        cornerRadiusPx: Float,
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val (w, h) = widgetBackgroundBitmapSize(widthPx, heightPx)
        val bitmapScale = w.toFloat() / widthPx.toFloat()
        val cornerOnBitmap = (cornerRadiusPx * bitmapScale)
            .coerceIn(0f, minOf(w, h) / 2f)
        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val top = applyBgAlpha(theme.bgGradientTop, alphaPercent)
        val bottom = applyBgAlpha(theme.bgGradientBottom, alphaPercent)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = false
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                top, bottom,
                Shader.TileMode.CLAMP,
            )
        }
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        if (cornerOnBitmap <= 0f) {
            canvas.drawRect(rect, paint)
        } else {
            val clip = Path().apply {
                addRoundRect(rect, cornerOnBitmap, cornerOnBitmap, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rect, paint)
            canvas.restore()
        }
        return out
    }

    fun createRoundedBackground(
        widthPx: Int,
        heightPx: Int,
        colorArgb: Int,
        cornerRadiusPx: Float,
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val w = widthPx.coerceAtMost(MAX_WIDGET_BITMAP_W)
        val h = heightPx.coerceAtMost(MAX_WIDGET_BITMAP_H)
        val out = createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        return out
    }

    /** 确认层磨砂遮罩（半透明叠层模拟模糊）。 */
    fun createFrostedScrim(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        baseArgb: Int = 0xB3000000.toInt(),
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val w = widthPx.coerceAtMost(MAX_WIDGET_BITMAP_W)
        val h = heightPx.coerceAtMost(MAX_WIDGET_BITMAP_H)
        val out = createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseArgb }
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        paint.color = 0x22FFFFFF
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        return out
    }

    fun widgetSizePx(context: Context, widthDp: Int, heightDp: Int): Pair<Int, Int> {
        val d = context.resources.displayMetrics.density
        val w = (widthDp * d).toInt().coerceAtLeast(1)
        val h = (heightDp * d).toInt().coerceAtLeast(1)
        return w to h
    }

    /** 读取当前小组件尺寸（缩放手势会更新 MIN/MAX，取平均更接近实际显示区域）。 */
    fun readWidgetSizeDp(
        options: Bundle?,
        defaultWidthDp: Int,
        defaultHeightDp: Int,
    ): Pair<Int, Int> {
        if (options == null) return defaultWidthDp to defaultHeightDp
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaultWidthDp)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defaultHeightDp)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH)
        val w = ((minW + maxW) / 2).coerceAtLeast(minW)
        val h = ((minH + maxH) / 2).coerceAtLeast(minH)
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    fun layoutMetrics(
        context: Context,
        widthDp: Int,
        heightDp: Int,
        cornerRadiusDp: Float = 16f,
    ): WidgetLayoutMetrics {
        val density = context.resources.displayMetrics.density
        val (widthPx, heightPx) = widgetSizePx(context, widthDp, heightDp)
        val wScale = (widthDp / BASE_WIDTH_DP).coerceIn(0.75f, 2.2f)
        val hScale = (heightDp / BASE_HEIGHT_DP).coerceIn(0.75f, 2.2f)
        val scale = minOf(wScale, hScale)

        val refreshDp = (16f * scale).coerceIn(14f, 22f)
        val fuelIconDp = (16f * scale).coerceIn(12f, 22f)
        val buttonRowDp = (40f * scale).coerceIn(34f, 56f)
        val metaBarDp = (22f * scale).coerceIn(18f, 30f)
        val locationRowDp = (24f * scale).coerceIn(18f, 32f)
        val rootPadPx = (4f * 2f * density).toInt()
        val contentPadPx = (8f * 2f * density).toInt()
        val buttonRowPx = (buttonRowDp * density).toInt()
        val metaBarPx = (metaBarDp * density).toInt()
        val locationRowPx = (locationRowDp * density).toInt()
        val topRowPx = (heightPx - rootPadPx - contentPadPx - buttonRowPx - (4f * density).toInt())
            .coerceAtLeast((48f * density).toInt())
        val carColumnChromePx = metaBarPx + locationRowPx + (6f * density).toInt()
        val carMaxHeightPx = (topRowPx - carColumnChromePx).coerceAtLeast((48f * density).toInt())
        val carMaxWidthPx = ((widthPx - rootPadPx - contentPadPx) * WIDGET_CAR_COLUMN_WEIGHT - (6f * density))
            .toInt()
            .coerceAtLeast((96f * density).toInt())

        return WidgetLayoutMetrics(
            widthPx = widthPx,
            heightPx = heightPx,
            iconPx = widgetButtonIconPx(density),
            refreshIconPx = (refreshDp * density).toInt().coerceAtLeast(1),
            fuelIconPx = (fuelIconDp * density).toInt().coerceAtLeast(1),
            carMaxWidthPx = carMaxWidthPx,
            carMaxHeightPx = carMaxHeightPx,
            rangeMainSp = (22f * scale).coerceIn(18f, 28f),
            rangeLabelSp = (10f * scale).coerceIn(9f, 12f),
            fuelPercentSp = (12f * scale).coerceIn(10f, 16f),
            timeTextSp = (12f * scale).coerceIn(10f, 15f),
            infoTextSp = (10f * scale).coerceIn(8f, 13f),
            locationTextSp = (9f * scale).coerceIn(8f, 12f),
            cornerRadiusPx = cornerRadiusDp.coerceAtLeast(0f) * density,
        )
    }

    fun applyTextSizeSp(views: RemoteViews, viewId: Int, sizeSp: Float) {
        views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /** 按钮图标输出边长：36dp 布局 × 密度 × 超采样，不压到低分辨率。 */
    fun widgetButtonIconPx(density: Float): Int {
        val px = (WIDGET_BTN_ICON_LAYOUT_DP * density * WIDGET_BTN_ICON_OVERSAMPLE).toInt()
        return px.coerceIn(1, MAX_WIDGET_BTN_ICON_PX)
    }

    /** 小组件续航行：大号数字 + 小号 km；无有效数值时仅显示占位符。 */
    fun parseRangeWidgetParts(rangeKmText: String, dash: String): Pair<String, String> {
        if (rangeKmText.isBlank() || rangeKmText == dash) return dash to ""
        val num = rangeKmText.replace(Regex("[^0-9]"), "")
        if (num.isEmpty()) return dash to ""
        return num to "km"
    }

    fun iconAssetName(displayText: String, vehicleStatus: VehicleStatusService.VehicleStatusInfo?): String =
        when (displayText) {
            "解锁" -> "ic_car_index_lock"
            "锁车" -> "ic_car_index_lock_on"
            "寻车" -> "ic_car_index_find_car"
            "点火" -> "ic_car_index_engine"
            "熄火" -> "ic_car_index_engine_on"
            "打开空调" -> "ic_ac_unit"
            "关闭空调" -> "ic_ac_unit_on"
            "开窗" -> "ic_car_index_open_window"
            "关窗" -> "ic_car_index_open_window_on"
            "透气" -> {
                val pos = vehicleStatus?.winPosDriver
                val vent = pos != null && pos != "未知" && try {
                    val v = pos.toInt()
                    v > 0 && v <= 50
                } catch (_: Exception) {
                    false
                }
                if (vent) "ic_car_index_wind_on" else "ic_car_index_wind"
            }
            "开后备箱" -> "ic_car_index_trunk"
            "关后备箱" -> "ic_car_index_trunk_on"
            "打开座椅加热" -> "ic_seat_heating"
            "关闭座椅加热" -> "ic_seat_heating_on"
            "主驾加热" -> "ic_seat_heating_driver"
            "关闭主驾加热" -> "ic_seat_heating_driver_on"
            "副驾加热" -> "ic_seat_heating_passenger"
            "关闭副驾加热" -> "ic_seat_heating_passenger_on"
            else -> "ic_car_index_find_car"
        }

    fun loadFuelIcon(context: Context, sizePx: Int): Bitmap? {
        val raw = CarControlAssets.decodeBitmap(context, CarControlAssets.pngPath("you")) ?: return null
        return scaleBitmap(raw, sizePx, sizePx)
    }

    internal fun loadTintedVectorIcon(context: Context, drawableId: Int, tintColor: Int, sizePx: Int): Bitmap? {
        val drawable = AppCompatResources.getDrawable(context, drawableId) ?: return null
        val raw = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(raw)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return tintIconForWidget(raw, tintColor, sizePx)
    }

    fun loadRefreshIcon(context: Context, tintColor: Int, sizePx: Int): Bitmap? =
        loadTintedVectorIcon(context, R.drawable.ic_car_control_refresh, tintColor, sizePx)

    fun loadButtonIcon(
        context: Context,
        displayText: String,
        vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
        tintColor: Int,
        sizePx: Int,
    ): Bitmap? {
        val name = iconAssetName(displayText, vehicleStatus)
        val raw = CarControlAssets.decodeBitmap(context, CarControlAssets.pngPath(name)) ?: return null
        return tintIconForWidget(raw, tintColor, sizePx)
    }

    /** 小组件车模：完整等比缩放到显示区域，不裁切，ARGB + 双线性滤波。 */
    fun scaleCarModelForWidget(source: Bitmap, maxWidthPx: Int, maxHeightPx: Int): Bitmap =
        scaleCarModelArgb(source, maxWidthPx, maxHeightPx)

    /** 统计本批小组件所需车模解码边长（取各实例车图区域较长边最大值）。 */
    fun maxCarModelDecodeSidePx(
        context: Context,
        appWidgetIds: IntArray,
        optionsForId: (Int) -> Bundle?,
        defaultWidthDp: Int,
        defaultHeightDp: Int,
    ): Int {
        var maxSide = 160
        for (widgetId in appWidgetIds) {
            val (widthDp, heightDp) = readWidgetSizeDp(
                optionsForId(widgetId),
                defaultWidthDp,
                defaultHeightDp,
            )
            val cornerDp = CarControlWidgetPrefs.cornerRadiusDp(context, widgetId).toFloat()
            val m = layoutMetrics(context, widthDp, heightDp, cornerDp)
            val (outW, outH) = carModelOutputSize(m.carMaxWidthPx, m.carMaxHeightPx)
            val decodeSide = (maxOf(outW, outH) * WIDGET_CAR_DECODE_OVERSAMPLE).toInt()
            maxSide = maxOf(maxSide, decodeSide)
        }
        return maxSide
    }

    private fun scaleCarModelArgb(source: Bitmap, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
        val (capW, capH) = carModelOutputSize(maxWidthPx, maxHeightPx)
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()
        if (sw <= 0f || sh <= 0f) return source
        val scale = minOf(capW / sw, capH / sh, 1f)
        if (scale <= 0f) return source
        val newW = (sw * scale).toInt().coerceAtLeast(1)
        val newH = (sh * scale).toInt().coerceAtLeast(1)
        if (newW == source.width && newH == source.height) return source
        val out = createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dst = RectF(0f, 0f, newW.toFloat(), newH.toFloat())
        canvas.drawBitmap(source, null, dst, paint)
        return out
    }

    private fun scaleBitmap(source: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
        if (source.width == widthPx && source.height == heightPx) return source
        return source.scale(widthPx, heightPx)
    }

    /**
     * 按钮图标：ARGB 正方画布、等比居中、双线性缩小，避免强行拉成方块导致发糊。
     */
    internal fun tintIconForWidget(source: Bitmap, tintColor: Int, boxPx: Int): Bitmap {
        if (boxPx <= 0) return source
        val out = createBitmap(boxPx, boxPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        }
        val sw = source.width.toFloat().coerceAtLeast(1f)
        val sh = source.height.toFloat().coerceAtLeast(1f)
        val fit = minOf(boxPx / sw, boxPx / sh)
        val dw = sw * fit
        val dh = sh * fit
        val dst = RectF(
            (boxPx - dw) / 2f,
            (boxPx - dh) / 2f,
            (boxPx + dw) / 2f,
            (boxPx + dh) / 2f,
        )
        canvas.drawBitmap(source, null, dst, paint)
        return out
    }
}
