package com.wmqc.miroot.charging

import com.wmqc.miroot.capability.PrivilegedShell

/**
 * TaskService 不可用时尝试 Root/Shizuku 直跑 shell 命令。
 */
object ChargingRestoreFallback {

    @JvmStatic
    fun runPrivilegedShell(command: String): Boolean {
        return try {
            PrivilegedShell.execCmd(command)
        } catch (_: Exception) {
            false
        }
    }
}
