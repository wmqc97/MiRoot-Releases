package com.wmqc.miroot.record

import com.wmqc.miroot.lyrics.LogHelper

/**
 * 背屏录屏 / 合成诊断日志。全部经 [LogHelper] 输出，仅 **debug** 构建可见。
 */
object RecordSynthDebugLog {
    const val TAG = "MiRoot-RecordSynth"

    fun d(msg: String) {
        LogHelper.d(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (t != null) LogHelper.w(TAG, msg, t) else LogHelper.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) LogHelper.e(TAG, msg, t) else LogHelper.e(TAG, msg)
    }

    fun diagI(msg: String) {
        LogHelper.i(TAG, msg)
    }

    fun diagW(msg: String, t: Throwable? = null) {
        if (t != null) LogHelper.w(TAG, msg, t) else LogHelper.w(TAG, msg)
    }

    fun truncateShellOutput(s: String?, maxChars: Int = 3500): String {
        if (s.isNullOrBlank()) return "<empty>"
        val t = s.trim().replace("\r\n", "\n")
        return if (t.length <= maxChars) t else t.take(maxChars) + "\n…(" + (t.length - maxChars) + " more chars)"
    }
}
