package com.wmqc.miroot.charging

import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PrivilegedShell

/**
 * TaskService 不可用时尝试 Root/Shizuku 直跑 shell 命令。
 */
object ChargingRestoreFallback {

    @JvmStatic
    fun runPrivilegedShell(command: String): Boolean {
        if (!EnvironmentProbe.hasPrivilegedShellChannelSync()) {
            return false
        }
        return try {
            PrivilegedShell.runAndWait(command)
        } catch (_: Exception) {
            false
        }
    }
}
