package com.wmqc.miroot.ui.music

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.wmqc.miroot.charging.ChargingService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.PrivilegeBackend
import com.wmqc.miroot.rear.MiRootNotificationIds
import com.wmqc.miroot.rear.RearSwitchKeeperService
import com.wmqc.miroot.service.MiRootNotificationListenerService
import java.lang.reflect.Method

/**
 * 开启「自动投屏」后以前台服务形式监听媒体会话：正在播放则启动背屏歌词投屏，长时间非播放则结束投屏。
 * 通过延迟启动 / 延迟停止减少切歌、缓冲造成的频繁启停。
 */
class MusicAutoProjectionService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    /** 当前是否有活跃的媒体会话（由 onActiveSessionsChanged 回调维护）。 */
    private var hasActiveSessions = false

    private val pollRunnable =
        object : Runnable {
            override fun run() {
                applyPlaybackProbe()
                // 有活跃会话时高频轮询（POLL_MS），无活跃会话时低频兜底（POLL_IDLE_MS）
                // 主要依赖 onActiveSessionsChanged 回调驱动，轮询仅作兜底
                val interval = if (hasActiveSessions) pollIntervalMs() else POLL_IDLE_MS
                mainHandler.postDelayed(this, interval)
            }
        }
    private var sessionsListenerRegistered = false
    private var mediaSessionManager: MediaSessionManager? = null

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            mainHandler.post {
                hasActiveSessions = !controllers.isNullOrEmpty()
                rebuildControllers()
                applyPlaybackProbe()
                // 会话从无到有时，立即提升轮询频率
                if (hasActiveSessions) {
                    cancelPoll()
                    schedulePoll()
                }
            }
        }

    private val tracked = mutableListOf<MediaController>()
    private val playbackCallback: MediaController.Callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                mainHandler.post { applyPlaybackProbe() }
            }
        }

    private var pendingStart: Runnable? = null
    private var pendingStop: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureForeground()
        registerSessionsListenerOnce()
        rebuildControllers()
        applyPlaybackProbe()
        schedulePoll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (!LyricsSettingsRepository.load(applicationContext).autoProjection) {
            stopSelf()
            return START_NOT_STICKY
        }
        rebuildControllers()
        applyPlaybackProbe()
        return START_STICKY
    }

    private fun ensureForeground() {
        try {
            val n = RearSwitchKeeperService.createServiceNotification(this, true)
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    MiRootNotificationIds.MAIN_SERVICE_NOTIFICATION_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(MiRootNotificationIds.MAIN_SERVICE_NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            LogHelper.e(TAG, "ensureForeground failed", t)
            throw t
        }
    }

    private fun registerSessionsListenerOnce() {
        if (!MusicProjectionController.isNotificationListenerEnabled(this)) {
            LogHelper.wThrottled(
                TAG,
                "NotificationListener 未启用或被系统限制：跳过会话监听注册（请在权限页开启通知使用权，并检查 MIUI 自启动/后台限制）",
                THROTTLE_MISSING_NLS_MS,
            )
            return
        }
        if (sessionsListenerRegistered) return
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = msm
            val cn = ComponentName(this, MiRootNotificationListenerService::class.java)
            val handler = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 36) {
                msm.addOnActiveSessionsChangedListener(activeSessionsListener, cn, handler)
            } else {
                val m: Method =
                    MediaSessionManager::class.java.getMethod(
                        "addOnActiveSessionsChangedListener",
                        ComponentName::class.java,
                        MediaSessionManager.OnActiveSessionsChangedListener::class.java,
                        Handler::class.java,
                    )
                m.invoke(msm, cn, activeSessionsListener, handler)
            }
            sessionsListenerRegistered = true
        } catch (e: SecurityException) {
            LogHelper.wThrottled(
                TAG,
                "registerSessionsListener 被拒绝（通知使用权/MIUI 后台限制？）：${LogHelper.truncateForLog(e.toString(), 180)}",
                THROTTLE_MISSING_NLS_MS,
            )
        } catch (e: Exception) {
            LogHelper.wThrottled(
                TAG,
                "registerSessionsListener 失败：${LogHelper.truncateForLog(e.toString(), 180)}",
                THROTTLE_MISSING_NLS_MS,
            )
        }
    }

    private fun unregisterSessionsListener() {
        if (!sessionsListenerRegistered) return
        sessionsListenerRegistered = false
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {
        }
        mediaSessionManager = null
    }

    private fun rebuildControllers() {
        if (!MusicProjectionController.isNotificationListenerEnabled(this)) {
            return
        }
        for (c in tracked) {
            try {
                c.unregisterCallback(playbackCallback)
            } catch (_: Exception) {
            }
        }
        tracked.clear()
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(this, MiRootNotificationListenerService::class.java)
            val sessions = msm.getActiveSessions(cn)
            for (s in sessions) {
                val c = MediaController(this, s.sessionToken)
                c.registerCallback(playbackCallback)
                tracked.add(c)
            }
            hasActiveSessions = tracked.isNotEmpty()
        } catch (e: SecurityException) {
            LogHelper.wThrottled(
                TAG,
                "rebuildControllers 被拒绝（通知使用权/MIUI 后台限制？）：${LogHelper.truncateForLog(e.toString(), 180)}",
                THROTTLE_MISSING_NLS_MS,
            )
        }
    }

    private fun schedulePoll() {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun cancelPoll() {
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun clearPending() {
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
    }

    private fun applyPlaybackProbe() {
        if (!LyricsSettingsRepository.load(applicationContext).autoProjection) {
            stopSelf()
            return
        }
        if (!MusicProjectionController.isNotificationListenerEnabled(this)) {
            LogHelper.wThrottled(
                TAG,
                "NotificationListener 未启用或不可用：自动投屏暂停探测（请先开启通知使用权，并检查 MIUI 自启动/后台限制）",
                THROTTLE_MISSING_NLS_MS,
            )
            return
        }
        val playing = MusicProjectionController.hasAnyPlayingSession(this)
        if (playing == null) {
            return
        }
        if (playing) {
            onPlayingDebounced()
        } else {
            onNotPlayingDebounced()
        }
    }

    private fun onPlayingDebounced() {
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
        if (MusicProjectionController.isMusicProjectionUiActive()) {
            pendingStart?.let { mainHandler.removeCallbacks(it) }
            pendingStart = null
            return
        }
        if (pendingStart != null) return
        val r = Runnable {
            pendingStart = null
            if (!LyricsSettingsRepository.load(this).autoProjection) return@Runnable
            val still = MusicProjectionController.hasAnyPlayingSession(this)
            if (still != true) return@Runnable
            if (MusicProjectionController.isMusicProjectionUiActive()) return@Runnable
            if (!MusicProjectionController.isNotificationListenerEnabled(this)) return@Runnable
            PrivilegeBackend.refreshSync()
            if (!PrivilegeBackend.isPrivileged()) return@Runnable
            MusicProjectionController.start(this)
        }
        pendingStart = r
        mainHandler.postDelayed(r, DELAY_START_MS)
    }

    private fun onNotPlayingDebounced() {
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null
        if (!MusicProjectionController.isMusicProjectionUiActive()) {
            pendingStop?.let { mainHandler.removeCallbacks(it) }
            pendingStop = null
            return
        }
        if (pendingStop != null) return
        val r = Runnable {
            pendingStop = null
            if (!LyricsSettingsRepository.load(this).autoProjection) return@Runnable
            val still = MusicProjectionController.hasAnyPlayingSession(this)
            if (still == null) return@Runnable
            if (still) return@Runnable
            MusicProjectionController.stop(this)
        }
        pendingStop = r
        mainHandler.postDelayed(r, DELAY_STOP_MS)
    }

    override fun onDestroy() {
        clearPending()
        cancelPoll()
        unregisterSessionsListener()
        for (c in tracked) {
            try {
                c.unregisterCallback(playbackCallback)
            } catch (_: Exception) {
            }
        }
        tracked.clear()
        val chargingRunning = ChargingService.isInstanceRunning()
        super.onDestroy()
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        if (chargingRunning) {
            ChargingService.refreshMainForegroundNotificationIfRunning()
        }
    }

    companion object {
        private const val TAG = "MusicAutoProjectionSvc"

        /** 确认进入播放后再投屏，减轻切歌瞬间误判 */
        private const val DELAY_START_MS = 700L

        /** 停止播放后稍等再关投屏，覆盖切歌间隙、缓冲暂停 */
        private const val DELAY_STOP_MS = 3200L

        private const val POLL_MS = 3500L

        /** 无活跃媒体会话时的低频兜底轮询（主要依赖 onActiveSessionsChanged 回调驱动）。 */
        private const val POLL_IDLE_MS = 30_000L

        private const val POLL_MISSING_PERMISSION_MS = 30_000L
        private const val THROTTLE_MISSING_NLS_MS = 10 * 60 * 1000L
    }

    private fun pollIntervalMs(): Long =
        if (MusicProjectionController.isNotificationListenerEnabled(this)) POLL_MS else POLL_MISSING_PERMISSION_MS
}
