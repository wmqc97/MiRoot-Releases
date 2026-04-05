package com.wmqc.miroot.ui.music

import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.PrivilegeBackend
import com.wmqc.miroot.service.MiRootNotificationListenerService
import java.lang.reflect.Method

/**
 * 开启「自动投屏」后以前台服务形式监听媒体会话：正在播放则启动背屏歌词投屏，长时间非播放则结束投屏。
 * 通过延迟启动 / 延迟停止减少切歌、缓冲造成的频繁启停。
 */
class MusicAutoProjectionService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                applyPlaybackProbe()
                mainHandler.postDelayed(this, POLL_MS)
            }
        }
    private var sessionsListenerRegistered = false
    private var mediaSessionManager: MediaSessionManager? = null

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener {
            mainHandler.post {
                rebuildControllers()
                applyPlaybackProbe()
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
            createChannel()
            val n =
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getString(R.string.music_auto_projection_notif_title))
                    .setContentText(getString(R.string.music_auto_projection_notif_text))
                    .setOngoing(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build()
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            LogHelper.e(TAG, "ensureForeground failed", t)
            throw t
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_auto_projection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        ch.setShowBadge(false)
        ch.enableLights(false)
        ch.enableVibration(false)
        ch.setSound(null, null)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun registerSessionsListenerOnce() {
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
        } catch (e: Exception) {
            LogHelper.w(TAG, "registerSessionsListener: ${e.message}")
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
        } catch (_: SecurityException) {
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
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MusicAutoProjectionSvc"
        private const val NOTIF_ID = 10047
        private const val CHANNEL_ID = "miroot_music_auto_projection"

        /** 确认进入播放后再投屏，减轻切歌瞬间误判 */
        private const val DELAY_START_MS = 700L

        /** 停止播放后稍等再关投屏，覆盖切歌间隙、缓冲暂停 */
        private const val DELAY_STOP_MS = 3200L

        private const val POLL_MS = 3500L
    }
}
