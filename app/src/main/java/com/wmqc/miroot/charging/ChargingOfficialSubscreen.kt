package com.wmqc.miroot.charging

import android.content.Context
import android.os.RemoteException
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import kotlin.jvm.JvmStatic

/**
 * 充电动画启动/恢复投屏前对官方背屏中心的处理：固定对官方背屏中心 force-stop（与设置页「禁用官方背屏服务」无关）。
 */
object ChargingOfficialSubscreen {
    private const val TAG = "ChargingOfficialSubscreen"

    @JvmStatic
    fun applyDisableBeforeChargingFlow(@Suppress("UNUSED_PARAMETER") context: Context, taskService: ITaskService?) {
        if (taskService == null) {
            return
        }
        try {
            taskService.forceStopOfficialSubscreenForCharging()
        } catch (e: RemoteException) {
            LogHelper.w(TAG, "applyDisableBeforeChargingFlow: ${e.message}")
        }
    }
}
