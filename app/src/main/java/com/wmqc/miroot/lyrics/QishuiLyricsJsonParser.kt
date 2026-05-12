package com.wmqc.miroot.lyrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * 汽水音乐（可能的网络回包）歌词 JSON 兜底解析。
 *
 * 目标是从各种包裹层里尽量提取出 LRC/逐字等“content”字段。
 */
object QishuiLyricsJsonParser {

    fun extractLyricContent(json: String?): String? {
        if (json.isNullOrBlank()) return null

        // Fast-path: 如果本身就是 LRC 文本，直接返回。
        val raw = json.trim()
        if (looksLikeRawLyrics(raw)) return raw

        return runCatching {
            val any = parseJsonAny(raw)
            extractFromAny(any)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseJsonAny(s: String): Any? {
        return when {
            s.startsWith("{") -> JSONObject(s)
            s.startsWith("[") -> JSONArray(s)
            else -> null
        }
    }

    private fun extractFromAny(any: Any?): String? {
        return when (any) {
            is JSONObject -> extractFromObject(any)
            is JSONArray -> extractFromArray(any)
            else -> null
        }
    }

    private fun extractFromArray(arr: JSONArray): String? {
        for (i in 0 until arr.length()) {
            extractFromAny(arr.opt(i))?.let { return it }
        }
        return null
    }

    private fun extractFromObject(obj: JSONObject): String? {
        // 1) 直接字段（兼容不同服务端命名）
        listOf(
            "content",
            "lyric",
            "lyrics",
            "lyricContent",
            "lyric_content",
            "lrc",
            "krc",
            "yrc",
            "qrc",
        ).forEach { key ->
            runCatching { obj.optString(key) }.getOrNull()?.let {
                if (it.isNotBlank() && it != "null") return it
            }
        }

        // 2) 常见包裹：data / result / lyric 等
        listOf("data", "result", "lyric", "lyrics", "payload", "response").forEach { k ->
            extractFromAny(obj.opt(k))?.let { return it }
        }

        // 3) 遍历：尽量在任意嵌套里找歌词相关字段
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = obj.opt(k)
            if (v is String && v.isNotBlank() && k.contains("lyric", ignoreCase = true)) return v
            if (k.equals("content", ignoreCase = true) && v is String && v.isNotBlank()) return v
            extractFromAny(v)?.let { nested -> return nested }
        }
        return null
    }

    private fun looksLikeRawLyrics(s: String): Boolean {
        if (s.isBlank()) return false
        if (s.contains('\n') && !s.startsWith("{") && !s.startsWith("[")) return true
        if (Regex(""".*\[\d{1,2}:\d{2}(?:\.\d{2,3})?].*""").matches(s)) return true
        if (Regex(""".*<\d+(?:,\d+)?>.*""").matches(s)) return true
        return false
    }
}

