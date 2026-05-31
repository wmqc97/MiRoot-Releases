package com.wmqc.miroot.charging



import android.content.Context

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.net.Uri

import java.io.FileOutputStream

import kotlin.math.max



/** 漂浮图片模式：仅加载用户自定义 PNG。 */

object ChargingMascotLoader {



    private const val MAX_EDGE_PX = 512



    @JvmStatic

    fun load(context: Context): Bitmap? {

        val app = context.applicationContext

        if (!ChargingAnimationPrefs.hasCustomMascot(app)) return null

        return decodeSampled(ChargingAnimationPrefs.customMascotFile(app).absolutePath)

    }



    @JvmStatic

    fun copyFromUri(context: Context, uri: Uri): Boolean {

        val app = context.applicationContext

        val outFile = ChargingAnimationPrefs.customMascotFile(app)

        return try {

            outFile.parentFile?.mkdirs()

            app.contentResolver.openInputStream(uri)?.use { input ->

                FileOutputStream(outFile).use { output -> input.copyTo(output) }

            } ?: return false

            outFile.isFile && outFile.length() > 0L

        } catch (_: Exception) {

            false

        }

    }



    @JvmStatic

    fun deleteCustom(context: Context): Boolean {

        val file = ChargingAnimationPrefs.customMascotFile(context.applicationContext)

        return !file.exists() || file.delete()

    }



    private fun decodeSampled(path: String): Bitmap? {

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        BitmapFactory.decodeFile(path, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {

            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)

            inPreferredConfig = Bitmap.Config.ARGB_8888

        }

        return BitmapFactory.decodeFile(path, opts)

    }



    private fun sampleSize(w: Int, h: Int): Int {

        var size = 1

        val maxEdge = max(w, h)

        while (maxEdge / size > MAX_EDGE_PX) {

            size *= 2

        }

        return size

    }

}

