package com.wmqc.miroot.viewmodel

import com.wmqc.miroot.lyrics.LogHelper
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PermissionSnapshot
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class MainPermissionViewModel(application: Application) : AndroidViewModel(application) {

    private val _snapshot = MutableLiveData(loadCachedSnapshot(application))
    val snapshot: LiveData<PermissionSnapshot> = _snapshot

    /** 递增世代号，避免多次 [refresh] 并发时较慢的旧探测覆盖已授权的新结果。 */
    @Volatile
    private var refreshEpoch = 0

    private var refreshJob: Job? = null

    fun refresh() {
        val epoch = ++refreshEpoch
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val prev = _snapshot.value ?: PermissionSnapshot.initial()
            try {
                val shizukuRunning = EnvironmentProbe.shizukuServiceRunning()
                val shizukuGranted = EnvironmentProbe.shizukuPermissionGranted()
                // Shizuku 为同步快检，先发布以免 Root 探测（最长约 12s）阻塞状态页刷新。
                publishIfCurrent(
                    epoch,
                    PermissionSnapshot(
                        root = prev.root,
                        shizukuRunning = shizukuRunning,
                        shizukuGranted = shizukuGranted,
                    ),
                )
                val root = EnvironmentProbe.probeRoot()
                publishIfCurrent(
                    epoch,
                    PermissionSnapshot(
                        root = root,
                        shizukuRunning = shizukuRunning,
                        shizukuGranted = shizukuGranted,
                    ),
                )
            } catch (t: Throwable) {
                if (epoch == refreshEpoch) {
                    LogHelper.e(TAG, "refresh failed", t)
                }
            }
        }
    }


    private suspend fun publishIfCurrent(epoch: Int, snap: PermissionSnapshot) {
        if (epoch != refreshEpoch) return
        coroutineContext.ensureActive()
        _snapshot.postValue(snap)
        saveCachedSnapshot(getApplication(), snap)
    }

    private companion object {
        private const val TAG = "MainPermissionVM"
        private const val PREFS_NAME = "miroot_perm_cache"
        private const val KEY_ROOT = "root"
        private const val KEY_SHIZUKU_RUNNING = "shizuku_running"
        private const val KEY_SHIZUKU_GRANTED = "shizuku_granted"

        private fun loadCachedSnapshot(context: Context): PermissionSnapshot {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return PermissionSnapshot(
                root = p.getBoolean(KEY_ROOT, false),
                shizukuRunning = p.getBoolean(KEY_SHIZUKU_RUNNING, false),
                shizukuGranted = p.getBoolean(KEY_SHIZUKU_GRANTED, false),
            )
        }

        private fun saveCachedSnapshot(context: Context, snap: PermissionSnapshot) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ROOT, snap.root)
                .putBoolean(KEY_SHIZUKU_RUNNING, snap.shizukuRunning)
                .putBoolean(KEY_SHIZUKU_GRANTED, snap.shizukuGranted)
                .apply()
        }
    }
}
