package com.wmqc.miroot.ui.music

import com.wmqc.miroot.lyrics.LyricsFontHelper

/**
 * 与背屏 [com.wmqc.miroot.lyrics.RearScreenLyricsActivity] 使用的 `LyricsSettings` SharedPreferences 键一致。
 */
data class LyricsUiSettings(
    val textSize: Float = 78f,
    val backgroundTextureSize: Float = 1.3f,
    val normalLyricsAlpha: Int = 30,
    val backgroundTextureAlpha: Int = 20,
    val wordByWord: Boolean = false,
    val marqueeLight: Boolean = true,
    val neonBorder: Boolean = true,
    val marqueeLightSize: Float = 18f,
    val gestureControl: Boolean = false,
    val backgroundTexture: Boolean = false,
    val autoProjection: Boolean = false,
    val abyssalMirror: Boolean = false,
    val abyssalGyroSensitivity: Float = 1f,
    val abyssalMovableRange: Float = 2.5f,
    /** 投屏（非深渊镜）歌词字体，见 [LyricsFontHelper]。 */
    val projectionLyricsFont: String = LyricsFontHelper.DEFAULT_ID,
    /** 投屏自定义字体文件路径（仅当 [projectionLyricsFont] 为 [LyricsFontHelper.ID_CUSTOM] 时有效）。 */
    val projectionLyricsCustomPath: String? = null,
    /** 深渊镜单行歌词字体，见 [LyricsFontHelper]。 */
    val abyssalLyricsFont: String = LyricsFontHelper.DEFAULT_ID,
    /** 深渊镜自定义字体文件路径（仅当 [abyssalLyricsFont] 为 [LyricsFontHelper.ID_CUSTOM] 时有效）。 */
    val abyssalLyricsCustomPath: String? = null,
)
