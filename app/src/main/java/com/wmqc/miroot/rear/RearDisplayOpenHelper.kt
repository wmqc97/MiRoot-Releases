package com.wmqc.miroot.rear

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper

/**
 * 「在背屏打开」类功能：启动前无条件尝试点亮背屏（KEYCODE_WAKEUP），
 * 与是否开启「投屏常亮」无关。
 */
object RearDisplayOpenHelper {

    const val REAR_DISPLAY_ID: Int = RearProjectionLaunchSequence.REAR_DISPLAY_ID

    private const val TAG = "RearDisplayOpen"
    private const val WAKE_POLL_ATTEMPTS = 15
    private const val WAKE_POLL_INTERVAL_MS = 80L
    private const val WAKE_SETTLE_CAP_MS = 150L

    @JvmStatic
    fun isRearDisplayLit(context: Context): Boolean {
        val rear = rearDisplay(context) ?: return false
        return rear.state == Display.STATE_ON || rear.state == Display.STATE_ON_SUSPEND
    }

    @JvmStatic
    fun rearDisplay(context: Context): Display? {
        val dm = context.applicationContext.getSystemService(DisplayManager::class.java) ?: return null
        return dm.getDisplay(REAR_DISPLAY_ID)
    }

    /** 向背屏发送 KEYCODE_WAKEUP（TaskService 优先，失败回退 PrivilegedShell）。 */
    @JvmStatic
    @JvmOverloads
    fun wakeRearDisplay(taskService: ITaskService? = null, displayId: Int = REAR_DISPLAY_ID): Boolean {
        val cmd = "input -d $displayId keyevent KEYCODE_WAKEUP"
        if (taskService != null) {
            try {
                taskService.executeShellCommand(cmd)
                LogHelper.d(TAG, "KEYCODE_WAKEUP via TaskService display=$displayId")
                return true
            } catch (e: Exception) {
                LogHelper.w(TAG, "TaskService wakeup failed: ${e.message}")
            }
        }
        return try {
            val ok = PrivilegedShell.execCmd(cmd)
            LogHelper.d(TAG, "KEYCODE_WAKEUP via shell ok=$ok display=$displayId")
            ok
        } catch (e: Exception) {
            LogHelper.w(TAG, "shell wakeup failed: ${e.message}")
            false
        }
    }

    /**
     * 点亮背屏并短暂等待；若仍未亮则重试唤醒。
     * @return 背屏 display 是否存在（不保证已亮，但已尽力唤醒）
     */
    @JvmStatic
    @JvmOverloads
    fun prepareRearDisplayForOpen(context: Context, taskService: ITaskService? = null): Boolean {
        val app = context.applicationContext
        if (rearDisplay(app) == null) {
            LogHelper.w(TAG, "rear display missing")
            return false
        }
        if (!isRearDisplayLit(app)) {
            wakeRearDisplay(taskService)
            RearProjectionLaunchWake.settleAfterWakeup(app, WAKE_SETTLE_CAP_MS)
        }
        repeat(WAKE_POLL_ATTEMPTS) { attempt ->
            if (isRearDisplayLit(app)) {
                return true
            }
            try {
                Thread.sleep(WAKE_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return rearDisplay(app) != null
            }
            if (attempt == 4 || attempt == 9) {
                wakeRearDisplay(taskService)
            }
        }
        if (!isRearDisplayLit(app)) {
            LogHelper.w(TAG, "rear display still not lit after wakeup retries")
        }
        return rearDisplay(app) != null
    }

    @JvmStatic
    fun startLaunchService(context: Context, serviceClass: Class<*>, action: String): Boolean {
        val app = context.applicationContext
        return try {
            app.startService(
                Intent(app, serviceClass).apply {
                    this.action = action
                },
            )
            true
        } catch (e: Exception) {
            LogHelper.e(TAG, "startLaunchService ${serviceClass.simpleName} failed", e)
            false
        }
    }

    @JvmStatic
    fun startActivityOnRearDisplay(context: Context, activityClass: Class<*>): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        prepareRearDisplayForOpen(app)
        val rear = rearDisplay(app) ?: return false
        return try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(rear.displayId)
            val intent =
                Intent(app, activityClass).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            app.startActivity(intent, opts.toBundle())
            LogHelper.d(TAG, "startActivityOnRear ${activityClass.simpleName} displayId=${rear.displayId}")
            true
        } catch (e: Exception) {
            LogHelper.w(TAG, "startActivityOnRear ${activityClass.simpleName} failed: ${e.message}")
            false
        }
    }
}
