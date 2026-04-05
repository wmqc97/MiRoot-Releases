package com.wmqc.miroot.ui.music

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wmqc.miroot.lyrics.LyricsFontHelper
import com.wmqc.miroot.lyrics.PrivilegeBackend
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val _projecting = MutableStateFlow(false)
    val projecting: StateFlow<Boolean> = _projecting.asStateFlow()

    /** 与投屏控制区「是否检测到正在播放」同步；勿在 Compose 里直接读 [MusicProjectionController]，否则无状态驱动、界面不刷新 */
    private val _hasActiveMediaSession = MutableStateFlow(
        MusicProjectionController.hasActiveMediaSession(application.applicationContext),
    )
    val hasActiveMediaSession: StateFlow<Boolean> = _hasActiveMediaSession.asStateFlow()

    private val _notificationListenerEnabled = MutableStateFlow(
        MusicProjectionController.isNotificationListenerEnabled(application.applicationContext),
    )
    val notificationListenerEnabled: StateFlow<Boolean> = _notificationListenerEnabled.asStateFlow()

    private val _settings = MutableStateFlow(LyricsSettingsRepository.load(application))
    val settings: StateFlow<LyricsUiSettings> = _settings.asStateFlow()

    init {
        refreshProjectionState()
    }

    /** 刷新投屏控制条依赖的系统信号（媒体会话 + 通知监听开关） */
    fun refreshMusicControlProbe(context: Context) {
        val app = context.applicationContext
        _hasActiveMediaSession.value = MusicProjectionController.hasActiveMediaSession(app)
        _notificationListenerEnabled.value = MusicProjectionController.isNotificationListenerEnabled(app)
    }

    /**
     * 与 [com.wmqc.miroot.MainActivity.sendMusicProjectionStateBroadcast] 对应；
     * 以广播中的显式状态为准，避免仅根据 Activity 推断时与真实结束不同步。
     */
    fun applyProjectionBroadcast(running: Boolean) {
        _projecting.value = running
    }

    /** 根据歌词 Activity 实例校正状态（onResume 等时机）。 */
    fun refreshProjectionState() {
        val act = RearScreenLyricsActivity.getCurrentInstance()
        val on =
            act != null &&
                !act.isFinishing &&
                runCatching { act.hasActiveLyricsUI() }.getOrDefault(false)
        _projecting.update { on }
    }

    fun reloadSettings() {
        _settings.value = LyricsSettingsRepository.load(getApplication())
    }

    fun setSettings(s: LyricsUiSettings) {
        val fixed = s.normalizeAbyssalMutex()
        _settings.value = fixed
        LyricsSettingsRepository.save(getApplication(), fixed)
    }

    fun applyProjectionCustomFont(path: String) {
        setSettings(
            _settings.value.copy(
                projectionLyricsFont = LyricsFontHelper.ID_CUSTOM,
                projectionLyricsCustomPath = path,
            ),
        )
    }

    fun applyAbyssalCustomFont(path: String) {
        setSettings(
            _settings.value.copy(
                abyssalLyricsFont = LyricsFontHelper.ID_CUSTOM,
                abyssalLyricsCustomPath = path,
            ),
        )
    }

    fun refreshPrivilege() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            PrivilegeBackend.refreshSync()
        }
    }
}
