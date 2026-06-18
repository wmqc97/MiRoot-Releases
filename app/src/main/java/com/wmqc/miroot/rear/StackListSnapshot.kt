package com.wmqc.miroot.rear

import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper

/**
 * 对 `am stack list` 的单次快照解析：一次 shell 调用，内存中多次查询，
 * 避免轮询周期内重复 spawn shell 进程。
 *
 * 典型用法：
 * ```
 * val snap = StackListSnapshot.fetch(taskService)
 * val tid = snap.findTaskIdForPackage("com.example", REAR_DISPLAY_ID)
 * val fg  = snap.foregroundComponentOnDisplay(REAR_DISPLAY_ID)
 * ```
 */
internal class StackListSnapshot private constructor(private val raw: String) {

    /** 按 display 分组的 taskId → component 映射（component 格式 `pkg/Activity`）。 */
    private val tasksByDisplay: Map<Int, List<TaskEntry>> = parse(raw)

    // ── 公开查询方法 ──────────────────────────────────────────────────

    /** 背屏栈顶组件，格式 `pkg/cls:taskId`；无匹配返回 null。 */
    fun foregroundComponentOnDisplay(displayId: Int): String? {
        val entry = tasksByDisplay[displayId]?.lastOrNull() ?: return null
        return "${entry.component}:${entry.taskId}"
    }

    /** 背屏栈顶应用，格式 `pkg:taskId`；无匹配返回 null。 */
    fun foregroundAppOnDisplay(displayId: Int): String? {
        val entry = tasksByDisplay[displayId]?.lastOrNull() ?: return null
        return "${entry.packageName}:${entry.taskId}"
    }

    /** 在指定 display 的栈中搜索包含 [activityName] 的 task，返回 taskId；未找到返回 -1。 */
    fun findTaskIdOnDisplay(activityName: String, displayId: Int): Int =
        tasksByDisplay[displayId]
            ?.lastOrNull { it.component.contains(activityName) }
            ?.taskId
            ?: -1

    /** 在所有 display 中搜索包含 [activityName] 的首个 taskId。 */
    fun findTaskIdGlobal(activityName: String): Int =
        tasksByDisplay.values
            .asSequence()
            .flatMap { it.asSequence() }
            .lastOrNull { it.component.contains(activityName) }
            ?.taskId
            ?: -1

    /** 指定包名在指定 display 上是否有 task 存在。 */
    fun isPackageOnDisplay(packageName: String, displayId: Int): Boolean =
        tasksByDisplay[displayId]?.any { it.packageName == packageName } == true

    /** taskId 是否存在于指定 display。 */
    fun isTaskOnDisplay(taskId: Int, displayId: Int): Boolean =
        tasksByDisplay[displayId]?.any { it.taskId == taskId } == true

    // ── 内部解析 ──────────────────────────────────────────────────────

    private data class TaskEntry(val taskId: Int, val component: String, val packageName: String)

    companion object {
        private const val TAG = "StackListSnapshot"

        /**
         * 执行一次 `am stack list` 并返回解析后的快照。
         * 失败时返回空快照（所有查询返回 null / -1 / false）。
         */
        @JvmStatic
        fun fetch(taskService: ITaskService): StackListSnapshot {
            val raw = try {
                taskService.executeShellCommandWithResult("am stack list")
            } catch (e: Exception) {
                LogHelper.w(TAG, "am stack list failed: ${e.message}")
                null
            }
            return StackListSnapshot(raw.orEmpty())
        }

        private fun parse(raw: String): Map<Int, List<TaskEntry>> {
            if (raw.isBlank()) return emptyMap()
            val result = mutableMapOf<Int, MutableList<TaskEntry>>()
            var currentDisplay = -1
            for (line in raw.lineSequence()) {
                if (line.startsWith("RootTask")) {
                    currentDisplay = parseDisplayId(line)
                    continue
                }
                if (currentDisplay < 0) continue
                if ("taskId=" !in line || "/" !in line) continue

                val tidStart = line.indexOf("taskId=") + 7
                val tidEnd = line.indexOf(':', tidStart)
                if (tidEnd <= tidStart) continue
                val taskId = line.substring(tidStart, tidEnd).trim().toIntOrNull() ?: continue

                val compStart = tidEnd + 2
                if (compStart >= line.length) continue
                // component 可能后跟空格/} 等
                var compEnd = compStart
                while (compEnd < line.length) {
                    val c = line[compEnd]
                    if (c == ' ' || c == '\t' || c == '}') break
                    compEnd++
                }
                val component = line.substring(compStart, compEnd).trim()
                if (component.isEmpty() || "/" !in component) continue

                val slashIdx = component.indexOf('/')
                val packageName = component.substring(0, slashIdx).trim()

                result.getOrPut(currentDisplay) { mutableListOf() }
                    .add(TaskEntry(taskId, component, packageName))
            }
            return result
        }

        private fun parseDisplayId(line: String): Int {
            // "RootTask id=42 type=standard displayId=1 ..."
            val regex = Regex("""displayId=(\d+)""")
            return regex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
    }
}
