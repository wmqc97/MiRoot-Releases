package com.wmqc.miroot.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 带壳合成最顶层：顺序为 背屏截图 → 外框底图 → **贴图**（本类）。
 * 背屏画面叠放位置仍由 [DeviceGeometry.compositeXY] 决定；本类仅处理贴图层的 X/Y（含可选水平/垂直居中）。
 */
object ShellStickerOverlay {

    /** 开启「贴图水平居中」时，在居中算出的 X 上叠加的像素偏移（截图/录屏与预览一致）。 */
    const val STICKER_HORIZONTAL_CENTER_OFFSET_PX = 4f

    /**
     * 「垂直居中」时：顶边算式见 [stickerVerticalCenterTopPx]；结果为负时从该 Y 起绘制。
     */
    const val STICKER_VERTICAL_CENTER_REGION_TOP_PX = 905

    /** 垂直居中算出的顶边再叠加的向下偏移（px），与真机壳图视觉对齐。 */
    const val STICKER_VERTICAL_CENTER_EXTRA_OFFSET_PX = 75f

    /**
     * 带壳贴图垂直居中：顶边 Y = 底图高度 − [STICKER_VERTICAL_CENTER_REGION_TOP_PX] − 贴图高度/2 + [STICKER_VERTICAL_CENTER_EXTRA_OFFSET_PX]；
     * 若未加偏移前为负，则从 [STICKER_VERTICAL_CENTER_REGION_TOP_PX] 起显示（不加额外偏移）。
     *
     * @param stickerHeightPx 参与居中计算的贴图高度（与画布同坐标系）：应为 **最终绘制到画布上的像素高度**。
     * 槽位内缩放（尤其 [StickerScaleMode.FIT]）后若与设置 H 不一致，必须用绘制高度而非设置 H，否则视觉中心会偏上约 (设置H−绘制H)/2。
     */
    @JvmStatic
    fun stickerVerticalCenterTopPx(canvasHeight: Int, stickerHeightPx: Int): Float {
        val anchor = STICKER_VERTICAL_CENTER_REGION_TOP_PX
        val raw = canvasHeight - anchor - stickerHeightPx / 2f
        return if (raw < 0f) {
            anchor.toFloat()
        } else {
            raw + STICKER_VERTICAL_CENTER_EXTRA_OFFSET_PX
        }
    }

    @JvmStatic
    fun drawOnCanvasIfEnabled(canvas: Canvas, context: Context, paint: Paint): Boolean {
        if (!DeviceGeometry.isStickerEnabled(context)) return false
        if (!DeviceGeometry.isScreenshotShellEnabled(context)) return false
        val file = DeviceGeometry.stickerOverlayFile(context)
        if (!file.isFile || file.length() == 0L) return false
        val orig = decodeStickerArgb8888(file) ?: return false
        val plan = prepareStickerDrawPlan(context, canvas.width, canvas.height, orig)
        return try {
            val radiusPx = stickerCornerRadiusPx(context)
            drawStickerBitmapOnCanvas(
                canvas,
                plan.bitmap,
                plan.left,
                plan.top,
                DeviceGeometry.isStickerRounded(context),
                radiusPx,
                paint,
            )
            true
        } finally {
            recycleAfterDraw(plan.bitmap, orig, plan.scaledFromOrig)
        }
    }

    private fun stickerCornerRadiusPx(context: Context): Float =
        context.resources.displayMetrics.density * DeviceGeometry.stickerCornerRadiusDp(context)

    /**
     * 与底图同尺寸的透明画布，仅含贴图（带壳录屏最后一层）。仅当开启带壳录屏时创建。
     */
    fun createFullCanvasStickerBitmap(context: Context, canvasW: Int, canvasH: Int): Bitmap? {
        if (!DeviceGeometry.isRecordStickerCompositeEnabled(context)) return null
        if (!DeviceGeometry.isStickerEnabled(context)) return null
        if (!DeviceGeometry.isRecordShellCompositeEnabled(context)) return null
        val file = DeviceGeometry.stickerOverlayFile(context)
        if (!file.isFile || file.length() == 0L) return null
        val orig = decodeStickerArgb8888(file) ?: return null
        val plan = prepareStickerDrawPlan(context, canvasW, canvasH, orig)
        val layer = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        layer.eraseColor(0)
        return try {
            val c = Canvas(layer)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val radiusPx = stickerCornerRadiusPx(context)
            drawStickerBitmapOnCanvas(
                c,
                plan.bitmap,
                plan.left,
                plan.top,
                DeviceGeometry.isStickerRounded(context),
                radiusPx,
                p,
            )
            layer
        } catch (_: Exception) {
            layer.recycle()
            null
        } finally {
            recycleAfterDraw(plan.bitmap, orig, plan.scaledFromOrig)
        }
    }

