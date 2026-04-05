package com.wmqc.miroot.ui.music

import android.content.Context
import java.io.File
import com.wmqc.miroot.lyrics.LyricsFontHelper
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity

object LyricsSettingsRepository {

    private const val PREFS = "LyricsSettings"

    fun load(context: Context): LyricsUiSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawBase = LyricsUiSettings(
            textSize = p.getFloat("textSize", 78f),
            backgroundTextureSize = p.getFloat("backgroundTextureSize", 1.3f),
            normalLyricsAlpha = p.getInt("normalLyricsAlpha", 30),
            backgroundTextureAlpha = p.getInt("backgroundTextureAlpha", 20),
            wordByWord = p.getBoolean("wordByWord", false),
            marqueeLight = p.getBoolean("marqueeLight", true),
            neonBorder = p.getBoolean("neonBorder", true),
            marqueeLightSize = p.getFloat("marqueeLightSize", 18f),
            gestureControl = p.getBoolean("gestureControl", false),
            backgroundTexture = p.getBoolean("backgroundTexture", false),
            autoProjection = p.getBoolean("autoProjection", false),
            abyssalMirror = p.getBoolean("abyssalMirror", false),
            abyssalGyroSensitivity = p.getFloat("abyssalGyroSensitivity", 1f),
            abyssalMovableRange = p.getFloat("abyssalMovableRange", 2.5f),
        )
        val pFontRaw = LyricsFontHelper.normalizeFontId(p.getString("projectionLyricsFont", null))
        val pPathOk = readCustomFontPath(pFontRaw, p.getString("projectionLyricsCustomPath", null))
        val aFontRaw = LyricsFontHelper.normalizeFontId(p.getString("abyssalLyricsFont", null))
        val aPathOk = readCustomFontPath(aFontRaw, p.getString("abyssalLyricsCustomPath", null))
        val raw = rawBase.copy(
            projectionLyricsFont = if (pFontRaw == LyricsFontHelper.ID_CUSTOM && pPathOk == null) {
                LyricsFontHelper.DEFAULT_ID
            } else {
                pFontRaw
            },
            projectionLyricsCustomPath = if (pFontRaw == LyricsFontHelper.ID_CUSTOM && pPathOk != null) pPathOk else null,
            abyssalLyricsFont = if (aFontRaw == LyricsFontHelper.ID_CUSTOM && aPathOk == null) {
                LyricsFontHelper.DEFAULT_ID
            } else {
                aFontRaw
            },
            abyssalLyricsCustomPath = if (aFontRaw == LyricsFontHelper.ID_CUSTOM && aPathOk != null) aPathOk else null,
        )
        val normalized = raw.normalizeAbyssalMutex()
        if (normalized != raw) {
            save(context, normalized)
        }
        return normalized
    }

    fun save(context: Context, s: LyricsUiSettings) {
        val pNorm = LyricsFontHelper.normalizeFontId(s.projectionLyricsFont)
        val aNorm = LyricsFontHelper.normalizeFontId(s.abyssalLyricsFont)
        val pPath = if (pNorm == LyricsFontHelper.ID_CUSTOM) {
            s.projectionLyricsCustomPath?.trim()?.takeIf { it.isNotEmpty() && File(it).isFile }
        } else {
            null
        }
        val aPath = if (aNorm == LyricsFontHelper.ID_CUSTOM) {
            s.abyssalLyricsCustomPath?.trim()?.takeIf { it.isNotEmpty() && File(it).isFile }
        } else {
            null
        }
        val pFontFinal = if (pNorm == LyricsFontHelper.ID_CUSTOM && pPath == null) {
            LyricsFontHelper.DEFAULT_ID
        } else {
            pNorm
        }
        val aFontFinal = if (aNorm == LyricsFontHelper.ID_CUSTOM && aPath == null) {
            LyricsFontHelper.DEFAULT_ID
        } else {
            aNorm
        }
        val fixed = s.normalizeAbyssalMutex().copy(
            projectionLyricsFont = pFontFinal,
            projectionLyricsCustomPath = if (pFontFinal == LyricsFontHelper.ID_CUSTOM) pPath else null,
            abyssalLyricsFont = aFontFinal,
            abyssalLyricsCustomPath = if (aFontFinal == LyricsFontHelper.ID_CUSTOM) aPath else null,
        )
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putFloat("textSize", fixed.textSize)
            .putFloat("backgroundTextureSize", fixed.backgroundTextureSize)
            .putInt("normalLyricsAlpha", fixed.normalLyricsAlpha)
            .putInt("backgroundTextureAlpha", fixed.backgroundTextureAlpha)
            .putBoolean("wordByWord", fixed.wordByWord)
            .putBoolean("marqueeLight", fixed.marqueeLight)
            .putBoolean("neonBorder", fixed.neonBorder)
            .putFloat("marqueeLightSize", fixed.marqueeLightSize)
            .putBoolean("gestureControl", fixed.gestureControl)
            .putBoolean("backgroundTexture", fixed.backgroundTexture)
            .putBoolean("autoProjection", fixed.autoProjection)
            .putBoolean("abyssalMirror", fixed.abyssalMirror)
            .putFloat("abyssalGyroSensitivity", fixed.abyssalGyroSensitivity)
            .putFloat("abyssalMovableRange", fixed.abyssalMovableRange)
            .putString("projectionLyricsFont", fixed.projectionLyricsFont)
            .putString("projectionLyricsCustomPath", fixed.projectionLyricsCustomPath.orEmpty())
            .putString("abyssalLyricsFont", fixed.abyssalLyricsFont)
            .putString("abyssalLyricsCustomPath", fixed.abyssalLyricsCustomPath.orEmpty())
            .apply()

        MusicAutoProjectionSync.sync(context)
        RearScreenLyricsActivity.getCurrentInstance()?.applySettings()
    }

    private fun readCustomFontPath(fontId: String, stored: String?): String? {
        if (fontId != LyricsFontHelper.ID_CUSTOM) return null
        val p = stored?.trim().orEmpty()
        if (p.isEmpty() || !File(p).isFile) return null
        return p
    }
}
