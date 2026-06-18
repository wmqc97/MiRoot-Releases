package com.wmqc.miroot.ui.music

import android.content.Context
import java.io.File
import com.wmqc.miroot.lyrics.LyricsFontHelper
import com.wmqc.miroot.lyrics.PrivilegeBackend
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity
object LyricsSettingsRepository {

    private const val PREFS = "LyricsSettings"
    private const val KEY_SHUFFLE_SPLIT_SCALE_VARIANCE = "shuffleSplitScaleVariance"
    private const val KEY_LYRICS_SOURCE_MODE = "lyricsSourceMode"
    private const val COLOR_CHANGE_INTERVAL_MIN_MS = 1000
    private const val COLOR_CHANGE_INTERVAL_MAX_MS = 10_000
    private const val COLOR_CHANGE_INTERVAL_DEFAULT_MS = 5000
    fun load(context: Context): LyricsUiSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawBase = LyricsUiSettings(
            textSize = p.getFloat("textSize", 78f),
            backgroundTextureSize = p.getFloat("backgroundTextureSize", 1.3f),
            normalLyricsAlpha = p.getInt("normalLyricsAlpha", 30),
            backgroundTextureAlpha = p.getInt("backgroundTextureAlpha", 20),
            albumArtBackground = p.getBoolean("albumArtBackground", false),
            albumArtAlphaPercent = p.getInt("albumArtAlphaPercent", 35).coerceIn(0, 100),
            albumArtBlurRadius = p.getFloat("albumArtBlurRadius", 12f).coerceAtLeast(0f),
            wordByWord = p.getBoolean("wordByWord", false),
            charJumpEnabled = p.getBoolean("charJumpEnabled", false),
            charJumpHeightPx = p.getFloat("charJumpHeightPx", 20f),
            shuffleSplitEffect = p.getBoolean("shuffleSplitEffect", false),
            shuffleSplitMulticolor = p.getBoolean("shuffleSplitMulticolor", false),
            shuffleSplitMode = p.getString("shuffleSplitMode", "WORD") ?: "WORD",
            shuffleSplitOnlyCurrentLine = p.getBoolean("shuffleSplitOnlyCurrentLine", true),
            shuffleSplitTiltRatio = p.getFloat("shuffleSplitTiltRatio", 5f),
            shuffleSplitScaleVariance = if (p.contains(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE)) {
                p.getFloat(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE, 0.22f)
            } else {
                adaptiveShuffleSplitScaleVariance(p.getFloat("textSize", 78f))
            },
            powerSavingMode = p.getBoolean("powerSavingMode", false),
            borderPerformanceGuard = p.getBoolean("borderPerformanceGuard", false),
            borderLightweightMode = p.getBoolean("borderLightweightMode", false),
            marqueeLight = p.getBoolean("marqueeLight", true),
            neonDisplayEnabled = p.getBoolean("neonDisplay", p.getBoolean("lyricsNeonGlow", true)),
            neonBorder = p.getBoolean("neonBorder", true),
            marqueeLightSize = p.getFloat("marqueeLightSize", 18f),
            marqueeLightDurationMs = p.getInt("marqueeLightDurationMs", 5000),
            gestureControl = p.getBoolean("gestureControl", false),
            backgroundTexture = p.getBoolean("backgroundTexture", false),
            autoProjection = p.getBoolean("autoProjection", false),
            showTranslation = p.getBoolean("showTranslation", true),
            showTransliteration = p.getBoolean("showTransliteration", true),
            breathingEnabled = p.getBoolean("breathingEnabled", false),
            breathingBpm = p.getInt("breathingBpm", 15),
            breathingScaleVariance = p.getFloat("breathingScaleVariance", 0.10f),
            breathingDisplacementStrength = p.getFloat("breathingDisplacementStrength", 1f),
            colorChangeIntervalMs = p.getInt("colorChangeIntervalMs", COLOR_CHANGE_INTERVAL_DEFAULT_MS)
                .coerceIn(COLOR_CHANGE_INTERVAL_MIN_MS, COLOR_CHANGE_INTERVAL_MAX_MS),
            randomColorSwitchEnabled = p.getBoolean("randomColorSwitchEnabled", true),
            fixedColor = p.getInt("fixedColor", 0xFFFFFFFF.toInt()),
            projectionSyncOffsetMs = p.getInt("projectionSyncOffsetMs", DEFAULT_PROJECTION_SYNC_OFFSET_MS),
            lyricsSourceMode = LyricsSourceMode.fromPrefValue(
                p.getString(KEY_LYRICS_SOURCE_MODE, LyricsSourceMode.NETWORK_ONLY.prefValue),
            ),
            abyssalMirror = p.getBoolean("abyssalMirror", false),
            abyssalGyroSensitivity = p.getFloat("abyssalGyroSensitivity", 1f),
            abyssalMovableRange = p.getFloat("abyssalMovableRange", 2.5f),
        )
        val pFontRaw = LyricsFontHelper.normalizeFontId(p.getString("projectionLyricsFont", null))
        val pPathOk = readCustomFontPath(pFontRaw, p.getString("projectionLyricsCustomPath", null))
        val projFont = if (pFontRaw == LyricsFontHelper.ID_CUSTOM && pPathOk == null) {
            LyricsFontHelper.DEFAULT_ID
        } else {
            pFontRaw
        }
        val projPath = if (pFontRaw == LyricsFontHelper.ID_CUSTOM && pPathOk != null) pPathOk else null

