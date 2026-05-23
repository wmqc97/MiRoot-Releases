package com.wmqc.miroot.lyrics

import android.content.Context
import android.content.Intent

/**
 * 向外部进程发送音乐投屏状态变更（不调用 [Intent.setPackage]，与车控回执一致）。
 */
object MusicProjectionPublicBroadcast {

    fun sendStateChanged(appContext: Context, running: Boolean) {
        appContext.sendBroadcast(
            Intent(LyricsIntents.ACTION_MUSIC_PROJECTION_STATE_CHANGED).apply {
                putExtra(
                    LyricsIntents.EXTRA_MUSIC_PROJECTION_RUNNING,
                    if (running) "true" else "false",
                )
            },
        )
    }
}
