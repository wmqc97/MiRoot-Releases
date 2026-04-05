package com.wmqc.miroot.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

/**
 * 背屏截图与底图合成（与旧版 [ScreenshotCompositeHelper] / 录屏带壳同一套坐标与缩放逻辑）。
 */
object ShellScreenshotComposite {

    fun composite(context: Context, phoneBackPath: String, screenshotPath: String, outputPath: String): Boolean {
        val phone = BitmapFactory.decodeFile(
            phoneBackPath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return false
        val screenshot = BitmapFactory.decodeFile(
            screenshotPath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: run {
            phone.recycle()
            return false
        }
        var scaled: Bitmap? = null
        var composite: Bitmap? = null
        return try {
            var toDraw: Bitmap = screenshot
            val target = DeviceGeometry.targetScaleSize(context)
            if (DeviceGeometry.isProMaxModel() && target[0] > 0 && target[1] > 0) {
                scaled = Bitmap.createScaledBitmap(screenshot, target[0], target[1], true)
                toDraw = scaled
            }
            composite = Bitmap.createBitmap(phone.width, phone.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val xy = DeviceGeometry.compositeXY(context)
            val dst = Rect(
                xy[0],
                xy[1],
                xy[0] + toDraw.width,
                xy[1] + toDraw.height,
            )
            canvas.drawBitmap(toDraw, null, dst, paint)
            canvas.drawBitmap(phone, 0f, 0f, paint)
            // 贴图为最后一层（盖在壳上）
            ShellStickerOverlay.drawOnCanvasIfEnabled(canvas, context, paint)
            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { os ->
                if (!composite.compress(Bitmap.CompressFormat.PNG, 100, os)) return false
            }
            outFile.isFile && outFile.length() > 0L
        } catch (_: Exception) {
            false
        } finally {
            scaled?.recycle()
            composite?.recycle()
            screenshot.recycle()
            phone.recycle()
        }
    }
}
