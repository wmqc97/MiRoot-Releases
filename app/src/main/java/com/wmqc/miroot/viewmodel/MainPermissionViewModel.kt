package com.wmqc.miroot.viewmodel

import com.wmqc.miroot.lyrics.LogHelper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PermissionSnapshot
import kotlinx.coroutines.launch

class MainPermissionViewModel : ViewModel() {

    private val _snapshot = MutableLiveData(PermissionSnapshot.initial())
    val snapshot: LiveData<PermissionSnapshot> = _snapshot

    fun refresh() {
        viewModelScope.launch {
            try {
                val root = EnvironmentProbe.probeRoot()
                val shizukuRunning = EnvironmentProbe.shizukuServiceRunning()
                val shizukuGranted = EnvironmentProbe.shizukuPermissionGranted()
                val xposed = EnvironmentProbe.xposedRuntimePresent()
                _snapshot.postValue(
                    PermissionSnapshot(
                        root = root,
                        shizukuRunning = shizukuRunning,
                        shizukuGranted = shizukuGranted,
                        xposed = xposed,
                    ),
                )
            } catch (t: Throwable) {
                LogHelper.e(TAG, "refresh failed", t)
                _snapshot.postValue(PermissionSnapshot.initial())
            }
        }
    }

    private companion object {
        private const val TAG = "MainPermissionVM"
    }
}
