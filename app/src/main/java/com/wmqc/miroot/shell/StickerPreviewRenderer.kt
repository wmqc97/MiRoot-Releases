package com.wmqc.miroot.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 弹窗比例预览用的贴图参数（有贴图文件即绘制；W/H 均为 0 表示原始尺寸）。
 */
data class StickerPreviewOverlay(
    val drawSticker: Boolean,
    val rounded: Boolean,
    val x: Int,
    val y: Int,
    val targetW: Int,
    val targetH: Int,
    /** W、H 均大于 0 时按此模式填入槽位；否则忽略。 */
    val scaleMode: StickerScaleMode,
    /** 仅影响贴图水平位置；与带壳画面叠放坐标（compositeXY）无关。 */
    val centerStickerHorizontal: Boolean,
    /** 垂直居中：与 [ShellStickerOverlay.stickerVerticalCenterTopPx] 一致（含 +[ShellStickerOverlay.STICKER_VERTICAL_CENTER_EXTRA_OFFSET_PX]）。 */
    val centerStickerVertical: Boolean,
    /** 圆角半径 (dp)，与 [DeviceGeometry.stickerCornerRadiusDp] 一致；仅 rounded 为 true 时使用。 */
    val cornerRadiusDp: Int,
)

/**
 * 功能页贴图弹窗：按成图画布比例缩小，仅底图 + 贴图（不含背屏截图内容）。
 *
 * 贴图 X/Y、W/H 与合成一致：坐标系为 **底图 PNG 完整像素**（与 [ShellScreenshotComposite] 中 phone 位图相同）。
 * 预览时对底图做采样/缩放仅为了显示，映射必须用 `fullW/fullH`（文件 intrinsic），不能用采样后的 `phone.width`。
 *
 * @param grayscaleBackdrop 为 true 时底图以灰度绘制；弹窗比例预览传 false，底图保持彩色。
 * @param maxSide 预览长边像素上限；**≤0 表示按底图完整像素解码与绘制（不对底图做缩小采样/缩放压缩）**，由界面 ImageView 负责缩小显示。
 */
object StickerPreviewRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 与弹窗比例预览一致，但按底图文件完整像素输出（底图 + 贴图示意，无背屏截图），用于导出无损 PNG。
     */
    fun renderFullPixelForExport(
        context: Context,
        overlay: StickerPreviewOverlay,
    ): Bitmap? {
        val path = DeviceGeometry.resolvePhoneBackPath(context) ?: return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val fullW = boundsOpts.outWidth
        val fullH = boundsOpts.outHeight
        if (fullW <= 0 || fullH <= 0) return null

        val phone = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return try {
            val pw = phone.width
            val ph = phone.height
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(phone, 0f, 0f, paint)
            drawStickerOverlayOnCanvas(context, canvas, pw, ph, fullW, fullH, overlay)
            out
        } finally {
            phone.recycle()
        }
    }

    fun render(
        context: Context,
        overlay: StickerPreviewOverlay,
        maxSide: Int = 0,
        grayscaleBackdrop: Boolean = true,
    ): Bitmap? {
        val path = DeviceGeometry.resolvePhoneBackPath(context) ?: return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val fullW = boundsOpts.outWidth
        val fullH = boundsOpts.outHeight
        if (fullW <= 0 || fullH <= 0) return null

        val maxSideCap = if (maxSide <= 0) max(fullW, fullH) else maxSide

        val decodeOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSizeForMax(fullW, fullH, maxSideCap * 2)
        }
        val phone = BitmapFactory.decodeFile(path, decodeOpts) ?: return null
        return try {
            val cw = phone.width
            val ch = phone.height
            val fitScale = min(maxSideCap.toFloat() / cw, maxSideCap.toFloat() / ch).coerceAtMost(1f)
            val pw = (cw * fitScale).roundToInt().coerceAtLeast(1)
            val ph = (ch * fitScale).roundToInt().coerceAtLeast(1)
            val scaledPhone =
                if (pw == cw && ph == ch) {
                    phone
                } else {
                    Bitmap.createScaledBitmap(phone, pw, ph, true)
                }
            val needRecycleScaled = scaledPhone !== phone
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            if (grayscaleBackdrop) {
                val oldFilter = paint.colorFilter
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(scaledPhone, 0f, 0f, paint)
                paint.colorFilter = oldFilter
            } else {
                canvas.drawBitmap(scaledPhone, 0f, 0f, paint)
            }
            if (needRecycleScaled) scaledPhone.recycle()

            drawStickerOverlayOnCanvas(context, canvas, pw, ph, fullW, fullH, overlay)
            out
        } finally {
            phone.recycle()
        }
    }

    private fun drawStickerOverlayOnCanvas(
        context: Context,
        canvas: Canvas,
        pw: Int,
        ph: Int,
        fullW: Int,
        fullH: Int,
        overlay: StickerPreviewOverlay,
    ) {
        if (!overlay.drawSticker) return
        val stickerFile = DeviceGeometry.stickerOverlayFile(context)
        if (!stickerFile.isFile || stickerFile.length() <= 0L) return

        val sx = pw.toFloat() / fullW
        val sy = ph.toFloat() / fullH

        val sticker = BitmapFactory.decodeFile(
            stickerFile.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return
        try {
            val hasTarget = overlay.targetW > 0 && overlay.targetH > 0
            val px0 = overlay.x * sx
            val py0 = overlay.y * sy
            val density = context.resources.displayMetrics.density
            val radiusPx =
                overlay.cornerRadiusDp.coerceAtLeast(0) * density * min(sx, sy)
            if (hasTarget) {
                val sw = (overlay.targetW * sx).roundToInt().coerceAtLeast(1)
                val sh = (overlay.targetH * sy).roundToInt().coerceAtLeast(1)
                val r = StickerScaleHelper.scaleToSlot(
                    sticker,
                    sw,
                    sh,
                    overlay.scaleMode,
                )
                val toDraw = r.bitmap
                val off = ShellStickerOverlay.STICKER_HORIZONTAL_CENTER_OFFSET_PX
                val left = when {
                    overlay.centerStickerHorizontal &&
                        overlay.scaleMode == StickerScaleMode.FIT ->
                        max(0, pw - toDraw.width) / 2f + off
                    overlay.centerStickerHorizontal ->
                        max(0, pw - sw) / 2f + off
                    else -> px0 + r.offsetXInSlot
                }
                val top = when {
                    overlay.centerStickerVertical ->
                        ShellStickerOverlay.stickerVerticalCenterTopPx(ph, toDraw.height)
                    overlay.scaleMode == StickerScaleMode.FIT ->
                        py0 + r.offsetYInSlot
                    else -> py0
                }
                ShellStickerOverlay.drawStickerBitmapOnCanvas(
                    canvas,
                    toDraw,
                    left,
                    top,
                    overlay.rounded,
                    radiusPx,
                    paint,
                )
                if (r.isNewBitmap) {
                    toDraw.recycle()
                }
            } else {
                val off = ShellStickerOverlay.STICKER_HORIZONTAL_CENTER_OFFSET_PX
                val left = if (overlay.centerStickerHorizontal) {
                    max(0, pw - sticker.width) / 2f + off
                } else {
                    px0
                }
                val settingHPreview =
                    (sticker.height * sy).roundToInt().coerceAtLeast(1)
                val topNoSlot = if (overlay.centerStickerVertical) {
                    ShellStickerOverlay.stickerVerticalCenterTopPx(ph, settingHPreview)
                } else {
                    py0
                }
                ShellStickerOverlay.drawStickerBitmapOnCanvas(
                    canvas,
                    sticker,
                    left,
                    topNoSlot,
                    overlay.rounded,
                    radiusPx,
                    paint,
                )
            }
        } finally {
            if (!sticker.isRecycled) {
                sticker.recycle()
            }
        }
    }

    private fun sampleSizeForMax(w: Int, h: Int, maxSide: Int): Int {
        if (maxSide <= 0) return 1
        var sample = 1
        while (w / sample > maxSide || h / sample > maxSide) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
