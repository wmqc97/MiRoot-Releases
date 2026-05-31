package com.wmqc.miroot.charging



import android.content.Context

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.net.Uri

import java.io.FileOutputStream

import kotlin.math.max



/** 充电动画场景背景：无自定义时为纯黑；图片 center-crop；视频由 [ChargingBackgroundVideoPlayer] 播放。 */

object ChargingBackgroundLoader {



    @JvmStatic

    fun load(context: Context, maxEdgePx: Int): Bitmap? {

        val app = context.applicationContext

        if (ChargingAnimationPrefs.hasCustomBackgroundVideo(app)) return null

        if (!ChargingAnimationPrefs.hasCustomBackground(app)) return null

        val target = maxEdgePx.coerceIn(480, 1920)

        return decodeSampledFile(ChargingAnimationPrefs.customBackgroundFile(app).absolutePath, target)

    }



    @JvmStatic

    fun copyMediaFromUri(context: Context, uri: Uri): Boolean {

        return if (copyMediaFromUriIsVideo(context, uri)) {

            copyVideoFromUri(context, uri)

        } else {

            copyFromUri(context, uri)

        }

    }



    /** @return true 表示 URI 为视频 */

    @JvmStatic

    fun copyMediaFromUriIsVideo(context: Context, uri: Uri): Boolean {

        val mime = context.applicationContext.contentResolver.getType(uri)

        return mime != null && mime.startsWith("video/")

    }



    @JvmStatic

    fun copyFromUri(context: Context, uri: Uri): Boolean {

        val app = context.applicationContext

        val outFile = ChargingAnimationPrefs.customBackgroundFile(app)

        return try {

            outFile.parentFile?.mkdirs()

            app.contentResolver.openInputStream(uri)?.use { input ->

                FileOutputStream(outFile).use { output -> input.copyTo(output) }

            } ?: return false

            val ok = outFile.isFile && outFile.length() > 0L

            if (ok) {

                deleteVideoOnly(app)

            }

            ok

        } catch (_: Exception) {

            false

        }

    }



    @JvmStatic

    fun copyVideoFromUri(context: Context, uri: Uri): Boolean {

        val app = context.applicationContext

        val outFile = ChargingAnimationPrefs.customBackgroundVideoFile(app)

        return try {

            outFile.parentFile?.mkdirs()

            app.contentResolver.openInputStream(uri)?.use { input ->

                FileOutputStream(outFile).use { output -> input.copyTo(output) }

            } ?: return false

            val ok = outFile.isFile && outFile.length() > 0L

            if (ok) {

                deleteImageOnly(app)

            }

            ok

        } catch (_: Exception) {

            false

        }

    }



    @JvmStatic

    fun deleteCustom(context: Context): Boolean {

        val app = context.applicationContext

        val imgOk = deleteImageOnly(app)

        val vidOk = deleteVideoOnly(app)

        return imgOk && vidOk

    }



    private fun deleteImageOnly(app: Context): Boolean {

        val file = ChargingAnimationPrefs.customBackgroundFile(app)

        return !file.exists() || file.delete()

    }



    private fun deleteVideoOnly(app: Context): Boolean {

        val file = ChargingAnimationPrefs.customBackgroundVideoFile(app)

        return !file.exists() || file.delete()

    }



    private fun decodeSampledFile(path: String, maxEdgePx: Int): Bitmap? {

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        BitmapFactory.decodeFile(path, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {

            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)

            inPreferredConfig = Bitmap.Config.RGB_565

        }

        return BitmapFactory.decodeFile(path, opts)

    }



    private fun sampleSize(w: Int, h: Int, maxEdgePx: Int): Int {

        var size = 1

        val maxEdge = max(w, h)

        while (maxEdge / size > maxEdgePx) {

            size *= 2

        }

        return size

    }

}

