package com.wmqc.miroot.ui.music

import com.wmqc.miroot.lyrics.LyricsFontHelper

/** ���ʱ��ƫ�ƣ����룩�״ΰ�װ��δд��ƫ��ʱ��Ĭ��ֵ�� */
const val DEFAULT_PROJECTION_SYNC_OFFSET_MS = 0

enum class LyricsSourceMode(val prefValue: String) {
    NETWORK_ONLY("NETWORK_ONLY"),
    SUPER_LYRIC_ONLY("SUPER_LYRIC_ONLY"),
    MIXED("MIXED");

    companion object {
        fun fromPrefValue(raw: String?): LyricsSourceMode {
            return entries.firstOrNull { it.prefValue.equals(raw, ignoreCase = true) } ?: MIXED
        }
    }
}

/**
 * �뱳�� [com.wmqc.miroot.lyrics.RearScreenLyricsActivity] ʹ�õ� `LyricsSettings` SharedPreferences ��һ�¡�
 */
data class LyricsUiSettings(
    val textSize: Float = 78f,
    val backgroundTextureSize: Float = 1.3f,
    val normalLyricsAlpha: Int = 30,
    val backgroundTextureAlpha: Int = 20,
    /** ����Ͷ������ʹ�ø���ר��ͼ���������ģ������ */
    val albumArtBackground: Boolean = false,
    /** ר��ͼ����͸���ȣ�0~100���� */
    val albumArtAlphaPercent: Int = 35,
    /** ר��ͼ����ģ���뾶�����أ���0 ��ʾ��ģ���� */
    val albumArtBlurRadius: Float = 12f,
    val wordByWord: Boolean = false,
    /** ������ʾ�����������ƣ���ǰ�����ƾۼ���������׼ȷ����ʱ����Ϣʱ��Ч�� */
    val charJumpEnabled: Boolean = false,
    /** �������Ƹ߶ȣ����أ������������ϸ����ѳ����ĸ߶Ȳ */
    val charJumpHeightPx: Float = 20f,
    val shuffleSplitEffect: Boolean = false,
    /** �ִ���ʾģʽ��ÿ�� token ʹ�ö�ɫ������ر������������/�����ɫ���� */
    val shuffleSplitMulticolor: Boolean = false,
    val shuffleSplitMode: String = "WORD",
    val shuffleSplitOnlyCurrentLine: Boolean = true,
    val shuffleSplitTiltRatio: Float = 5f,
    /** �ִʴ����С��������ǿ�ȣ�0~0.4���� */
    val shuffleSplitScaleVariance: Float = 0.22f,
    /** ���ʡ��ģʽ������ߡ������͸�Ƶ�ػ棬���ͺĵ��뷢�ȡ� */
    val powerSavingMode: Boolean = false,
    /** �߿����ܻ���������������������Ⱦѹ���Զ�����������Ĭ�Ϲر��Ա���������ʾ��Ϊ�� */
    val borderPerformanceGuard: Boolean = false,
    /** �߿�����ģʽ�����ֶ����ã��������ܻ����Զ����롣 */
    val borderLightweightMode: Boolean = false,
    val marqueeLight: Boolean = true,
    /** �޺�Ч�����Ƴ��������ֶν����ڼ��ݾ����á� */
    val neonDisplayEnabled: Boolean = false,
    /** �߿���ʾ���Ƿ������Ļ��Ե�߿� */
    val neonBorder: Boolean = true,
    val marqueeLightSize: Float = 18f,
    /** ������һȦʱ�������룩����ֵԽС�ٶ�Խ�졣 */
    val marqueeLightDurationMs: Int = 5000,
    val gestureControl: Boolean = false,
    val backgroundTexture: Boolean = false,
    val showTranslation: Boolean = true,
    val showTransliteration: Boolean = true,
    val autoProjection: Boolean = false,
    /** ���������ܿ��أ��رպ�����ʲ���ִ�к���������λ�ơ� */
    val breathingEnabled: Boolean = false,
    /** ����Ƶ�ʣ�ÿ��������������BPM���� */
    val breathingBpm: Int = 15,
    /** �������Ÿ�����С��0.01~0.20����Ӧ scale �������������ȣ��� */
    val breathingScaleVariance: Float = 0.10f,
    /** ����λ��ǿ�ȱ��ʣ�Ӱ��������΢Ư�ƣ��� */
    val breathingDisplacementStrength: Float = 1f,
    /** �����ɫ�ӵ�ǰɫ���ɵ���һĿ��ɫ��ʱ�������룩������ 1��10 �롢���� 1 �룬Ĭ�� 5 �롣 */
    val colorChangeIntervalMs: Int = 5000,
    /** �����ɫ�л������������������ɫ���رչ̶��߿ɶ��ڰ���ɫ�� */
    val randomColorSwitchEnabled: Boolean = true,
    /** ��ɫ��ѡ�Ĺ̶������ ARGB ���ֵ�� only used when randomColorSwitchEnabled=false */
    val fixedColor: Int = 0xFFFFFFFF.toInt(),
    /**
     * Ͷ��������ý����ȵ�ʱ��ƫ�ƣ����룩��
     * ��ֵ����ǰ��ʾ�������ȸ�ʿ졢�����ͺ�ʱ�����󣩣���ֵ���Ӻ���ʾ��
     */
    val projectionSyncOffsetMs: Int = DEFAULT_PROJECTION_SYNC_OFFSET_MS,
    /** �����Դ������ API��SuperLyric���������л���MIXED���� */
    val lyricsSourceMode: LyricsSourceMode = LyricsSourceMode.MIXED,
    val abyssalMirror: Boolean = false,
    val abyssalGyroSensitivity: Float = 1f,
    val abyssalMovableRange: Float = 2.5f,
    /** ����������壨���ִ�ģʽ����Ԩ�������� [LyricsFontHelper]�� */
    val projectionLyricsFont: String = LyricsFontHelper.DEFAULT_ID,
    /** �Զ�������·�������� [projectionLyricsFont] Ϊ [LyricsFontHelper.ID_CUSTOM] ʱ��Ч���� */
    val projectionLyricsCustomPath: String? = null,
    /** �� [projectionLyricsFont] ͬ���־û�������ƫ�ü����ݣ��߼�����Ͷ������Ϊ׼�� */
    val abyssalLyricsFont: String = LyricsFontHelper.DEFAULT_ID,
    /** �� [projectionLyricsCustomPath] ͬ���� */
    val abyssalLyricsCustomPath: String? = null,
)

