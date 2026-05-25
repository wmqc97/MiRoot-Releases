package com.wmqc.miroot.lyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * BroadcastReceiver for Kuwo car edition lyrics broadcasts.
 *
 * Listens for:
 * - `cn.kuwo.kwmusiccar.action.LYRIC_FULL` — full lyrics with word timestamps
 * - `cn.kuwo.kwmusiccar.action.LYRIC_PROGRESS` — real-time playback progress
 *
 * Register dynamically via [register] (Android 8+ restricts manifest-registered receivers
 * for custom implicit broadcasts).
 *
 * Reference: [酷我移植参考文档.md §9]
 */
class KuwoBroadcastLyricReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_LYRIC_FULL = "cn.kuwo.kwmusiccar.action.LYRIC_FULL"
        const val ACTION_LYRIC_PROGRESS = "cn.kuwo.kwmusiccar.action.LYRIC_PROGRESS"
        const val KUWO_PACKAGE = "cn.kuwo.kwmusiccar"
        const val KUWO_URI_AUTHORITY = "$KUWO_PACKAGE.fileprovider"
    }

    private var registered = false

    /** Callback interface for received lyrics data. */
    interface Listener {
        fun onFullLyric(
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
        )

        fun onProgress(
            musicId: Long,
            title: String,
            artist: String,
            positionMs: Long,
            lineIndex: Int,
            lineText: String,
            lineProgress: Int,   // 0–100 (LRCX only, meaningful)
            isLrcx: Boolean,
            playing: Boolean,
            lyricType: String,
            // Word-level progress (only meaningful when isLrcx == true)
            wordCount: Int,
            wordIndex: Int,
            wordStartMs: Int,    // line-relative offset
            wordEndMs: Int,      // line-relative offset
            wordCharStart: Int,
            wordCharEnd: Int
        )
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isRegistered(): Boolean = registered

    /**
     * Dynamically register this receiver.
     * Call from an active Context (e.g., in Activity.onResume or Service.onCreate).
     */
    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_LYRIC_FULL)
            addAction(ACTION_LYRIC_PROGRESS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        try {
            context.unregisterReceiver(this)
        } catch (e: IllegalArgumentException) {
            // Already unregistered or never registered
        }
        registered = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val l = listener ?: return

        when (action) {
            ACTION_LYRIC_FULL -> {
                onFullLyric(intent, l)
            }
            ACTION_LYRIC_PROGRESS -> {
                onProgress(intent, l)
            }
        }
    }

    // ── LYRIC_FULL parsing ────────────────────────────────────

    private var fullCount = 0
    private var progressCount = 0

    private fun onFullLyric(intent: Intent, l: Listener) {
        fullCount++
        val musicId = intent.getLongExtra("music_id", -1L)
        val title = intent.getStringExtra("title") ?: ""
        val artist = intent.getStringExtra("artist") ?: ""
        val durationMs = intent.getLongExtra("duration_ms", 0L)
        val lyricType = intent.getStringExtra("lyric_type") ?: ""
        val lines = intent.getStringArrayExtra("lines") ?: emptyArray()
        val lineTimesMs = intent.getIntArrayExtra("line_times_ms")
        val hasWordTiming = intent.getBooleanExtra("has_word_timing", false)

        android.util.Log.wtf("MIR-Kuwo", "FULL#$fullCount id=$musicId type=$lyricType " +
            "lines=${lines.size} hasWord=$hasWordTiming title=$title")

        var wordsJson: String? = null
        var wordsJsonMode: String? = null
        var wordsJsonUri: android.net.Uri? = null

        if (hasWordTiming) {
            wordsJsonMode = intent.getStringExtra("words_json_mode")
            when {
                wordsJsonMode == "content" -> {
                    wordsJsonUri = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra("words_json_uri", android.net.Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("words_json_uri")
                    }
                }
                else -> {
                    wordsJson = intent.getStringExtra("words_json")
                }
            }
        }

        l.onFullLyric(
            musicId, title, artist, durationMs, lyricType,
            lines, lineTimesMs, hasWordTiming,
            wordsJson, wordsJsonMode, wordsJsonUri
        )
    }

    // ── LYRIC_PROGRESS parsing ────────────────────────────────

    private fun onProgress(intent: Intent, l: Listener) {
        progressCount++
        val musicId = intent.getLongExtra("music_id", -1L)
        val title = intent.getStringExtra("title") ?: ""
        val artist = intent.getStringExtra("artist") ?: ""
        val positionMs = intent.getLongExtra("position_ms", 0L)
        val lineIndex = intent.getIntExtra("line_index", -1)
        val lineText = intent.getStringExtra("line_text") ?: ""
        val lineProgress = intent.getIntExtra("line_progress", 0)
        val isLrcx = intent.getBooleanExtra("is_lrcx", false)
        val playing = intent.getBooleanExtra("playing", false)
        val lyricType = intent.getStringExtra("lyric_type") ?: ""

        val wordCount = intent.getIntExtra("word_count", 0)
        val wordIndex = intent.getIntExtra("word_index", -1)
        val wordStartMs = intent.getIntExtra("word_start_ms", -1)
        val wordEndMs = intent.getIntExtra("word_end_ms", -1)
        val wordCharStart = intent.getIntExtra("word_char_start", -1)
        val wordCharEnd = intent.getIntExtra("word_char_end", -1)

        if (progressCount % 50 == 1) {
            android.util.Log.wtf("MIR-Kuwo", "PROG#$progressCount pos=$positionMs " +
                "line=$lineIndex words=$wordCount wi=$wordIndex cs=$wordCharStart ce=$wordCharEnd")
        }

        l.onProgress(
            musicId, title, artist, positionMs,
            lineIndex, lineText, lineProgress,
            isLrcx, playing, lyricType,
            wordCount, wordIndex, wordStartMs, wordEndMs,
            wordCharStart, wordCharEnd
        )
    }
}
