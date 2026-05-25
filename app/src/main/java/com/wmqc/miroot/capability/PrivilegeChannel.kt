package com.wmqc.miroot.capability

/**
 * 权限策略：**Root 优先**；没有 Root 时，在 Shizuku 服务可用且已授权时使用 Shizuku。
 * 与 [com.wmqc.miroot.capability.PrivilegedShell]、[EnvironmentProbe.hasPrivilegedShellChannelSync] 一致。
 * 截图、录屏、背屏辅助等需执行 shell 的能力均走此判定。
 */
enum class PrivilegeChannel {
    ROOT,
    SHIZUKU,
    NONE,
}

data class PermissionSnapshot(
    val root: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
) {
    val shizukuReady: Boolean get() = shizukuRunning && shizukuGranted

    val channel: PrivilegeChannel
        get() = when {
            root -> PrivilegeChannel.ROOT
            shizukuReady -> PrivilegeChannel.SHIZUKU
            else -> PrivilegeChannel.NONE
        }

    val privileged: Boolean get() = channel != PrivilegeChannel.NONE

    companion object {
        fun initial() = PermissionSnapshot(
            root = false,
            shizukuRunning = false,
            shizukuGranted = false,
        )
    }
}
