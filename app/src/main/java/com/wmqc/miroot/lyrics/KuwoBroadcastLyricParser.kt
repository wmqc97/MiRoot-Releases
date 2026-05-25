package com.wmqc.miroot.lyrics

import org.json.JSONArray
import org.json.JSONException
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Parse Kuwo car edition broadcast lyric data (LYRIC_FULL / LYRIC_PROGRESS)
 * into [EnhancedLRCParser.EnhancedLyricLine] with [EnhancedLRCParser.WordTimestamp]
 * for word-by-word highlighting.
 *
 * Reference: [F:\BatchApkTool\kuwo5-16\_INPUT_APK\kuwo_7.3.9.23\酷我移植参考文档.md §9]
 */
object KuwoBroadcastLyricParser {

    private const val TAG = "KuwoBroadcastLyric"

    /** A single word timing entry from the `words_json` 2D array. */
    data class RawWordTiming(
        val cs: Int,  // char start index in line text (inclusive)
        val ce: Int,  // char end index in line text (inclusive)
        val wi: Int,  // word index within line (0-based)
        val s: Int,   // start time relative to line start (ms)
        val e: Int    // end time relative to line start (ms)
    )

    // ── Public API ──────────────────────────────────────────────

    /**
     * Parse `LYRIC_FULL` extras into [EnhancedLRCParser.EnhancedLyricLine] list
     * with optional word timestamps from `words_json`.
     *
     * @param lines        all lyric line texts
     * @param lineTimesMs  line start times in milliseconds
     * @param wordsJson    LRCX word timing JSON string, or null/empty for non-LRCX
     * @return list ready for [ModernLyricsView.setLyricLines]
     */
    @JvmStatic
    fun parseLyricLines(
        lines: Array<String>?,
        lineTimesMs: IntArray?,
        wordsJson: String?
    ): List<EnhancedLRCParser.EnhancedLyricLine> {
        if (lines == null || lines.isEmpty()) return emptyList()

        val times = lineTimesMs ?: IntArray(lines.size) { 0 }
        val wordTimingRows: List<List<RawWordTiming>>? =
            if (wordsJson.isNullOrEmpty()) null else parseWordsJson(wordsJson)

        return lines.indices.map { i ->
            val text = lines[i].orEmpty()
            val time = if (i < times.size) times[i].toLong() else 0L
            val line = EnhancedLRCParser.EnhancedLyricLine(time, text)
            line.translation = null
            val words = buildWordTimestampsForLine(
                text,
                time,
                wordTimingRows?.getOrNull(i)
            )
            if (words != null && words.isNotEmpty()) {
                line.wordTimestamps = words
                // wordTimestamps use absolute song-time ms (lineTimeMs + raw.s/e), so
                // ModernLyricsView.computeFusedWordHighlightTarget should handle them as
                // module-style absolute timeline (not sentence-relative).
                line.moduleWordTimeline = true
            }
            line
        }.toList()
    }

    /**
     * Parse the `words_json` field into a 2D list of [RawWordTiming].
     * Outer index = line index, inner list = words in that line.
     */
    @JvmStatic
    fun parseWordsJson(json: String): List<List<RawWordTiming>> {
        return try {
            val root = JSONArray(json)
            (0 until root.length()).map { lineIdx ->
                val lineArr = root.optJSONArray(lineIdx) ?: JSONArray()
                parseWordTimingRow(lineArr)
            }
        } catch (e: JSONException) {
            LogHelper.w(TAG, "Failed to parse words_json: ${e.message}")
            emptyList()
        }
    }

    /**
     * Read `words_json` content from a Content URI (large file mode).
     * Requires the Kuwo FileProvider authority `cn.kuwo.kwmusiccar.fileprovider`.
     */
    @JvmStatic
    fun readWordsJsonFromUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri
    ): String? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "Failed to read words_json from URI: ${e.message}")
            null
        }
    }

    // ── Internal ────────────────────────────────────────────────

    private fun parseWordTimingRow(arr: JSONArray): List<RawWordTiming> {
        val row = ArrayList<RawWordTiming>(arr.length())
        for (j in 0 until arr.length()) {
            val obj = arr.optJSONObject(j) ?: continue
            row.add(
                RawWordTiming(
                    cs = obj.optInt("cs", -1),
                    ce = obj.optInt("ce", -1),
                    wi = obj.optInt("wi", -1),
                    s  = obj.optInt("s", 0),
                    e  = obj.optInt("e", 0)
                )
            )
        }
        return row
    }

    /**
     * Convert a row of [RawWordTiming] into [EnhancedLRCParser.WordTimestamp] list,
     * mapping character indices to actual text substrings and computing absolute times.
     */
    private fun buildWordTimestampsForLine(
        lineText: String,
        lineTimeMs: Long,
        rawWords: List<RawWordTiming>?
    ): ArrayList<EnhancedLRCParser.WordTimestamp>? {
        if (rawWords.isNullOrEmpty() || lineText.isEmpty()) return null

        val timestamps = ArrayList<EnhancedLRCParser.WordTimestamp>(rawWords.size)
        for (raw in rawWords) {
            // Extract the actual character substring from line text
            val wordText = if (raw.cs >= 0 && raw.ce >= raw.cs && raw.ce < lineText.length) {
                lineText.substring(raw.cs, raw.ce + 1)
            } else {
                // Fallback: use word index or empty
                ""
            }

            // Convert line-relative times to absolute song timeline
            val absStart = lineTimeMs + raw.s
            val absEnd = lineTimeMs + raw.e
            val endTime = if (absEnd > absStart) absEnd else absStart + 220L

            timestamps.add(
                EnhancedLRCParser.WordTimestamp(wordText, absStart, endTime)
            )
        }
        return if (timestamps.isEmpty()) null else timestamps
    }
}
