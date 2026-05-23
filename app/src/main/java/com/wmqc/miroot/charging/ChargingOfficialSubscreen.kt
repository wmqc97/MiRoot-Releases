package com.wmqc.miroot.charging

import android.content.Context
import android.os.RemoteException
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import kotlin.jvm.JvmStatic

/**
 * 对齐 3.4 [ChargingService]：启动充电动画前仅调用一次
 * {@link com.wmqc.miroot.lyrics.ITaskService#forceStopOfficialSubscreenForCharging}（等同 3.4 的
 * {@code disableSubScreenLauncher()}，{@code am force-stop com.xiaomi.subscreencenter}，不受功能页总开关约束）。
 */
object ChargingOfficialSubscreen {
    private const val TAG = "ChargingOfficialSubscreen"

    @Volatile
    private var disabledByCharging: Boolean = false

    /** 本次充电动画流程是否已 force-stop 官方背屏中心（与设置页「禁用」开关无关）。 */
    @Volatile
    private var forceStoppedOfficialForCharging: Boolean = false

    @JvmStatic
    fun applyDisableBeforeChargingFlow(@Suppress("UNUSED_PARAMETER") context: Context, taskService: ITaskService?) {
        if (taskService == null) {
            return
        }
        if (disabledByCharging) {
            LogHelper.d(TAG, "applyDisableBeforeChargingFlow: 已由充电动画禁用，跳过重复")
            return
        }
        try {
            val ok = runCatching { taskService.forceStopOfficialSubscreenForCharging() }.getOrDefault(false)
            if (ok) {
                disabledByCharging = true
                forceStoppedOfficialForCharging = true
                LogHelper.d(TAG, "applyDisableBeforeChargingFlow: 已禁用官方背屏（对齐 3.4 disableSubScreenLauncher）")
            }
        } catch (e: RemoteException) {
            LogHelper.w(TAG, "applyDisableBeforeChargingFlow: ${e.message}")
        }
    }

    /**
     * 充电动画结束后恢复官方背屏服务（若本次确实由充电动画禁用过）。
     *
     * 说明：enableSubScreenLauncher() 幂等，可多次调用；此处仍做标记保护，
     * 避免把其他链路（如用户主动禁用官方背屏）误恢复。
     */
    @JvmStatic
    fun restoreAfterChargingFlow(@Suppress("UNUSED_PARAMETER") context: Context, taskService: ITaskService?) {
        if (taskService == null) {
            disabledByCharging = false
            return
        }
        if (!disabledByCharging) {
            return
        }
        try {
            val ok = taskService.enableSubScreenLauncher()
            LogHelper.d(TAG, "restoreAfterChargingFlow: enableSubScreenLauncher ok=$ok")
        } catch (e: RemoteException) {
            LogHelper.w(TAG, "restoreAfterChargingFlow: ${e.message}")
        } finally {
            disabledByCharging = false
            forceStoppedOfficialForCharging = false
        }
    }

    /**
     * 充电动画结束且不会调用 [restoreAfterChargingFlow]（例如恢复音乐/车控投屏）时：
     * 官方进程曾被 force-stop，若不做任何恢复，副屏边缘返回往往要等系统自行拉起（约十秒级）。
     * 此处仅 `pm enable` 包与组件，**不** `am start` 官方 Launcher，避免打断即将由广播拉起的 MiRoot 背屏界面。
     */
    @JvmStatic
    fun reviveOfficialPackageAfterChargingWithoutLauncher(context: Context, taskService: ITaskService?) {
        if (!forceStoppedOfficialForCharging) {
            return
        }
        forceStoppedOfficialForCharging = false
        if (taskService == null) {
            return
        }
        try {
            val ok = taskService.enableOfficialSubscreenPackageOnly()
            LogHelper.d(TAG, "reviveOfficialPackageAfterChargingWithoutLauncher: ok=$ok")
        } catch (e: RemoteException) {
            LogHelper.w(TAG, "reviveOfficialPackageAfterChargingWithoutLauncher: ${e.message}")
        }
    }
}
