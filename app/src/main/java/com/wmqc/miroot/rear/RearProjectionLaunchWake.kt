package com.wmqc.miroot.rear

import android.content.Context
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper

/**
 * MiRoot 自带投屏（音乐 / 车控 / 背屏桌面）任务启动阶段的背屏唤醒。
 *
 * 与功能页「投屏常亮」[RearAssistPrefs.isKeepScreenOnEnabled] 及「发送间隔」
 * [RearAssistPrefs.intervalMs] 一致（无额外上限）；常亮关闭时不发 KEYCODE_WAKEUP。
 * 迁屏时序见 [RearProjectionLaunchSequence]；持续周期唤醒仍由投屏 Activity 注册 [com.wmqc.miroot.lyrics.RearScreenWakeService] 负责。
 */
object RearProjectionLaunchWake {

    const val REAR_DISPLAY_ID: Int = 1

    private const val TAG = "RearProjLaunchWake"

    /**
     * 常亮开启时向背屏发送一次 KEYCODE_WAKEUP。
     * @return 是否已发送
     */
    @JvmStatic
    @JvmOverloads
    fun sendWakeupIfKeepScreenOn(
        context: Context,
        taskService: ITaskService,
        displayId: Int = REAR_DISPLAY_ID,
    ): Boolean {
        val app = context.applicationContext
        if (!RearAssistPrefs.isKeepScreenOnEnabled(app)) {
            LogHelper.d(TAG, "keep_screen_on off, skip KEYCODE_WAKEUP")
            return false
        }
        return try {
            taskService.executeShellCommand("input -d $displayId keyevent KEYCODE_WAKEUP")
            LogHelper.d(TAG, "KEYCODE_WAKEUP sent (display=$displayId)")
            true
        } catch (e: Exception) {
            LogHelper.w(TAG, "KEYCODE_WAKEUP failed: ${e.message}")
            false
        }
    }

    /** 唤醒后等待 [RearAssistPrefs.intervalMs]（与功能页「发送间隔」滑块一致，仅受滑块最小/最大约束）。 */
    @JvmStatic
    fun settleAfterWakeup(context: Context): Boolean = settleAfterWakeup(context, 0L)

    /**
     * @param capMs 大于 0 时取 min(intervalMs, capMs)，用于占位迁屏启动阶段，避免启动后再等一整段发送间隔。
     */
    @JvmStatic
    @JvmOverloads
    fun settleAfterWakeup(context: Context, capMs: Long): Boolean {
        var ms = RearAssistPrefs.intervalMs(context.applicationContext).toLong()
        if (capMs > 0L) {
            ms = minOf(ms, capMs)
        }
        if (ms <= 0L) return true
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 常亮开启：发送 KEYCODE_WAKEUP 并按功能页间隔短暂 settle。 */
    @JvmStatic
    @JvmOverloads
    fun wakeAndSettleIfEnabled(
        context: Context,
        taskService: ITaskService,
        displayId: Int = REAR_DISPLAY_ID,
    ): Boolean {
        if (!sendWakeupIfKeepScreenOn(context, taskService, displayId)) {
            return true
        }
        return settleAfterWakeup(context)
    }
}
