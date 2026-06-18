package com.wmqc.miroot.lyrics

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Bridge that connects Kuwo broadcast lyrics to [RearScreenLyricsActivity] / [ModernLyricsView].
 *
 * ## Why route through [RearScreenLyricsActivity]
 *
 * The activity already owns the `enhancedLyricLines` field and a deduplicating
 * `setLyricsToView` pipeline. If the bridge wrote directly to `ModernLyricsView`,
 * subsequent activity flows (track change, MediaSession callbacks, network fallbacks)
 * would overwrite the word-timestamp data with line-only lyrics.
 *
 * The activity exposes [RearScreenLyricsActivity.applyKuwoBroadcastLyrics] and
 * [RearScreenLyricsActivity.applyKuwoBroadcastProgress] so the bridge can hand off
 * data without bypassing the dedupe / state coordination logic.
 *
 * ## Word-by-word highlighting
 *
 * LRCX broadcasts carry `words_json` with per-character timing data. [KuwoBroadcastLyricParser]
 * converts each entry into [EnhancedLRCParser.WordTimestamp] (absolute song-time ms) and sets
 * `moduleWordTimeline=true` so [ModernLyricsView.computeFusedWordHighlightTarget] is preferred.
 *
 * Reference: 酷我移植参考文档.md §9.
 */
class KuwoBroadcastLyricBridge {

    companion object {
        private const val TAG = "KuwoBroadcastLyric"
    }

    private val context: Context
    private val activity: RearScreenLyricsActivity?
    private val lyricsView: ModernLyricsView?

    /** Recommended path: routes data through the activity for consistent state. */
    constructor(activity: RearScreenLyricsActivity, lyricsView: ModernLyricsView) {
        this.context = activity.applicationContext
        this.activity = activity
        this.lyricsView = lyricsView
    }

    /** Direct-view fallback (kept for testability / non-activity contexts). */
    constructor(context: Context, lyricsView: ModernLyricsView) {
        this.context = context.applicationContext
        this.activity = null
        this.lyricsView = lyricsView
    }

    private val receiver = KuwoBroadcastLyricReceiver()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cached full lyric data
    private var cachedLines: List<String> = emptyList()
    private var cachedLineTimesMs: IntArray? = null
    private var cachedWordsJson: String? = null
    private var cachedMusicId: Long = -1L
    private var cachedLyricType: String = ""
    private var cachedHasWordTiming: Boolean = false

    // ── Lifecycle ─────────────────────────────────────────────

    /** Register the broadcast receiver. Safe to call repeatedly. */
    fun start() {
        receiver.setListener(receiverListener)
        receiver.register(context)
        LogHelper.d(TAG, "Bridge started, receiver registered " +
            "(routing=${if (activity != null) "activity" else "direct-view"})")
    }

    /** Unregister the broadcast receiver. */
    fun stop() {
        receiver.setListener(null)
        receiver.unregister(context)
        LogHelper.d(TAG, "Bridge stopped, receiver unregistered")
    }

    /** True while the receiver is registered. */
    fun isStarted(): Boolean = receiver.isRegistered()

    /**
     * Re-apply cached full lyrics to the view. Called on resume so that if
     * updateMediaInfo() previously set a single-line placeholder, the real
     * lyrics are restored without waiting for a new LYRIC_FULL broadcast.
     */
    fun restoreCachedLyrics() {
        if (cachedLines.isEmpty()) return
        val v = lyricsView ?: return
        mainHandler.post {
            applyFullLyric(cachedLines.toTypedArray(), cachedLineTimesMs,
                cachedWordsJson, cachedLyricType)
        }
    }

    // ── Listener implementation ──────────────────────────────

