package com.wmqc.miroot.record

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * 检测 MP4 等容器是否包含音频轨（用于判断 screenrecord --audio 是否真正写入音轨）。
 */
object VideoAudioProbe {

    fun hasAudioTrack(file: File): Boolean {
        if (!file.isFile || file.length() < 64L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return true
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
