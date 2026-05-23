package com.wmqc.miroot.lyrics

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import java.util.function.Consumer

/**
 * [RearScreenLyricsActivity] 的 [MediaController.Callback]，与 UI 更新逻辑解耦以便单测与维护。
 */
class RearLyricsMediaSessionCallbacks(
    private val metadataRunnable: Runnable,
    private val playbackStateRunnable: Runnable,
    private val extrasConsumer: Consumer<Bundle?>,
) : MediaController.Callback() {

    override fun onMetadataChanged(metadata: MediaMetadata?) {
        metadataRunnable.run()
    }

    override fun onPlaybackStateChanged(state: PlaybackState?) {
        playbackStateRunnable.run()
    }

    override fun onExtrasChanged(extras: Bundle?) {
        extrasConsumer.accept(extras)
    }
}
