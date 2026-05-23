package com.wmqc.miroot.lyrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析酷我车载通过 MediaSession extras 下发的 [AUDIO_LYRIC] JSON（见《第三方应用获取歌词_MediaSession集成》）。
 */
object KuwoAudioLyricParser {

    /** 与酷我内部 SUCCESS 映射一致 */
    private const val RESULT_SUCCESS = 20000

    const val EXTRA_AUDIO_LYRIC: String = "AUDIO_LYRIC"

    /**
     * @return 解析成功且 [EnhancedLRCParser.ParseResult.lines] 非空时返回结果，否则 null。
     */
    @JvmStatic
    fun parse(audioLyricJson: String?): EnhancedLRCParser.ParseResult? {
        if (audioLyricJson.isNullOrBlank()) return null
        return try {
            parseImpl(audioLyricJson.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun parseImpl(json: String): EnhancedLRCParser.ParseResult? {
        val root = JSONObject(json)
        val code = root.optInt("resultCode", -1)
        if (code != RESULT_SUCCESS) return null

        val arr: JSONArray = root.optJSONArray(EXTRA_AUDIO_LYRIC) ?: return null
        if (arr.length() == 0) return null

        val result = EnhancedLRCParser.ParseResult()
        for (i in 0 until arr.length()) {
            val lineObj = arr.optJSONObject(i) ?: continue
            val startMs = lineObj.optLong("startTime", 0L)
            val text = lineObj.optString("text", "").trim()
            if (text.isEmpty()) continue
            result.lines.add(EnhancedLRCParser.EnhancedLyricLine(startMs, text))
        }
        if (result.lines.isEmpty()) return null

        result.lines.sortBy { it.time }
        return result
    }
}
