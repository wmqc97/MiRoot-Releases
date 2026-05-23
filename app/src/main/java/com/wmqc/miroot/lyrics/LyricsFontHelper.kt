package com.wmqc.miroot.lyrics

import com.wmqc.miroot.lyrics.LogHelper
import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.util.Locale

/**
 * 背屏歌词字体（普通 / 分词 / 深渊镜共用一套偏好：projectionLyricsFont）。
 * 持久化 id 为 [ID_SYSTEM] / [ID_MFGEHEI] / [ID_CUSTOM]（自定义须配合应用内复制的绝对路径）。
 */
object LyricsFontHelper {
    private const val TAG = "LyricsFontHelper"
    const val ID_SYSTEM = "system"
    const val ID_MFGEHEI = "mfgehei"
    const val ID_CUSTOM = "custom"

    /** 默认 MFGeHei（assets 缺失则回退系统字体）。 */
    const val DEFAULT_ID = ID_MFGEHEI

    @JvmStatic
    fun normalizeFontId(id: String?): String {
        val s = id?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            s.isEmpty() -> DEFAULT_ID
            s == ID_SYSTEM -> ID_SYSTEM
            s == ID_MFGEHEI -> ID_MFGEHEI
            s == ID_CUSTOM -> ID_CUSTOM
            else -> DEFAULT_ID
        }
    }

    @JvmStatic
    fun resolveTypeface(context: Context?, fontId: String): Typeface =
        resolveTypeface(context, fontId, null)

    @JvmStatic
    fun resolveTypeface(context: Context?, fontId: String, customFontPath: String?): Typeface {
        if (context == null) return Typeface.DEFAULT
        val id = normalizeFontId(fontId)
        if (id == ID_SYSTEM) return Typeface.DEFAULT
        if (id == ID_CUSTOM) {
            val p = customFontPath?.trim().orEmpty()
            if (p.isNotEmpty()) {
                val f = File(p)
                if (f.isFile) {
                    return try {
                        Typeface.createFromFile(f)
                    } catch (_: Throwable) {
                        Typeface.DEFAULT
                    }
                }
            }
            return Typeface.DEFAULT
        }
        return loadMfGeHeiTypeface(context) ?: Typeface.DEFAULT
    }

    /**
     * MFGeHei：优先 [res/font/miroot_mfgehei.ttf]（可选打包），再尝试 assets。
     * 标准内置路径为 assets/MFGeHei-Regular.ttf（即 app/src/main/assets/MFGeHei-Regular.ttf），
     * 其后为 `fonts/`、`shell/` 等备选路径（与发版 shell 目录约定一致）。
     */
    private fun loadMfGeHeiTypeface(context: Context): Typeface? {
        try {
            val resId = context.resources.getIdentifier("miroot_mfgehei", "font", context.packageName)
            if (resId != 0) {
                ResourcesCompat.getFont(context, resId)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        val paths = arrayOf(
            "MFGeHei-Regular.ttf",
            "fonts/MFGeHei-Regular.ttf",
            "shell/MFGeHei-Regular.ttf",
            "shell/fonts/MFGeHei-Regular.ttf",
        )
        for (path in paths) {
            try {
                return Typeface.createFromAsset(context.assets, path)
            } catch (_: Throwable) {
            }
        }
        LogHelper.w(
            TAG,
            "MFGeHei not bundled (tried res/font/miroot_mfgehei and assets paths " +
                paths.contentToString() + "); using default typeface. Add app/src/main/assets/MFGeHei-Regular.ttf",
        )
        return null
    }
}