    private val receiverListener = object : KuwoBroadcastLyricReceiver.Listener {

        override fun onFullLyric(
            musicId: Long,
            title: String,
            artist: String,
            durationMs: Long,
            lyricType: String,
            lines: Array<String>,
            lineTimesMs: IntArray?,
            hasWordTiming: Boolean,
            wordsJson: String?,
            wordsJsonMode: String?,
            wordsJsonUri: android.net.Uri?
        ) {
            if (lines.isEmpty()) {
                LogHelper.d(TAG, "LYRIC_FULL received but lines empty, ignored")
                return
            }

            // Resolve words_json for "content" (FileProvider) mode
            var resolvedWordsJson = wordsJson
            if (wordsJson == null && wordsJsonMode == "content" && wordsJsonUri != null) {
                resolvedWordsJson = KuwoBroadcastLyricParser.readWordsJsonFromUri(
                    context.contentResolver, wordsJsonUri
                )
                LogHelper.d(TAG, "Resolved words_json from FileProvider URI " +
                    "(${resolvedWordsJson?.length ?: 0} chars)")
            }

            // Cache
            cachedMusicId = musicId
            cachedLines = lines.toList()
            cachedLineTimesMs = lineTimesMs
            cachedWordsJson = resolvedWordsJson
            cachedLyricType = lyricType
            cachedHasWordTiming = hasWordTiming

            LogHelper.d(TAG, "LYRIC_FULL received: musicId=$musicId, " +
                "type=$lyricType, lines=${lines.size}, hasWordTiming=$hasWordTiming, " +
                "wordsJsonLen=${resolvedWordsJson?.length ?: 0}")

            // Parse and apply
            mainHandler.post {
                applyFullLyric(lines, lineTimesMs, resolvedWordsJson, lyricType)
            }
        }

        override fun onProgress(
            musicId: Long,
            title: String,
            artist: String,
            positionMs: Long,
            lineIndex: Int,
            lineText: String,
            lineProgress: Int,
            isLrcx: Boolean,
            playing: Boolean,
            lyricType: String,
            wordCount: Int,
            wordIndex: Int,
            wordStartMs: Int,
            wordEndMs: Int,
            wordCharStart: Int,
            wordCharEnd: Int
        ) {
            mainHandler.post {
                applyProgress(positionMs, playing, lineIndex, wordCharStart, wordCharEnd)
            }
        }
    }

    // ── Apply to view / activity ─────────────────────────────

    private fun applyFullLyric(
        lines: Array<String>,
        lineTimesMs: IntArray?,
        wordsJson: String?,
        lyricType: String
    ) {
        if (lines.isEmpty()) return

        val enhancedLines = KuwoBroadcastLyricParser.parseLyricLines(lines, lineTimesMs, wordsJson)

        val totalWords = enhancedLines.sumOf { it.wordTimestamps?.size ?: 0 }
        LogHelper.d(TAG, "Applying ${enhancedLines.size} lines " +
            "(type=$lyricType, totalWordTimestamps=$totalWords)")

        // Prefer routing through activity so existing state coordination works.
        val act = activity
        if (act != null) {
            act.applyKuwoBroadcastLyrics(enhancedLines)
            return
        }

        // Direct-view fallback.
        val v = lyricsView ?: return
        if (totalWords > 0) {
            v.setEnableWordByWord(true)
            v.setCharJumpEnabled(true)
        }
        v.setLyrics(enhancedLines)
        v.setTrackLoading(false)
    }

    private fun applyProgress(positionMs: Long, playing: Boolean,
                              lineIndex: Int, wordCharStart: Int, wordCharEnd: Int) {
        val act = activity
        if (act != null) {
            act.applyKuwoBroadcastProgress(positionMs, playing, lineIndex, wordCharStart, wordCharEnd)
            return
        }
        val v = lyricsView ?: return
        v.setPlaybackActive(playing)
        if (positionMs in 1L..3600000L) {
            v.updatePosition(positionMs)
        }
        if (lineIndex >= 0 && wordCharStart >= 0 && wordCharEnd >= 0) {
            v.setKuwoWordHighlightHint(lineIndex, wordCharStart, wordCharEnd)
        }
    }
}
