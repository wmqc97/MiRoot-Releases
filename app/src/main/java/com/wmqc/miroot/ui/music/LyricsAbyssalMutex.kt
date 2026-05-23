package com.wmqc.miroot.ui.music

/**
 * 深渊镜 与 歌词底图、逐字、分词显示模式、跑马灯、霓虹效果 不能同时开启；
 * 分词显示模式 与 深渊镜、逐字、可滑动歌词 互斥。
 * 省电模式 下仅保留普通歌词，并强制关闭上述特效与霓虹、跑马灯、可滑动歌词等。
 * 其余项之间可任意组合（边框显示为独立偏好，与霓虹总开关同时开才出边框霓虹）。
 * 注意：深渊镜模式下的「可滑动歌词」手势用于切歌（左/右滑上一首/下一首），
 *       与逐行模式的手势（上下滑滚动歌词）含义不同，故不与深渊镜互斥。
 */
fun LyricsUiSettings.withAbyssalMirror(enabled: Boolean): LyricsUiSettings =
    if (enabled) {
        copy(
            abyssalMirror = true,
            albumArtBackground = false,
            backgroundTexture = false,
            wordByWord = false,
            shuffleSplitEffect = false,
            marqueeLight = false,
            neonDisplayEnabled = false,
            neonBorder = false,
        )
    } else {
        copy(abyssalMirror = false)
    }

/**
 * 与深渊镜互斥的项（底图、逐字、分词、跑马灯、霓虹）任一开启时，关闭深渊镜；
 * 若仅开深渊镜，则保证这些项为关（修复脏偏好）。
 * 手势控制（可滑动歌词/切歌）不与深渊镜互斥。
 */
fun LyricsUiSettings.normalizeAbyssalMutex(): LyricsUiSettings {
    if (powerSavingMode) {
        val step = copy(
            abyssalMirror = false,
            albumArtBackground = false,
            gestureControl = false,
            shuffleSplitEffect = false,
            wordByWord = false,
            backgroundTexture = false,
            marqueeLight = false,
            neonDisplayEnabled = false,
            neonBorder = false,
        )
        return if (step != this) step.normalizeAbyssalMutex() else this
    }
    val usingNormal =
        albumArtBackground || backgroundTexture || wordByWord || marqueeLight || neonDisplayEnabled
    if (usingNormal && abyssalMirror) {
        return copy(abyssalMirror = false)
    }
    if (abyssalMirror) {
        return copy(
            albumArtBackground = false,
            backgroundTexture = false,
            wordByWord = false,
            shuffleSplitEffect = false,
            marqueeLight = false,
            neonDisplayEnabled = false,
            neonBorder = false,
        )
    }
    if (shuffleSplitEffect && (wordByWord || gestureControl)) {
        return copy(wordByWord = false, gestureControl = false)
    }
    return this
}
