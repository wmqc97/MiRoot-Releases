package com.wmqc.miroot.ui.music
import com.wmqc.miroot.display.MainDisplayUi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.LyricsIntents
import com.wmqc.miroot.lyrics.MusicProjectionService
import com.wmqc.miroot.lyrics.PrivilegeBackend
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity
import com.wmqc.miroot.service.MiRootNotificationListenerService
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

object MusicProjectionController {

    @JvmStatic
    @JvmOverloads
    fun start(context: Context, directRearOnly: Boolean = false) {
        PrivilegeBackend.refreshSync()
        if (!PrivilegeBackend.isPrivileged()) {
            MainDisplayUi.showToast(context, R.string.music_need_privilege, Toast.LENGTH_LONG)
            return
        }
        val i = Intent(context, MusicProjectionService::class.java)
        i.action = LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION
        i.putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP, LyricsIntents.VALUE_MUSIC_PROJECTION_OP_START)
        i.putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_DIRECT_REAR_ONLY, directRearOnly)
        context.startService(i)
    }

    fun stop(context: Context) {
        val i = Intent(context, MusicProjectionService::class.java)
        i.action = LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION
        i.putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP, LyricsIntents.VALUE_MUSIC_PROJECTION_OP_STOP)
        context.startService(i)
    }

    /**
     * 是否有任意媒体会话处于 [PlaybackState.STATE_PLAYING]。
     * 无通知使用权等导致无法枚举会话时返回 null（调用方据此停止投屏）。
     * 已加入 [MusicAutoProjectionPrefs] 黑名单的应用会被跳过。
     */
    fun hasAnyPlayingSession(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
        return try {
            val blacklist = MusicAutoProjectionPrefs.blacklist(context)
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(context, MiRootNotificationListenerService::class.java)
            val sessions = msm.getActiveSessions(cn)
            if (sessions.isEmpty()) return false
            for (s in sessions) {
                val c = MediaController(context, s.sessionToken)
                val pkg = c.packageName
                if (pkg in blacklist) continue
                val st = c.playbackState ?: continue
                if (st.state == PlaybackState.STATE_PLAYING) return true
            }
            false
        } catch (_: SecurityException) {
            null
        }
    }

    /** 背屏/主屏横屏歌词界面是否已显示歌词 UI（与 [com.wmqc.miroot.ui.music.MusicViewModel.refreshProjectionState] 一致）。*/
    fun isMusicProjectionUiActive(): Boolean {
        val act = RearScreenLyricsActivity.getCurrentInstance() ?: return false
        if (act.isFinishing) return false
        return runCatching { act.hasActiveLyricsUI() }.getOrDefault(false)
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(context.packageName)
    }

    fun openNotificationListenerSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}
