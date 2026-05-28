package com.wmqc.miroot.capability

import rikka.shizuku.Shizuku
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * 每次优先 **`su -c`**（与 [EnvironmentProbe.probeRootSync] 的检测结果无关），启动失败再 **Shizuku**。
 *
 * 启动子进程会阻塞，须在后台线程调用。
 */
object PrivilegedShell {

    /** 统一命令执行入口：Root 优先，失败自动回退 Shizuku。 */
    @JvmStatic
    fun execCmd(cmd: String): Boolean = runAndWait(cmd)

    @Throws(IOException::class)
    @JvmStatic
    fun startShell(command: String, redirectErrorStream: Boolean = false): Process {
        val suFailure =
            try {
                val pb = ProcessBuilder("su", "-c", command).withPrivilegedSuPath()
                if (redirectErrorStream) {
                    pb.redirectErrorStream(true)
                }
                return pb.start()
            } catch (e: IOException) {
                e
            }
        if (!shizukuReady()) {
            throw suFailure
        }
        return try {
            val process = shizukuNewProcessReflect(command)
            if (redirectErrorStream) {
                drainErrorStreamAsync(process.errorStream)
            }
            process
        } catch (e: IOException) {
            suFailure.addSuppressed(e)
            throw suFailure
        }
    }

    /**
     * 仅使用 Root 通道执行（不回退 Shizuku）。
     * 仅用于确实必须 root-only 的场景，例如访问其它应用 /data/data 私有目录。
     */
    @Throws(IOException::class)
    @JvmStatic
    fun startRootShellOnly(command: String, redirectErrorStream: Boolean = false): Process {
        val pb = ProcessBuilder("su", "-c", command).withPrivilegedSuPath()
        if (redirectErrorStream) {
            pb.redirectErrorStream(true)
        }
        return pb.start()
    }

    private fun shizukuReady(): Boolean =
        EnvironmentProbe.shizukuServiceRunning() && EnvironmentProbe.shizukuPermissionGranted()

    /**
     * Shizuku API 13 中 [Shizuku.newProcess] 非 public，与旧工程一致用反射调用。
     */
    private fun shizukuNewProcessReflect(command: String): Process {
        try {
            val m: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            val proc = m.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null,
            )
            @Suppress("UNCHECKED_CAST")
            return proc as Process
        } catch (e: Exception) {
            throw IOException("Shizuku.newProcess failed", e)
        }
    }

    private fun drainErrorStreamAsync(errorStream: InputStream?) {
        if (errorStream == null) return
        Thread(
            {
                try {
                    errorStream.use { stream ->
                        val sink = java.io.OutputStream.nullOutputStream()
                        stream.copyTo(sink)
                    }
                } catch (_: Exception) {
                }
            },
            "miroot-shizuku-stderr",
        ).start()
    }

    /**
     * 执行命令并等待结束，返回是否 exit code 为 0。
     * Root（su -c）失败后自动回退 Shizuku，不再继续等待。
     */
    @JvmStatic
    fun runAndWait(command: String, redirectErrorStream: Boolean = true): Boolean {
        val proc = try {
            startShell(command, redirectErrorStream)
        } catch (_: Exception) {
            return shizukuFallbackOrNull(command, redirectErrorStream) != null
        }
        val ok = try {
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
        if (ok) return true
        // Root 失败 → 立即销毁进程，走 Shizuku
        try { proc.destroyForcibly() } catch (_: Exception) {}
        return shizukuFallbackOrNull(command, redirectErrorStream) != null
    }

    /**
     * 仅在 Shizuku 可用时通过 Shizuku 执行命令并返回 exit code == 0。
     * 适用于 Root 通道失败后的快速回退。
     */
    private fun shizukuFallbackOrNull(command: String, redirectErrorStream: Boolean): Boolean? {
        if (!shizukuReady()) return null
        return try {
            val p = shizukuNewProcessReflect(command)
            if (redirectErrorStream) drainErrorStreamAsync(p.errorStream)
            p.waitFor() == 0
        } catch (_: Exception) {
            null
        }
    }

    /** 读取合并后的 stdout（适合单条输出，如 pid、单行路径）。 */
    @JvmStatic
    fun captureOutput(command: String): String? {
        val proc = try {
            startShell(command, redirectErrorStream = true)
        } catch (_: Exception) {
            return shizukuCaptureFallback(command)
        }
        return try {
            val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val exitOk = proc.waitFor() == 0
            if (out.isNotEmpty() && exitOk) {
                out
            } else if (out.isNotEmpty()) {
                // exit != 0 但有输出 → 仍可用
                out
            } else {
                // 空输出 → 尝试 Shizuku
                shizukuCaptureFallback(command)
            }
        } catch (_: Exception) {
            shizukuCaptureFallback(command)
        }
    }

    private fun shizukuCaptureFallback(command: String): String? {
        if (!shizukuReady()) return null
        return try {
            shizukuNewProcessReflect(command).inputStream.bufferedReader().use { it.readText().trim() }.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
