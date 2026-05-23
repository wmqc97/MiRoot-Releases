package com.wmqc.miroot.lyrics

/**
 * 背屏歌词投屏界面 Debug 来源指示（见 [RearScreenLyricsActivity] 标题下方文案）。
 */
enum class LyricsRuntimeSource(val displayLabel: String) {
    IDLE("待机"),
    ACQUIRING("获取中"),
    KUWO_PENDING("酷我 · 等待 AUDIO_LYRIC"),
    KUWO_AUDIO_LYRIC("AUDIO_LYRIC"),
    NETWORK("网络"),
    NETWORK_KUGOU("网络·酷狗"),
    NETWORK_QSGC("网络·汽水"),
    NETWORK_LRCLIB("网络·LRCLIB"),
    NETWORK_LYRICS_OVH("网络·OVH"),
    SUPER_LYRIC("SuperLyric");

    companion object {
        /** 投屏 Debug「当前歌词来源」段：具体 API 名称（不含「网络·」前缀）。 */
        @JvmStatic
        fun shortApiLabel(provider: String?): String {
            val p = provider?.trim()?.lowercase().orEmpty()
            return when (p) {
                "kugou" -> "酷狗"
                "qsgc" -> "qsgc"
                "lrclib" -> "LRCLIB"
                "lyrics.ovh" -> "lyrics.ovh"
                else -> provider?.trim()?.takeIf { it.isNotEmpty() } ?: "网络API"
            }
        }

        @JvmStatic
        fun shortApiLabel(source: LyricsRuntimeSource): String = when (source) {
            NETWORK_KUGOU -> "酷狗"
            NETWORK_QSGC -> "qsgc"
            NETWORK_LRCLIB -> "LRCLIB"
            NETWORK_LYRICS_OVH -> "lyrics.ovh"
            NETWORK -> "网络API"
            else -> source.displayLabel
        }
    }
}
