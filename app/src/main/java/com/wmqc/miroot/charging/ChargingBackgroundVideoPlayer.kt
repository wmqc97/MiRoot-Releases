package com.wmqc.miroot.charging

import android.content.Context
import android.net.Uri
import android.view.TextureView
import android.view.View
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * 充电动画底层场景视频：循环静音铺满，由 [TextureView] 置于水效 View 下方。
 */
@UnstableApi
class ChargingBackgroundVideoPlayer(
    context: Context,
    private val textureView: TextureView,
) {
    private val appContext = context.applicationContext
    private val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }

    private var active = false
    private var loadedUri: Uri? = null

    init {
        player.setVideoTextureView(textureView)
    }

    /** @return 是否正在播放自定义背景视频 */
    fun applyFromStorage(): Boolean {
        val file = ChargingAnimationPrefs.customBackgroundVideoFile(appContext)
        if (!ChargingAnimationPrefs.hasCustomBackgroundVideo(appContext)) {
            stopInternal()
            return false
        }
        val uri = file.toUri()
        if (active && loadedUri == uri) {
            textureView.visibility = View.VISIBLE
            if (!player.isPlaying) {
                player.play()
            }
            return true
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        textureView.visibility = View.VISIBLE
        loadedUri = uri
        active = true
        return true
    }

    fun pause() {
        if (active) {
            player.pause()
        }
    }

    fun resume() {
        if (active) {
            player.play()
        }
    }

    fun release() {
        stopInternal()
        player.release()
    }

    private fun stopInternal() {
        active = false
        loadedUri = null
        player.stop()
        player.clearMediaItems()
        textureView.visibility = View.GONE
    }
}