    /** 贴图水平位置：开启「水平居中」时按当前画布宽度与贴图绘制宽度居中，否则用贴图设置里的 X。 */
    private fun stickerDrawLeftPx(canvasWidth: Int, context: Context, stickerDrawWidth: Int): Float {
        if (DeviceGeometry.isStickerCenterHorizontal(context)) {
            return max(0, (canvasWidth - stickerDrawWidth) / 2).toFloat() +
                STICKER_HORIZONTAL_CENTER_OFFSET_PX
        }
        return DeviceGeometry.stickerXY(context)[0].toFloat()
    }

    private fun decodeStickerArgb8888(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (bmp.config == Bitmap.Config.ARGB_8888) {
            return bmp
        }
        val copy = try {
            bmp.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Exception) {
            null
        }
        if (!bmp.isRecycled) bmp.recycle()
        return copy
    }

    private data class StickerDrawPlan(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        /** 为 true 时 [bitmap] 与解码得到的原图为不同对象，需同时 recycle。 */
        val scaledFromOrig: Boolean,
    )

    private fun prepareStickerDrawPlan(
        context: Context,
        canvasWidth: Int,
        canvasHeight: Int,
        orig: Bitmap,
    ): StickerDrawPlan {
        val target = DeviceGeometry.stickerTargetSize(context)
        val tw = target[0]
        val th = target[1]
        val xy = DeviceGeometry.stickerXY(context)
        val x0 = xy[0].toFloat()
        val y0 = xy[1].toFloat()
        val centerV = DeviceGeometry.isStickerCenterVertical(context)
        if (tw > 0 && th > 0) {
            val mode = DeviceGeometry.stickerScaleMode(context)
            val r = StickerScaleHelper.scaleToSlot(orig, tw, th, mode)
            val bmp = r.bitmap
            val centerH = DeviceGeometry.isStickerCenterHorizontal(context)
            val left = when {
                centerH && mode == StickerScaleMode.FIT ->
                    max(0, (canvasWidth - bmp.width) / 2).toFloat() +
                        STICKER_HORIZONTAL_CENTER_OFFSET_PX
                centerH -> stickerDrawLeftPx(canvasWidth, context, tw)
                else -> x0 + r.offsetXInSlot
            }
            val top = when {
                // 用实际绘制高度，与 FIT 槽位内 offsetY 一致，避免「设置 H」与 nh 不等时偏上约 (th−nh)/2
                centerV -> stickerVerticalCenterTopPx(canvasHeight, bmp.height)
                mode == StickerScaleMode.FIT -> y0 + r.offsetYInSlot
                else -> y0
            }
            return StickerDrawPlan(bmp, left, top, r.isNewBitmap)
        }
        val left = stickerDrawLeftPx(canvasWidth, context, orig.width)
        val top = if (centerV) {
            stickerVerticalCenterTopPx(canvasHeight, orig.height)
        } else {
            y0
        }
        return StickerDrawPlan(orig, left, top, false)
    }

    private fun recycleAfterDraw(toDraw: Bitmap, orig: Bitmap, scaledFromOrig: Boolean) {
        if (scaledFromOrig) {
            if (!toDraw.isRecycled) toDraw.recycle()
            if (!orig.isRecycled) orig.recycle()
        } else {
            if (!orig.isRecycled) orig.recycle()
        }
    }

    fun drawStickerBitmapOnCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        rounded: Boolean,
        radiusPx: Float,
        referencePaint: Paint,
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (!rounded) {
            canvas.drawBitmap(bitmap, left, top, referencePaint)
            return
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = shader
        val matrix = Matrix()
        matrix.setTranslate(left, top)
        shader.setLocalMatrix(matrix)
        val r = radiusPx.coerceAtMost(min(w, h) / 2f)
        canvas.drawRoundRect(RectF(left, top, left + w, top + h), r, r, p)
    }
}
