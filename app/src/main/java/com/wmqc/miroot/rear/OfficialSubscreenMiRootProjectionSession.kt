package com.wmqc.miroot.rear

import android.content.Context
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper

/**
 * 音乐投屏 / 车控投屏 / 背屏桌面 共用：与功能页「禁用官方背屏服务」总开关 ([OfficialSubscreenServiceGate]) 对齐。
 *
 * - **单次禁用**： refcount 从 0→1 且总开关开启时，仅调用一次 [ITaskService.disableSubScreenLauncher]。
 * - **单次恢复**： refcount 回到 0 且本次栈曾执行过禁用时，仅调用一次官方恢复（与 [com.wmqc.miroot.lyrics.TaskService.enableSubScreenLauncher] 一致）。
 *
 * 嵌套进入（例如多条链路重叠）通过引用计数合并为一对禁用/恢复，避免重复 force-stop / enable 导致手势异常。
 */
object OfficialSubscreenMiRootProjectionSession {

    private const val TAG = "OfficialSubscreenProjSess"

    private val lock = Any()
    private var refCount = 0
    private var suppressedOfficialForActiveStack = false

    @JvmStatic
    fun acquire(appContext: Context, taskService: ITaskService?) {
        if (taskService == null) {
            LogHelper.w(TAG, "acquire: taskService=null, skip")
            return
        }
        val app = appContext.applicationContext
        synchronized(lock) {
            refCount++
            val gateOn = OfficialSubscreenServiceGate.isDisableEnabled(app)
            if (gateOn && refCount == 1) {
                try {
                    taskService.disableSubScreenLauncher()
                    suppressedOfficialForActiveStack = true
                    LogHelper.d(TAG, "acquire: disableSubScreenLauncher (ref=$refCount)")
                } catch (e: Exception) {
                    suppressedOfficialForActiveStack = false
                    LogHelper.w(TAG, "acquire: disable failed: ${e.message}")
                }
            } else if (!gateOn) {
                LogHelper.d(TAG, "acquire: gate off (ref=$refCount)")
            }
        }
    }

    @JvmStatic
    fun release(appContext: Context, taskService: ITaskService?) {
        val app = appContext.applicationContext
        val shouldRestore: Boolean
        synchronized(lock) {
            if (refCount <= 0) {
                LogHelper.w(TAG, "release: refCount already 0")
                return
            }
            refCount--
            shouldRestore = refCount == 0 && suppressedOfficialForActiveStack
            if (!shouldRestore) {
                LogHelper.d(TAG, "release: ref=$refCount")
                return
            }
            suppressedOfficialForActiveStack = false
        }
        restoreOfficialSubscreen(taskService)
    }

    private fun restoreOfficialSubscreen(taskService: ITaskService?) {
        try {
            if (taskService != null) {
                taskService.enableSubScreenLauncher()
                LogHelper.d(TAG, "restore: enableSubScreenLauncher via TaskService")
                return
            }
            val cmd =
                "pm enable com.xiaomi.subscreencenter; " +
                    "pm enable com.xiaomi.subscreencenter/.SubScreenLauncher; " +
                    "am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher"
            val main = MainActivity.getCurrentInstance()
            if (main != null) {
                main.executeShellCommand(cmd)
                LogHelper.d(TAG, "restore: shell via MainActivity")
            } else {
                Thread({ PrivilegedShell.execCmd(cmd) }, "miroot-official-restore").start()
                LogHelper.d(TAG, "restore: shell via PrivilegedShell")
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "restore failed: ${e.message}")
        }
    }
}
