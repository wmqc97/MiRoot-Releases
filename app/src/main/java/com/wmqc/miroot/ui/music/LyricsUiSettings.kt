package com.wmqc.miroot.ui.music

import com.wmqc.miroot.lyrics.LyricsFontHelper

/** 歌词时间偏移（毫秒）首次安装或未写入偏好时的默认值。 */
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
 * 与背屏 [com.wmqc.miroot.lyrics.RearScreenLyricsActivity] 使用的 `LyricsSettings` SharedPreferences 键一致。
 */
data class LyricsUiSettings(
    val textSize: Float = 78f,
    val backgroundTextureSize: Float = 1.3f,
    val normalLyricsAlpha: Int = 30,
    val backgroundTextureAlpha: Int = 20,
    /** 音乐投屏背景使用歌曲专辑图（叠加轻度模糊）。 */
    val albumArtBackground: Boolean = false,
    /** 专辑图背景透明度（0~100）。 */
    val albumArtAlphaPercent: Int = 35,
    /** 专辑图背景模糊半径（像素），0 表示不模糊。 */
    val albumArtBlurRadius: Float = 12f,
    val wordByWord: Boolean = false,
    val shuffleSplitEffect: Boolean = false,
    /** 分词显示模式：每个 token 使用多色随机（关闭则跟随跑马灯/歌词主色）。 */
    val shuffleSplitMulticolor: Boolean = false,
    val shuffleSplitMode: String = "WORD",
    val shuffleSplitOnlyCurrentLine: Boolean = true,
    val shuffleSplitTiltRatio: Float = 5f,
    /** 分词词组大小正负浮动强度（0~0.4）。 */
    val shuffleSplitScaleVariance: Float = 0.22f,
    /** 歌词省电模式：简化描边、动画和高频重绘，降低耗电与发热。 */
    val powerSavingMode: Boolean = false,
    /** 边框性能护栏：开启后允许根据渲染压力自动切轻量档；默认关闭以保持现有显示行为。 */
    val borderPerformanceGuard: Boolean = false,
    /** 边框轻量模式：可手动启用，或由性能护栏自动切入。 */
    val borderLightweightMode: Boolean = false,
    val marqueeLight: Boolean = true,
    /** 霓虹效果已移除，保留字段仅用于兼容旧配置。 */
    val neonDisplayEnabled: Boolean = false,
    /** 边框显示：是否绘制屏幕边缘边框。 */
    val neonBorder: Boolean = true,
    val marqueeLightSize: Float = 18f,
    /** 跑马灯一圈时长（毫秒），数值越小速度越快。 */
    val marqueeLightDurationMs: Int = 5000,
    val gestureControl: Boolean = false,
    val backgroundTexture: Boolean = false,
    val autoProjection: Boolean = false,
    /** 呼吸动画总开关：关闭后背屏歌词不再执行呼吸缩放与位移。 */
    val breathingEnabled: Boolean = false,
    /** 呼吸频率（每分钟跳动次数，BPM）。 */
    val breathingBpm: Int = 15,
    /** 呼吸缩放浮动大小（0.01~0.20，对应 scale 的正负浮动幅度）。 */
    val breathingScaleVariance: Float = 0.10f,
    /** 呼吸位移强度倍率（影响上下轻微漂移）。 */
    val breathingDisplacementStrength: Float = 1f,
    /** 随机配色从当前色过渡到下一目标色的时长（毫秒）；界面 1～10 秒、步进 1 秒，默认 5 秒。 */
    val colorChangeIntervalMs: Int = 5000,
    /** 随机颜色切换：开启按节奏随机变色；关闭固定高可读黑白配色。 */
    val randomColorSwitchEnabled: Boolean = true,
    /**
     * 投屏歌词相对媒体进度的时间偏移（毫秒）。
     * 正值：提前显示（声音比歌词快、逐字滞后时可增大）；负值：延后显示。
     */
    val projectionSyncOffsetMs: Int = DEFAULT_PROJECTION_SYNC_OFFSET_MS,
    /** 歌词来源：网络 API、SuperLyric、或智能切换（MIXED）。 */
    val lyricsSourceMode: LyricsSourceMode = LyricsSourceMode.MIXED,
    val abyssalMirror: Boolean = false,
    val abyssalGyroSensitivity: Float = 1f,
    val abyssalMovableRange: Float = 2.5f,
    /** 背屏歌词字体（含分词模式与深渊镜），见 [LyricsFontHelper]。 */
    val projectionLyricsFont: String = LyricsFontHelper.DEFAULT_ID,
    /** 自定义字体路径（仅当 [projectionLyricsFont] 为 [LyricsFontHelper.ID_CUSTOM] 时有效）。 */
    val projectionLyricsCustomPath: String? = null,
    /** 与 [projectionLyricsFont] 同步持久化，供旧偏好键兼容；逻辑上以投屏字体为准。 */
    val abyssalLyricsFont: String = LyricsFontHelper.DEFAULT_ID,
    /** 与 [projectionLyricsCustomPath] 同步。 */
    val abyssalLyricsCustomPath: String? = null,
)
