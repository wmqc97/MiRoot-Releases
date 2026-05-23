package com.wmqc.miroot.lyrics

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.activity.ComponentActivity
import com.wmqc.miroot.service.MiRootNotificationListenerService

/**
 * 酷我车载：何时走 MediaBrowser 直连 KwMediaSession，以及播放状态变化时是否需重绑 [RearScreenLyricsActivity.setupMediaController]。
 */
object KuwoCarLyricsPolicy {

    private const val LOG_TAG = "RearScreenLyricsActivity"

    /**
     * 系统返回的活跃会话列表中，首位不一定是当前出声播放器（多应用同时保留 MediaSession 时常见）。
     * 优先选择 [PlaybackState.STATE_PLAYING] 的会话，否则退回列表首位（与系统优先级顺序一致）。
     */
    @JvmStatic
    fun preferredActiveController(controllers: List<MediaController>?): MediaController? {
        if (controllers.isNullOrEmpty()) return null
        for (c in controllers) {
            val ps = c.playbackState ?: continue
            if (ps.state == PlaybackState.STATE_PLAYING) return c
        }
        return controllers[0]
    }

    @JvmStatic
    fun shouldUseKuwoCarLyrics(first: MediaController?): Boolean {
        if (first == null) return false
        if (KuwoCarMediaSessionHelper.KUWO_PACKAGE != first.packageName) return false
        val ps = first.playbackState
        return ps != null && ps.state == PlaybackState.STATE_PLAYING
    }

    /** 是否为同一 MediaSession（切换播放器后 sessionToken 会变）。 */
    @JvmStatic
    fun isSameMediaSession(current: MediaController?, preferred: MediaController?): Boolean {
        if (current == null && preferred == null) return true
        if (current == null || preferred == null) return false
        return current.sessionToken == preferred.sessionToken
    }

    /**
     * 当前附着的 [MediaController] 与「正在播放的优先会话」不一致，或酷我 MediaBrowser 策略需切换时，触发重绑。
     * <p>
     * 仅依赖 [MediaSessionManager.OnActiveSessionsChangedListener] 不够：多播放器同时保留会话时列表不变，
     * 仅播放态在应用间切换，必须周期性或于播放状态回调中校验。
     */
    @JvmStatic
    fun maybeRefreshIfNeeded(
        activity: ComponentActivity,
        current: MediaController?,
        kuwoSessionActive: Boolean,
        rebindMediaController: Runnable,
    ) {
        try {
            val sm = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager?
                ?: return
            val list = sm.getActiveSessions(
                ComponentName(activity, MiRootNotificationListenerService::class.java),
            )
            if (list.isNullOrEmpty()) return
            val preferred = preferredActiveController(list) ?: return
            val wantKuwo = shouldUseKuwoCarLyrics(preferred)
            if (wantKuwo != kuwoSessionActive) {
                rebindMediaController.run()
                return
            }
            if (!isSameMediaSession(current, preferred)) {
                LogHelper.d(
                    LOG_TAG,
                    "活跃播放会话变化: ${current?.packageName} -> ${preferred.packageName}，重新绑定 MediaController",
                )
                rebindMediaController.run()
            }
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            LogHelper.w(LOG_TAG, "maybeRefreshIfNeeded: " + e.message)
        }
    }
}
