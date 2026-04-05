package com.wmqc.miroot.capability

import rikka.shizuku.Shizuku
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Method

/**
 * 每次优先 **`su -c`**（与 [EnvironmentProbe.probeRootSync] 的检测结果无关），启动失败再 **Shizuku**。
 *
 * 启动子进程会阻塞，须在后台线程调用。
 */
object PrivilegedShell {

    @Throws(IOException::class)
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

    /** 执行命令并等待结束，返回是否 exit code 为 0。 */
    fun runAndWait(command: String, redirectErrorStream: Boolean = true): Boolean =
        try {
            startShell(command, redirectErrorStream).waitFor() == 0
        } catch (_: Exception) {
            false
        }

    /** 读取合并后的 stdout（适合单条输出，如 pid、单行路径）。 */
    fun captureOutput(command: String): String? =
        try {
            startShell(command, redirectErrorStream = true)
                .inputStream.bufferedReader().use { it.readText().trim() }
                .ifEmpty { null }
        } catch (_: Exception) {
            null
        }
}
