package com.wmqc.miroot.capability

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.ProcessBuilder
import java.util.concurrent.TimeUnit

/**
 * 与 [PrivilegedShell.startShell] 一致：每次执行时先尝试 `su -c`，失败再用 Shizuku。
 * 背屏录屏仅在此二者就绪时可启动；内录策略按通道分流（见 [privilegedShellRouteSync]）。
 */
enum class PrivilegedShellRoute {
    NONE,
    /** `su -c`，可配合 `pm grant` 等 */
    ROOT,
    /** Shizuku 远程 `sh -c` */
    SHIZUKU,
}

/**
 * 运行环境探测：Root、Shizuku、Xposed/LSPosed。
 * 后续背屏相关指令可在此集中选择可用的执行通道。
 */
object EnvironmentProbe {

    private const val PROP_MI_OS_INCREMENTAL = "ro.mi.os.version.incremental"

    /** Root 弹窗或 ksud 较慢时略长于 5s；仍设上限以免永久阻塞。 */
    private const val SU_PROBE_TIMEOUT_SEC = 12L

    /**
     * Xiaomi HyperOS（小米澎湃 OS）完整版本号（如 OS3.0.41.0.UNCCNXM），非小米或无法读取时返回 null。
     */
    fun miOsVersionIncremental(): String? = readSystemProperty(PROP_MI_OS_INCREMENTAL)

    fun readSystemProperty(key: String): String? {
        val commands = listOf(
            arrayOf("/system/bin/getprop", key),
            arrayOf("getprop", key),
        )
        for (cmd in commands) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                val finished = p.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    continue
                }
                val text = p.inputStream.bufferedReader().use { it.readText() }.trim()
                if (text.isNotEmpty() && text != "null") return text
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * 同步检测 Root（会阻塞）。仅供后台线程或 [PrivilegedShell] 使用，勿在主线程调用。
     *
     * 与 [PrivilegedShell.startShell] 一致使用 `ProcessBuilder("su", "-c", …)`，避免 `sh -c "su -c …"`
     * 在部分 Magisk / KernelSU 环境下与直接 `su` 行为不一致；并合并 stderr，防止 `id` 输出落在错误流。
     *
     * 仅以 `su -c id` 的 **进程 exit code == 0** 为成功（不依赖解析 stdout），
     * 部分机型上 `id` 输出格式或缓冲与「必须含 uid=0」组合会误判；此处对齐该策略并保留输出校验作补充。
     *
     * **SukiSU Ultra / KernelSU**：真实 `su` 往往在 `/data/adb/ksu/bin`，应用进程默认 PATH 常不含该目录；
     * ksud 仅在 argv[0] 为 `su` 或 `/system/bin/su` 时进入 root（见 KernelSU `ksud` cli）。
     */
    fun probeRootSync(): Boolean {
        val suArgv0 = listOf("su", "/system/bin/su", "/system/xbin/su")
        // 1) 不修改 PATH（部分环境下与系统默认行为一致）
        for (argv0 in suArgv0) {
            if (tryProbeSu(arrayOf(argv0, "-c", "id"), enrichPath = false)) return true
        }
        // 2) 带 ksu/magisk PATH + 备用 echo（stdout 异常时 exit 仍可能为 0）
        for (argv0 in suArgv0) {
            if (tryProbeSu(arrayOf(argv0, "-c", "id"), enrichPath = true)) return true
            if (tryProbeSu(arrayOf(argv0, "-c", "echo MIROOT_SU_OK"), enrichPath = true)) return true
        }
        return false
    }

    private fun tryProbeSu(argv: Array<String>, enrichPath: Boolean): Boolean {
        return try {
            val pb = if (enrichPath) {
                ProcessBuilder(*argv).withPrivilegedSuPath()
            } else {
                ProcessBuilder(*argv)
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            if (!p.waitFor(SU_PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return false
            }
            val text = p.inputStream.bufferedReader().use { it.readText() }
            val remote = argv[argv.size - 1]
            val exit = p.exitValue()
            when (remote) {
                "id" -> exit == 0 || text.contains("uid=0")
                else -> text.contains("MIROOT_SU_OK")
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun probeRoot(): Boolean = withContext(Dispatchers.IO) {
        probeRootSync()
    }

    fun shizukuServiceRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun shizukuPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * 是否与 [PrivilegedShell]、[PermissionSnapshot.privileged] 使用同一套策略：
     * **Root 优先**；无 Root 时，Shizuku 服务在且已授权则视为可用。
     *
     * 内含 [probeRootSync]，可能阻塞数秒，仅用于磁贴、服务启动闸等可接受延迟的场景；
     * 功能页可改用 [PermissionSnapshot.privileged]（异步探测）避免阻塞主线程。
     */
    fun hasPrivilegedShellChannelSync(): Boolean =
        probeRootSync() || (shizukuServiceRunning() && shizukuPermissionGranted())

    /**
     * 当前特权 shell 走 Root 还是 Shizuku（与 [PrivilegedShell] 选路一致）。
     * 内含 [probeRootSync]，可能阻塞数秒，**勿在主线程调用**。
     */
    fun privilegedShellRouteSync(): PrivilegedShellRoute = when {
        probeRootSync() -> PrivilegedShellRoute.ROOT
        shizukuServiceRunning() && shizukuPermissionGranted() -> PrivilegedShellRoute.SHIZUKU
        else -> PrivilegedShellRoute.NONE
    }

    /**
     * 检测 Xposed 运行时（含 LSPosed 等），不引入 compileOnly 依赖，避免非 Xposed 环境链接失败。
     */
    fun xposedRuntimePresent(): Boolean = try {
        val c = Class.forName("de.robv.android.xposed.XposedBridge")
        val v = c.getMethod("getXposedVersion").invoke(null) as Int
        v > 0
    } catch (_: Throwable) {
        false
    }
}

/**
 * 为 `su` 子进程补齐 KernelSU / SukiSU Ultra、Magisk 常见 bin 目录，使 argv[0]=`su` 时能解析到 ksud。
 */
internal fun ProcessBuilder.withPrivilegedSuPath(): ProcessBuilder {
    val env = environment()
    val path = env["PATH"] ?: "/system/bin:/system/xbin:/vendor/bin:/product/bin"
    env["PATH"] = "/data/adb/ksu/bin:/data/adb/magisk:$path"
    return this
}
