package com.wmqc.miroot.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 贴图在目标矩形 (tw×th) 内的缩放策略；W/H 均为 0 时不使用。
 */
enum class StickerScaleMode {
    /** 独立缩放到 tw×th，可能变形。 */
    STRETCH,

    /** 等比缩放以完整放入矩形，在矩形内居中。 */
    FIT,

    /** 等比放大后居中裁剪为 tw×th。 */
    CROP,
}

/**
 * [bitmap] 为绘制用图；相对槽位左上角的偏移（槽位尺寸为 tw×th）。
 * [isNewBitmap] 为 false 时表示未缩放，[bitmap] 即调用方传入的原始引用，回收时勿重复 recycle。
 */
data class StickerScaledResult(
    val bitmap: Bitmap,
    val offsetXInSlot: Float,
    val offsetYInSlot: Float,
    val isNewBitmap: Boolean,
)

object StickerScaleHelper {

    fun fromPreferenceValue(raw: String?): StickerScaleMode {
        return when (raw?.lowercase()) {
            "fit" -> StickerScaleMode.FIT
            "crop" -> StickerScaleMode.CROP
            else -> StickerScaleMode.STRETCH
        }
    }

    fun toPreferenceValue(mode: StickerScaleMode): String =
        when (mode) {
            StickerScaleMode.STRETCH -> "stretch"
            StickerScaleMode.FIT -> "fit"
            StickerScaleMode.CROP -> "crop"
        }

    /** 不创建 Bitmap；与 [scaleToSlot] 一致的绘制尺寸（槽位内最终贴图宽高）。 */
    fun drawSizeInSlot(origW: Int, origH: Int, tw: Int, th: Int, mode: StickerScaleMode): Pair<Int, Int> {
        if (tw <= 0 || th <= 0 || origW <= 0 || origH <= 0) {
            return origW.coerceAtLeast(1) to origH.coerceAtLeast(1)
        }
        return when (mode) {
            StickerScaleMode.STRETCH, StickerScaleMode.CROP -> tw to th
            StickerScaleMode.FIT -> {
                val scale = min(tw / origW.toFloat(), th / origH.toFloat())
                val nw = max(1, (origW * scale).roundToInt())
                val nh = max(1, (origH * scale).roundToInt())
                nw to nh
            }
        }
    }

    /**
     * @param orig 解码后的贴图；调用方负责 recycle [orig]（本方法会生成新 Bitmap）。
     */
    fun scaleToSlot(orig: Bitmap, tw: Int, th: Int, mode: StickerScaleMode): StickerScaledResult {
        val ow = orig.width
        val oh = orig.height
        if (tw <= 0 || th <= 0 || ow <= 0 || oh <= 0) {
            return StickerScaledResult(orig, 0f, 0f, false)
        }
        return when (mode) {
            StickerScaleMode.STRETCH -> {
                val out = Bitmap.createScaledBitmap(orig, tw, th, true)
                StickerScaledResult(out, 0f, 0f, true)
            }
            StickerScaleMode.FIT -> {
                val scale = min(tw / ow.toFloat(), th / oh.toFloat())
                val nw = max(1, (ow * scale).roundToInt())
                val nh = max(1, (oh * scale).roundToInt())
                val out = Bitmap.createScaledBitmap(orig, nw, nh, true)
                val ox = (tw - nw) / 2f
                val oy = (th - nh) / 2f
                StickerScaledResult(out, ox, oy, true)
            }
            StickerScaleMode.CROP -> {
                val out = cropCenterCover(orig, tw, th)
                StickerScaledResult(out, 0f, 0f, true)
            }
        }
    }

    private fun cropCenterCover(orig: Bitmap, tw: Int, th: Int): Bitmap {
        val ow = orig.width
        val oh = orig.height
        val scale = max(tw / ow.toFloat(), th / oh.toFloat())
        val sw = max(tw, (ow * scale).roundToInt())
        val sh = max(th, (oh * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(orig, sw, sh, true)
        val srcX = (sw - tw) / 2
        val srcY = (sh - th) / 2
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val src = Rect(srcX, srcY, srcX + tw, srcY + th)
        val dst = Rect(0, 0, tw, th)
        c.drawBitmap(scaled, src, dst, null)
        scaled.recycle()
        return out
    }
}
