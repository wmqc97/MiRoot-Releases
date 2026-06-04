package com.wmqc.miroot.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileInputStream

private const val KEY_CAR_MODEL_PATH = "car_model_path"
private const val MAX_BITMAP_SIDE_PX = 1280
private const val ABS_MAX_WIDGET_CAR_DECODE_SIDE_PX = 2048

/**
 * 加载车模图片：自定义路径 → assets → drawable。
 * 带采样缩放，避免大图解码 OOM 导致车控页闪退。
 */
object CarModelImageLoader {

    /**
     * 小组件车模解码：按目标显示边长采样，保留 ARGB 透明通道。
     * 自定义/高清源图会尽量按原图较长边解码（上限 [ABS_MAX_WIDGET_CAR_DECODE_SIDE_PX]），
     * 避免大图被过度采样后再缩小导致发糊。
     *
     * @param targetMaxSidePx 当前实例车模区域较长边（像素，已含超采样）
     */
    fun loadForWidget(context: Context, targetMaxSidePx: Int): Bitmap? {
        val decodeSide = resolveWidgetDecodeMaxSide(context, targetMaxSidePx)
        return loadSampled(context, decodeSide, Bitmap.Config.ARGB_8888)
    }

    private fun resolveWidgetDecodeMaxSide(context: Context, targetMaxSidePx: Int): Int {
        val target = targetMaxSidePx.coerceIn(160, ABS_MAX_WIDGET_CAR_DECODE_SIDE_PX)
        val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val customPath = prefs.getString(KEY_CAR_MODEL_PATH, null)
        if (customPath != null) {
            val f = File(customPath)
            if (f.exists()) {
                readImageBounds(f)?.let { (w, h) ->
                    return widgetDecodeSideForSource(w, h, target)
                }
            }
        }
        val hdPath = CarControlAssets.pngPath("xingruicar")
        if (CarControlAssets.exists(context, hdPath)) {
            readAssetBounds(context, hdPath)?.let { (w, h) ->
                return widgetDecodeSideForSource(w, h, target)
            }
        }
        return target
    }

    /** 源图越大，解码越长边越接近原图（封顶），保证只缩不放大。 */
    private fun widgetDecodeSideForSource(srcW: Int, srcH: Int, targetMaxSidePx: Int): Int {
        val srcLong = maxOf(srcW, srcH)
        if (srcLong <= 0) return targetMaxSidePx
        val desired = maxOf(targetMaxSidePx, minOf(srcLong, ABS_MAX_WIDGET_CAR_DECODE_SIDE_PX))
        return desired.coerceIn(160, ABS_MAX_WIDGET_CAR_DECODE_SIDE_PX)
    }

    private fun readImageBounds(file: File): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readAssetBounds(context: Context, assetPath: String): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) {
            null
        }
    }

    fun load(context: Context): Bitmap? =
        loadSampled(context, MAX_BITMAP_SIDE_PX, Bitmap.Config.ARGB_8888)

    private fun loadSampled(context: Context, maxSidePx: Int, preferredConfig: Bitmap.Config): Bitmap? {
        val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)

        val customPath = prefs.getString(KEY_CAR_MODEL_PATH, null)
        if (customPath != null) {
            val f = File(customPath)
            if (f.exists()) {
                decodeFileSampled(f, maxSidePx, preferredConfig)?.let { return it }
            }
        }

        val webpPath = CarControlAssets.webpPath("xingrui")
        if (CarControlAssets.exists(context, webpPath)) {
            decodeAssetSampled(context, webpPath, maxSidePx, preferredConfig)?.let { return it }
        }

        val hdPath = CarControlAssets.pngPath("xingruicar")
        if (CarControlAssets.exists(context, hdPath)) {
            decodeAssetSampled(context, hdPath, maxSidePx, preferredConfig)?.let { return it }
        }

        val pngPath = CarControlAssets.pngPath("xingrui")
        if (CarControlAssets.exists(context, pngPath)) {
            decodeAssetSampled(context, pngPath, maxSidePx, preferredConfig)?.let { return it }
        }

        val resId = context.resources.getIdentifier("xingrui", "drawable", context.packageName)
        if (resId != 0) {
            return try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = if (maxSidePx <= 480) 2 else 1
                    inPreferredConfig = preferredConfig
                }
                BitmapFactory.decodeResource(context.resources, resId, opts)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun decodeAssetSampled(
        context: Context,
        assetPath: String,
        maxSidePx: Int,
        preferredConfig: Bitmap.Config,
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            decodeBounds(boundsOpts, maxSidePx, preferredConfig) { sample ->
                context.assets.open(assetPath).use {
                    BitmapFactory.decodeStream(it, null, sample)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFileSampled(
        file: File,
        maxSidePx: Int,
        preferredConfig: Bitmap.Config,
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            decodeBounds(boundsOpts, maxSidePx, preferredConfig) { sample ->
                FileInputStream(file).use {
                    BitmapFactory.decodeStream(it, null, sample)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBounds(
        boundsOpts: BitmapFactory.Options,
        maxSidePx: Int,
        preferredConfig: Bitmap.Config,
        decode: (BitmapFactory.Options) -> Bitmap?,
    ): Bitmap? {
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
        val sample = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxSidePx)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = preferredConfig
            inScaled = false
            inDither = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                inPreferQualityOverSpeed = true
            }
        }
        return decode(decodeOpts)
    }

    /**
     * 采样后较长边尽量 >= maxSide，避免先缩小再放大导致马赛克。
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        val longest = maxOf(width, height)
        var inSampleSize = 1
        while (longest / inSampleSize > maxSide) {
            inSampleSize *= 2
        }
        if (inSampleSize > 1 && longest / inSampleSize < maxSide) {
            inSampleSize /= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
