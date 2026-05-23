package com.wmqc.miroot.rear

import com.wmqc.miroot.capability.PrivilegedShell

/**
 * 用 `am stack list` 解析任务；迁屏顺序见 [moveTaskToDisplay]（含 Janus 的 `am display move-stack`）。
 */
object RearDisplayTaskCommands {

    /**
     * 与 [com.wmqc.miroot.charging.ChargingService] 一致：用 dumpsys 解析第二块物理屏的 displayId；
     * 失败时回退为 `1`（多数小米背屏为 1）。
     *
     * 须在具备特权 shell 的线程中调用（会阻塞）。
     */
    fun resolveRearDisplayId(): Int {
        val cmd =
            "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print \$2}'"
        val out = PrivilegedShell.captureOutput(cmd)?.trim().orEmpty()
        val token = out.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
        val id = token.toIntOrNull()
        return if (id != null && id > 0) id else 1
    }

    /**
     * @return `"packageName:taskId"` 或 null
     */
    fun foregroundOnDisplay(displayId: Int): String? {
        val out = PrivilegedShell.captureOutput("am stack list") ?: return null
        var inTarget = false
        for (raw in out.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("RootTask")) {
                inTarget = line.contains("displayId=$displayId")
                continue
            }
            if (inTarget && line.contains("taskId=") && line.contains("/")) {
                val tidStart = line.indexOf("taskId=") + 7
                val tidEnd = line.indexOf(':', tidStart)
                if (tidEnd <= tidStart) continue
                val taskId = line.substring(tidStart, tidEnd).trim()
                val pkgStart = tidEnd + 2
                val pkgEnd = line.indexOf('/', pkgStart)
                if (pkgEnd <= pkgStart) continue
                val packageName = line.substring(pkgStart, pkgEnd).trim()
                return "$packageName:$taskId"
            }
        }
        return null
    }

    fun parsePackageTaskId(spec: String): Pair<String, Int>? {
        val idx = spec.lastIndexOf(':')
        if (idx <= 0 || idx >= spec.length - 1) return null
        val pkg = spec.substring(0, idx).trim()
        val tid = spec.substring(idx + 1).trim().toIntOrNull() ?: return null
        if (pkg.isEmpty()) return null
        return pkg to tid
    }

    /**
     * 从 `am stack list` 全文里找**同时**含 `taskId=` 与 [lineMustContain] 的行（类同 `findstr`），解析 taskId。
     * 例如 lineMustContain 为 `cn.kuwo.kwmusiccar` 或 `RearScreenTestActivity`。
     */
    fun parseTaskIdFromStackListLineContaining(stackList: String, lineMustContain: String): Int? {
        val taskIdRegex = Regex("""taskId=(\d+)""")
        for (raw in stackList.lineSequence()) {
            val line = raw.trim()
            if (!line.contains("taskId=")) continue
            if (!line.contains(lineMustContain)) continue
            taskIdRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 迁屏顺序与 [Janus DisplayUtils](https://github.com/penguinyzsh/janus) 及既有逻辑合并：
     * 1. `am task move-task-to-display`（通用）
     * 2. `am display move-stack`（Janus 在小米背屏上使用的路径）
     * 3. `service call activity_task 50`（binder 回退）
     */
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        if (PrivilegedShell.execCmd("am task move-task-to-display $taskId $displayId")) {
            return true
        }
        if (PrivilegedShell.execCmd("am display move-stack $taskId $displayId")) {
            return true
        }
        val cmd = "service call activity_task 50 i32 $taskId i32 $displayId"
        return PrivilegedShell.execCmd(cmd)
    }
}
