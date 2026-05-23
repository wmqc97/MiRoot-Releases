package com.wmqc.miroot.viewmodel

import com.wmqc.miroot.lyrics.LogHelper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PermissionSnapshot
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class MainPermissionViewModel : ViewModel() {

    private val _snapshot = MutableLiveData(PermissionSnapshot.initial())
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
                val xposed = EnvironmentProbe.xposedRuntimePresent()
                // Shizuku / Xposed 为同步快检，先发布以免 Root 探测（最长约 12s）阻塞状态页刷新。
                publishIfCurrent(
                    epoch,
                    PermissionSnapshot(
                        root = prev.root,
                        shizukuRunning = shizukuRunning,
                        shizukuGranted = shizukuGranted,
                        xposed = xposed,
                    ),
                )
                val root = EnvironmentProbe.probeRoot()
                publishIfCurrent(
                    epoch,
                    PermissionSnapshot(
                        root = root,
                        shizukuRunning = shizukuRunning,
                        shizukuGranted = shizukuGranted,
                        xposed = xposed,
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
    }

    private companion object {
        private const val TAG = "MainPermissionVM"
    }
}
