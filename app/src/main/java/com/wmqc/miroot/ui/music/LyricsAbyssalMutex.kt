package com.wmqc.miroot.ui.music

/**
 * 深渊镜 与 歌词底图、逐字、跑马灯、霓虹边框、可滑动歌词 不能同时开启；
 * 其余五项之间可以任意组合。
 */
fun LyricsUiSettings.withAbyssalMirror(enabled: Boolean): LyricsUiSettings =
    if (enabled) {
        copy(
            abyssalMirror = true,
            backgroundTexture = false,
            wordByWord = false,
            marqueeLight = false,
            neonBorder = false,
            gestureControl = false,
        )
    } else {
        copy(abyssalMirror = false)
    }

/**
 * 与深渊镜互斥的五项任一开启时，关闭深渊镜；
 * 若仅开深渊镜，则保证五项为关（修复脏偏好）。
 */
fun LyricsUiSettings.normalizeAbyssalMutex(): LyricsUiSettings {
    val usingNormal =
        backgroundTexture || wordByWord || marqueeLight || neonBorder || gestureControl
    if (usingNormal && abyssalMirror) {
        return copy(abyssalMirror = false)
    }
    if (abyssalMirror) {
        return copy(
            backgroundTexture = false,
            wordByWord = false,
            marqueeLight = false,
            neonBorder = false,
            gestureControl = false,
        )
    }
    return this
}