        val aFontRaw = LyricsFontHelper.normalizeFontId(p.getString("abyssalLyricsFont", null))
        val aPathOk = readCustomFontPath(aFontRaw, p.getString("abyssalLyricsCustomPath", null))
        val abyFont = if (aFontRaw == LyricsFontHelper.ID_CUSTOM && aPathOk == null) {
            LyricsFontHelper.DEFAULT_ID
        } else {
            aFontRaw
        }
        val abyPath = if (aFontRaw == LyricsFontHelper.ID_CUSTOM && aPathOk != null) aPathOk else null

        // ͳһΪ��һ���壺Ĭ����Ͷ��Ϊ׼�����ϴ�Ϊ��Ԩ��������ƫ�ò�һ�£�������Ԩ���ࣨ�ɰ����д�� abyssal ����
        val prefsAbyssalOn = p.getBoolean("abyssalMirror", false)
        val pairsDiffer = projFont != abyFont || projPath != abyPath
        val canonFont: String
        val canonPath: String?
        if (prefsAbyssalOn && pairsDiffer) {
            canonFont = abyFont
            canonPath = abyPath
        } else {
            canonFont = projFont
            canonPath = projPath
        }

        val raw = rawBase.copy(
            projectionLyricsFont = canonFont,
            projectionLyricsCustomPath = canonPath,
            abyssalLyricsFont = canonFont,
            abyssalLyricsCustomPath = canonPath,
        )
        val normalized = enforceLyricsSourceMode(context, raw)
            .normalizeAbyssalMutex()
        if (normalized != raw) {
            save(context, normalized)
        }
        return normalized
    }

    fun save(context: Context, s: LyricsUiSettings) {
        val pNorm = LyricsFontHelper.normalizeFontId(s.projectionLyricsFont)
        val pPath = if (pNorm == LyricsFontHelper.ID_CUSTOM) {
            s.projectionLyricsCustomPath?.trim()?.takeIf { it.isNotEmpty() && File(it).isFile }
        } else {
            null
        }
        val pFontFinal = if (pNorm == LyricsFontHelper.ID_CUSTOM && pPath == null) {
            LyricsFontHelper.DEFAULT_ID
        } else {
            pNorm
        }
        val projCustomPath = if (pFontFinal == LyricsFontHelper.ID_CUSTOM) pPath else null
        val fixed = enforceLyricsSourceMode(context, s).normalizeAbyssalMutex().copy(
            projectionLyricsFont = pFontFinal,
            projectionLyricsCustomPath = projCustomPath,
            abyssalLyricsFont = pFontFinal,
            abyssalLyricsCustomPath = projCustomPath,
        )
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putFloat("textSize", fixed.textSize)
            .putFloat("backgroundTextureSize", fixed.backgroundTextureSize)
            .putInt("normalLyricsAlpha", fixed.normalLyricsAlpha)
            .putInt("backgroundTextureAlpha", fixed.backgroundTextureAlpha)
            .putBoolean("albumArtBackground", fixed.albumArtBackground)
            .putInt("albumArtAlphaPercent", fixed.albumArtAlphaPercent.coerceIn(0, 100))
            .putFloat("albumArtBlurRadius", fixed.albumArtBlurRadius.coerceAtLeast(0f))
            .putBoolean("wordByWord", fixed.wordByWord)
            .putBoolean("charJumpEnabled", fixed.charJumpEnabled)
            .putFloat("charJumpHeightPx", fixed.charJumpHeightPx)
            .putBoolean("shuffleSplitEffect", fixed.shuffleSplitEffect)
            .putBoolean("shuffleSplitMulticolor", fixed.shuffleSplitMulticolor)
            .putString("shuffleSplitMode", fixed.shuffleSplitMode)
            .putBoolean("shuffleSplitOnlyCurrentLine", fixed.shuffleSplitOnlyCurrentLine)
            .putFloat("shuffleSplitTiltRatio", fixed.shuffleSplitTiltRatio)
            .putFloat(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE, fixed.shuffleSplitScaleVariance)
            .putBoolean("shuffleSplitPerformanceGuard", false)
            .putBoolean("powerSavingMode", fixed.powerSavingMode)
            .putBoolean("borderPerformanceGuard", fixed.borderPerformanceGuard)
            .putBoolean("borderLightweightMode", fixed.borderLightweightMode)
            .putBoolean("marqueeLight", fixed.marqueeLight)
            .putBoolean("neonDisplay", fixed.neonDisplayEnabled)
            .putBoolean("neonBorder", fixed.neonBorder)
            .putFloat("marqueeLightSize", fixed.marqueeLightSize)
            .putInt("marqueeLightDurationMs", fixed.marqueeLightDurationMs)
            .putBoolean("gestureControl", fixed.gestureControl)
            .putBoolean("backgroundTexture", fixed.backgroundTexture)
            .putBoolean("autoProjection", fixed.autoProjection)
            .putBoolean("showTranslation", fixed.showTranslation)
            .putBoolean("showTransliteration", fixed.showTransliteration)
            .putBoolean("breathingEnabled", fixed.breathingEnabled)
            .putInt("breathingBpm", fixed.breathingBpm)
            .putFloat("breathingScaleVariance", fixed.breathingScaleVariance)
            .putFloat("breathingDisplacementStrength", fixed.breathingDisplacementStrength)
            .putInt(
                "colorChangeIntervalMs",
                fixed.colorChangeIntervalMs.coerceIn(
                    COLOR_CHANGE_INTERVAL_MIN_MS,
                    COLOR_CHANGE_INTERVAL_MAX_MS,
                ),
            )
            .putBoolean("randomColorSwitchEnabled", fixed.randomColorSwitchEnabled)
            .putInt("fixedColor", fixed.fixedColor)
            .remove("shuffleLayoutRebuildIntervalMs")
            .putInt("projectionSyncOffsetMs", fixed.projectionSyncOffsetMs)
            .putString(KEY_LYRICS_SOURCE_MODE, fixed.lyricsSourceMode.prefValue)
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

    /**
     * ���ڡ��� Root ͨ���������������ԴΪ NETWORK_ONLY������ Shizuku����
     * Root ͨ�������û��л����־û� NETWORK_ONLY / SUPER_LYRIC_ONLY / MIXED��
     */
    private fun enforceLyricsSourceMode(context: Context, s: LyricsUiSettings): LyricsUiSettings {
        PrivilegeBackend.refreshIfUnknown()
        val rootMode = PrivilegeBackend.getMode() == PrivilegeBackend.Mode.ROOT
        if (rootMode) return s
        if (s.lyricsSourceMode == LyricsSourceMode.NETWORK_ONLY) return s
        return s.copy(lyricsSourceMode = LyricsSourceMode.NETWORK_ONLY)
    }

    private fun readCustomFontPath(fontId: String, stored: String?): String? {
        if (fontId != LyricsFontHelper.ID_CUSTOM) return null
        val p = stored?.trim().orEmpty()
        if (p.isEmpty() || !File(p).isFile) return null
        return p
    }

    /**
     * ����δ����ʱ������ֺŸ�һ������ӦĬ��ֵ��
     * ���ֺ�Ĭ�ϸ�����С��С�ֺ�Ĭ�ϸ�������
     */
    private fun adaptiveShuffleSplitScaleVariance(textSize: Float): Float {
        val minSize = 40f
        val maxSize = 140f
        val t = ((textSize - minSize) / (maxSize - minSize)).coerceIn(0f, 1f)
        val maxVariance = 0.30f
        val minVariance = 0.16f
        return (maxVariance - (maxVariance - minVariance) * t).coerceIn(minVariance, maxVariance)
    }
}


