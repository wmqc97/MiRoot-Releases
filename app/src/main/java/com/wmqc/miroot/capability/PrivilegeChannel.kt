package com.wmqc.miroot.capability

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 权限策略：**Root 优先**；没有 Root 时，在 Shizuku 服务可用且已授权时使用 Shizuku。
 * 与 [PrivilegedShell]、[EnvironmentProbe.hasPrivilegedShellChannelSync] 一致。
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

/**
 * 权限缓存单例：App 启动时异步探测一次，后续所有调用方直接读缓存，避免重复 `su` 探测卡主线程。
 *
 * - Shizuku 状态变化（断开/重连）通过 [onShizukuBinderDead] / [refresh] 更新缓存。
 * - Root 仅在 Shizuku 不可用时才探测（一个后台线程，启动后 ~12s 内完成）。
 * - 录屏、截图、QS磁贴等无需再阻塞等待 Root 探测。
 */
object PermissionCache {

    @Volatile
    private var _snapshot: PermissionSnapshot = PermissionSnapshot.initial()

    val snapshot: PermissionSnapshot get() = _snapshot
    val privileged: Boolean get() = _snapshot.privileged
    val channel: PrivilegeChannel get() = _snapshot.channel
    val rootReady: Boolean get() = _snapshot.root
    val shizukuReady: Boolean get() = _snapshot.shizukuReady

    /** 在后台线程刷新，不阻塞调用方。 */
    fun refresh() {
        Thread {
            refreshSnapshot()
        }.apply { name = "permission-cache" }.start()
    }

    /** 同步刷新（仅在后台线程调用）。 */
    private fun refreshSnapshot() {
        // Phase 1: Shizuku（非阻塞，毫秒级）
        val shizukuRunning = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) { false }
        val shizukuGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        _snapshot = PermissionSnapshot(
            root = _snapshot.root,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
        )
        // Phase 2: Root（仅在 Shizuku 不可用时探测）
        if (!shizukuReady) {
            val root = EnvironmentProbe.probeRootSync()
            _snapshot = PermissionSnapshot(
                root = root,
                shizukuRunning = shizukuRunning,
                shizukuGranted = shizukuGranted,
            )
        }
    }

    /** Shizuku Binder 断开时标记为不可用。 */
    fun onShizukuBinderDead() {
        _snapshot = _snapshot.copy(shizukuRunning = false, shizukuGranted = false)
    }

    /** Shizuku Binder 重连时刷新。 */
    fun onShizukuBinderReceived() {
        refresh()
    }
}
