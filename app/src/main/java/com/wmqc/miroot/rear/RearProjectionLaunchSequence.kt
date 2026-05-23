package com.wmqc.miroot.rear

import android.content.Context
import com.wmqc.miroot.charging.ChargingOfficialSubscreen
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import java.util.regex.Pattern

/** 迁背屏成功后如何禁用官方背屏服务 */
enum class OfficialDisableAfterLaunch {
    /** 音乐 / 车控 / 桌面： [OfficialSubscreenMiRootProjectionSession] */
    MIROOT_PROJECTION_SESSION,
    /** 充电动画：force-stop，不受功能页总开关约束 */
    CHARGING_SUBSCREEN,
}

/**
 * 背屏 Activity 启动参数。
 * - 优先 [directRearCmd]（`am start --display N`），须背屏前台验证成功才算直启成功
 * - 失败则 [mainPlaceholderCmd] 主屏占位 + 迁屏（对齐 MiRoot-3.4：`service call activity_task 50`）
 *
 * 全部经 [ITaskService.executeShellCommand] / [ITaskService.moveTaskToDisplay] 在 Shizuku 进程执行。
 */
data class RearActivityLaunchSpec @JvmOverloads constructor(
    val grepActivityName: String,
    val componentName: String,
    val mainPlaceholderCmd: String,
    val directRearCmd: String,
    val isBroadcastExtra: Boolean = false,
    val rearDisplayId: Int = RearProjectionLaunchSequence.REAR_DISPLAY_ID,
    val disableMode: OfficialDisableAfterLaunch = OfficialDisableAfterLaunch.MIROOT_PROJECTION_SESSION,
    /** true：充电动画等始终 KEYCODE_WAKEUP；false：跟随「投屏常亮」与发送间隔 */
    val alwaysSendWakeup: Boolean = false,
)

/** 直启或占位迁屏完成并禁用官方背屏后的回调（可选） */
fun interface OnRearLaunchFinalizedListener {
    /**
     * @param usedPlaceholderFallback true 表示走了主屏占位迁屏（3.4 方案）
     * @param taskId 占位迁屏成功时的 taskId；直启成功时为 null
     */
    fun onRearLaunchFinalized(
        rearDisplayId: Int,
        usedPlaceholderFallback: Boolean,
        taskId: String?,
    )
}

/**
 * 充电动画 / 音乐 / 车控 / 背屏桌面：优先背屏直启（验证落屏）→ 失败则主屏占位迁屏（3.4）。
 */
object RearProjectionLaunchSequence {

    const val REAR_DISPLAY_ID: Int = 1

    private const val TAG = "RearProjLaunchSeq"
    /** 主屏占位：首帧立即 grep，之后短间隔轮询（3.4 为 60×30ms，偏慢） */
    private const val POLL_ATTEMPTS = 45
    private const val POLL_INTERVAL_FAST_MS = 12L
    private const val POLL_RETRY_AT_1 = 10
    private const val POLL_RETRY_AT_2 = 22
    private const val POLL_RETRY_AT_3 = 34
    private const val DIRECT_LAUNCH_RETRY_GAP_MS = 200L
    /** 直启失败判定：尽快回退占位，避免空等 ~1.2s */
    private const val DIRECT_VERIFY_ATTEMPTS = 14
    private const val DIRECT_VERIFY_INTERVAL_MS = 20L
    /** 占位迁屏：短确认轮询，未通过再 moveTaskToDisplay */
    private const val CONFIRM_REAR_MOVE_ATTEMPTS = 5
    private const val CONFIRM_REAR_MOVE_STEP_MS = 12L
    /** 占位迁屏成功后 postLaunch 唤醒 settle 上限（完整 interval 留给持续常亮服务） */
    private const val PLACEHOLDER_POST_WAKE_SETTLE_CAP_MS = 120L
    /** 直启优先流程：迁屏前唤醒 settle 上限，避免直启失败后再多等一整段发送间隔 */
    private const val PRE_WAKE_SETTLE_CAP_MS = 150L
    /** 3.4 充电动画迁屏后 sleep */
    private const val PLACEHOLDER_MOVE_SETTLE_CHARGING_MS = 40L
    /** 3.4 音乐/车控：100ms；占位链路用 40ms + 快速确认以尽快出画 */
    private const val PLACEHOLDER_MOVE_SETTLE_PROJECTION_MS = 40L
    const val MOVE_SETTLE_MS: Long = PLACEHOLDER_MOVE_SETTLE_CHARGING_MS

    private val TASK_ID_PATTERN = Pattern.compile("taskId=(\\d+)")

    /**
     * 优先背屏直启（须验证 Activity 已在背屏），失败再主屏占位 + 3.4 迁屏。
     */
    @JvmStatic
    fun runPreferDirectRearLaunchWithPlaceholderFallback(
        context: Context,
        taskService: ITaskService,
        spec: RearActivityLaunchSpec,
        afterFinalized: OnRearLaunchFinalizedListener? = null,
    ): Boolean {
        val app = context.applicationContext
        val displayId = spec.rearDisplayId

        if (!preLaunchWakeup(app, taskService, displayId, spec.alwaysSendWakeup, PRE_WAKE_SETTLE_CAP_MS)) {
            return false
        }

        if (spec.directRearCmd.isNotBlank() &&
            tryDirectRearLaunchVerified(taskService, spec, app, displayId)
        ) {
            if (!sleepQuiet(MOVE_SETTLE_MS)) {
                return false
            }
            if (!finalizeOnRear(app, taskService, displayId, spec)) {
                return false
            }
            LogHelper.d(TAG, "背屏直启已验证: ${spec.grepActivityName}")
            afterFinalized?.onRearLaunchFinalized(displayId, false, null)
            return true
        }

        LogHelper.w(TAG, "背屏直启未验证成功，回退主屏占位(3.4): ${spec.grepActivityName}")
        val taskId =
            runMainPlaceholderMoveInternal(
                app,
                taskService,
                spec,
                skipPreWake = true,
            )
        if (taskId == null) {
            return false
        }
        afterFinalized?.onRearLaunchFinalized(displayId, true, taskId)
        return true
    }

    /**
     * 仅背屏直启（须验证落屏），失败不回退主屏占位。
     * 供音乐设置页「开始投屏」等明确走 `am start --display N` 的入口。
     */
    @JvmStatic
    fun runDirectRearLaunchOnly(
        context: Context,
        taskService: ITaskService,
        spec: RearActivityLaunchSpec,
        afterFinalized: OnRearLaunchFinalizedListener? = null,
    ): Boolean {
        val app = context.applicationContext
        val displayId = spec.rearDisplayId
        if (spec.directRearCmd.isBlank()) {
            LogHelper.e(TAG, "仅背屏直启缺少 directRearCmd: ${spec.grepActivityName}")
            return false
        }
        if (!preLaunchWakeup(app, taskService, displayId, spec.alwaysSendWakeup)) {
            return false
        }
        if (!tryDirectRearLaunchVerified(taskService, spec, app, displayId)) {
            LogHelper.e(TAG, "仅背屏直启失败（未回退占位迁屏）: ${spec.grepActivityName}")
            return false
        }
        if (!sleepQuiet(MOVE_SETTLE_MS)) {
            return false
        }
        if (!finalizeOnRear(app, taskService, displayId, spec)) {
            return false
        }
        LogHelper.d(TAG, "仅背屏直启成功: ${spec.grepActivityName}")
        afterFinalized?.onRearLaunchFinalized(displayId, false, null)
        return true
    }

    /** 仅主屏占位迁屏（3.4），不尝试背屏直启。 */
    @JvmStatic
    fun runMainPlaceholderThenMoveToRear(
        context: Context,
        taskService: ITaskService,
        spec: RearActivityLaunchSpec,
        afterFinalized: OnRearLaunchFinalizedListener? = null,
    ): Boolean {
        val app = context.applicationContext
        val displayId = spec.rearDisplayId
        val taskId = runMainPlaceholderMoveInternal(app, taskService, spec, skipPreWake = false)
        if (taskId == null) {
            return false
        }
        afterFinalized?.onRearLaunchFinalized(displayId, true, taskId)
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun runDirectRearDisplayLaunchFlow(
        context: Context,
        taskService: ITaskService,
        componentName: String,
        isBroadcastExtra: Boolean = false,
        rearDisplayId: Int = REAR_DISPLAY_ID,
    ): Boolean {
        val spec =
            mirootProjectionLaunchSpec(
                componentName.substringAfterLast('/'),
                componentName,
                isBroadcastExtra,
                rearDisplayId,
            )
        return runPreferDirectRearLaunchWithPlaceholderFallback(context, taskService, spec, null)
    }

    @JvmStatic
    @JvmOverloads
    fun runChargingAlignedMainPlaceholderFlow(
        context: Context,
        taskService: ITaskService,
        grepActivityName: String,
        mainCmd: String,
        rearDisplayId: Int = REAR_DISPLAY_ID,
    ): String? {
        val component = mainCmd.substringAfter("-n ").substringBefore(' ')
        val spec =
            RearActivityLaunchSpec(
                grepActivityName = grepActivityName,
                componentName = component,
                mainPlaceholderCmd = mainCmd,
                directRearCmd = buildRearStartCmd(component, rearDisplayId, false),
                rearDisplayId = rearDisplayId,
                disableMode = OfficialDisableAfterLaunch.CHARGING_SUBSCREEN,
                alwaysSendWakeup = true,
            )
        return if (runPreferDirectRearLaunchWithPlaceholderFallback(context, taskService, spec, null)) {
            "0"
        } else {
            null
        }
    }

    @JvmStatic
    @JvmOverloads
    fun mirootProjectionLaunchSpec(
        grepActivityName: String,
        componentName: String,
        isBroadcastExtra: Boolean = false,
        rearDisplayId: Int = REAR_DISPLAY_ID,
    ): RearActivityLaunchSpec {
        val mainPlaceholderCmd =
            if (isBroadcastExtra) {
                "am start -n $componentName --ez isBroadcast true"
            } else {
                "am start -n $componentName"
            }
        return RearActivityLaunchSpec(
            grepActivityName = grepActivityName,
            componentName = componentName,
            mainPlaceholderCmd = mainPlaceholderCmd,
            directRearCmd = buildRearStartCmd(componentName, rearDisplayId, isBroadcastExtra),
            isBroadcastExtra = isBroadcastExtra,
            rearDisplayId = rearDisplayId,
            disableMode = OfficialDisableAfterLaunch.MIROOT_PROJECTION_SESSION,
            alwaysSendWakeup = false,
        )
    }

    @JvmStatic
    fun buildRearStartCmd(
        componentName: String,
        rearDisplayId: Int,
        isBroadcastExtra: Boolean,
    ): String =
        if (isBroadcastExtra) {
            "am start --display $rearDisplayId -n $componentName --ez isBroadcast true"
        } else {
            "am start --display $rearDisplayId -n $componentName"
        }

    /**
     * 背屏直启：执行 [directRearCmd] 并轮询验证目标 Activity 已在背屏（避免 shell 返回成功但未落屏）。
     */
    private fun tryDirectRearLaunchVerified(
        taskService: ITaskService,
        spec: RearActivityLaunchSpec,
        app: Context,
        rearDisplayId: Int,
    ): Boolean {
        LogHelper.d(TAG, "背屏直启: ${LogHelper.truncateForLog(spec.directRearCmd, 120)}")

        fun execOnce(): Boolean =
            try {
                taskService.executeShellCommand(spec.directRearCmd)
            } catch (e: Exception) {
                LogHelper.w(TAG, "背屏直启 shell 失败: ${e.message}")
                false
            }

        if (!execOnce()) {
            if (!sleepQuiet(DIRECT_LAUNCH_RETRY_GAP_MS)) {
                return false
            }
            if (!preLaunchWakeup(app, taskService, rearDisplayId, spec.alwaysSendWakeup)) {
                return false
            }
            if (!execOnce()) {
                return false
            }
        }

        for (attempt in 0 until DIRECT_VERIFY_ATTEMPTS) {
            if (attempt > 0 && !sleepQuiet(DIRECT_VERIFY_INTERVAL_MS)) {
                return false
            }
            if (isTargetActivityOnRearDisplay(taskService, spec, rearDisplayId)) {
                LogHelper.d(TAG, "背屏直启验证通过 (attempt=${attempt + 1})")
                return true
            }
        }
        LogHelper.w(TAG, "背屏直启验证超时: ${spec.grepActivityName}")
        return false
    }

    private fun isTargetActivityOnRearDisplay(
        taskService: ITaskService,
        spec: RearActivityLaunchSpec,
        rearDisplayId: Int,
    ): Boolean {
        try {
            val fg = taskService.getForegroundComponentOnDisplay(rearDisplayId)
            if (fg != null && fg.contains(spec.grepActivityName)) {
                return true
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "getForegroundComponentOnDisplay: ${e.message}")
        }
        val taskId = findTaskIdInStack(taskService, spec.grepActivityName) ?: return false
        return try {
            taskService.isTaskOnDisplay(taskId, rearDisplayId)
        } catch (e: Exception) {
            LogHelper.w(TAG, "isTaskOnDisplay: ${e.message}")
            false
        }
    }

    private fun findTaskIdInStack(taskService: ITaskService, grepActivityName: String): Int? {
        val grepCmd = "am stack list | grep $grepActivityName"
        return try {
            val result = taskService.executeShellCommandWithResult(grepCmd) ?: return null
            val matcher = TASK_ID_PATTERN.matcher(result)
            if (matcher.find()) {
                matcher.group(1)?.toIntOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "findTaskIdInStack: ${e.message}")
            null
        }
    }

    /**
     * 主屏占位迁背屏（对齐 MiRoot-3.4）：
     * 主屏 `am start` → grep taskId（60×30ms，20/40 重发）→ `service call activity_task 50` → settle。
     */
    private fun runMainPlaceholderMoveInternal(
        app: Context,
        taskService: ITaskService,
        spec: RearActivityLaunchSpec,
        skipPreWake: Boolean,
    ): String? {
        val displayId = spec.rearDisplayId
        if (!skipPreWake && !preLaunchWakeup(app, taskService, displayId, spec.alwaysSendWakeup)) {
            return null
        }

        LogHelper.d(TAG, "主屏占位(3.4): ${LogHelper.truncateForLog(spec.mainPlaceholderCmd, 120)}")
        try {
            taskService.executeShellCommand(spec.mainPlaceholderCmd)
        } catch (e: Exception) {
            LogHelper.e(TAG, "主屏占位启动失败: ${e.message}")
            return null
        }

        val taskId = pollTaskIdWithGrep(taskService, spec.grepActivityName, spec.mainPlaceholderCmd)
        if (taskId == null) {
            LogHelper.e(TAG, "占位迁屏未能解析 taskId (grep=${spec.grepActivityName})")
            return null
        }

        val tid = taskId.toIntOrNull()
        if (tid == null) {
            LogHelper.e(TAG, "无效 taskId=$taskId")
            return null
        }

        val settleMs = placeholderMoveSettleMs(spec)
        if (!moveTaskToRearPlaceholderStyle(taskService, tid, displayId, settleMs)) {
            LogHelper.e(TAG, "占位迁屏失败 taskId=$taskId display=$displayId")
            return null
        }
        LogHelper.d(TAG, "占位已迁背屏(3.4) taskId=$taskId")

        if (!finalizeOnRear(app, taskService, displayId, spec, PLACEHOLDER_POST_WAKE_SETTLE_CAP_MS)) {
            return null
        }
        return taskId
    }

    private fun placeholderMoveSettleMs(spec: RearActivityLaunchSpec): Long =
        if (spec.disableMode == OfficialDisableAfterLaunch.CHARGING_SUBSCREEN) {
            PLACEHOLDER_MOVE_SETTLE_CHARGING_MS
        } else {
            PLACEHOLDER_MOVE_SETTLE_PROJECTION_MS
        }

    /** 3.4 迁屏命令；失败时再试 [ITaskService.moveTaskToDisplay]（Shizuku 内多路径）。 */
    private fun moveTaskToRearPlaceholderStyle(
        taskService: ITaskService,
        taskId: Int,
        rearDisplayId: Int,
        settleMs: Long,
    ): Boolean {
        val serviceCallCmd = "service call activity_task 50 i32 $taskId i32 $rearDisplayId"
        try {
            taskService.executeShellCommand(serviceCallCmd)
        } catch (e: Exception) {
            LogHelper.w(TAG, "service call 50: ${e.message}")
        }
        if (!sleepQuiet(settleMs)) {
            return false
        }
        if (confirmTaskOnRearDisplay(taskService, taskId, rearDisplayId, serviceCallCmd)) {
            return true
        }
        LogHelper.w(TAG, "占位迁屏未确认，尝试 moveTaskToDisplay taskId=$taskId")
        return try {
            if (!taskService.moveTaskToDisplay(taskId, rearDisplayId)) {
                return false
            }
            sleepQuiet(settleMs.coerceAtMost(50L))
            confirmTaskOnRearDisplay(taskService, taskId, rearDisplayId, serviceCallCmd)
        } catch (e: Exception) {
            LogHelper.w(TAG, "moveTaskToDisplay fallback: ${e.message}")
            false
        }
    }

    private fun confirmTaskOnRearDisplay(
        taskService: ITaskService,
        taskId: Int,
        rearDisplayId: Int,
        retryMoveCmd: String,
    ): Boolean {
        for (attempt in 0 until CONFIRM_REAR_MOVE_ATTEMPTS) {
            try {
                if (taskService.isTaskOnDisplay(taskId, rearDisplayId)) {
                    LogHelper.d(TAG, "迁背屏已确认 taskId=$taskId (attempt=${attempt + 1})")
                    return true
                }
                if (attempt == 2) {
                    LogHelper.d(TAG, "迁背屏重试 service call (attempt=${attempt + 1})")
                    taskService.executeShellCommand(retryMoveCmd)
                }
                if (!sleepQuiet(CONFIRM_REAR_MOVE_STEP_MS)) {
                    return false
                }
            } catch (e: Exception) {
                LogHelper.w(TAG, "confirmTaskOnRear: ${e.message}")
                return false
            }
        }
        return false
    }

    private fun finalizeOnRear(
        app: Context,
        taskService: ITaskService,
        rearDisplayId: Int,
        spec: RearActivityLaunchSpec,
        postWakeSettleCapMs: Long = 0L,
    ): Boolean {
        when (spec.disableMode) {
            OfficialDisableAfterLaunch.MIROOT_PROJECTION_SESSION -> {
                OfficialSubscreenMiRootProjectionSession.acquire(app, taskService)
                LogHelper.d(TAG, "已禁用官方背屏（MiRoot Session）")
            }
            OfficialDisableAfterLaunch.CHARGING_SUBSCREEN -> {
                ChargingOfficialSubscreen.applyDisableBeforeChargingFlow(app, taskService)
                LogHelper.d(TAG, "已禁用官方背屏（充电动画）")
            }
        }
        val wakeCap =
            when {
                postWakeSettleCapMs > 0L -> postWakeSettleCapMs
                spec.disableMode == OfficialDisableAfterLaunch.MIROOT_PROJECTION_SESSION ->
                    RearAssistPrefs.wakeSettleDelayMsAfterKeyevent(app).toLong()
                else -> 0L
            }
        return postLaunchWakeup(app, taskService, rearDisplayId, spec, wakeCap)
    }

    private fun preLaunchWakeup(
        app: Context,
        taskService: ITaskService,
        rearDisplayId: Int,
        alwaysSendWakeup: Boolean,
        settleCapMs: Long = 0L,
    ): Boolean {
        if (alwaysSendWakeup) {
            sendUnconditionalWakeup(taskService, rearDisplayId)
            return true
        }
        if (!RearProjectionLaunchWake.sendWakeupIfKeepScreenOn(app, taskService, rearDisplayId)) {
            return true
        }
        return RearProjectionLaunchWake.settleAfterWakeup(app, settleCapMs)
    }

    private fun postLaunchWakeup(
        app: Context,
        taskService: ITaskService,
        rearDisplayId: Int,
        spec: RearActivityLaunchSpec,
        settleCapMs: Long = 0L,
    ): Boolean {
        if (spec.alwaysSendWakeup) {
            sendUnconditionalWakeup(taskService, rearDisplayId)
            return true
        }
        if (!RearProjectionLaunchWake.sendWakeupIfKeepScreenOn(app, taskService, rearDisplayId)) {
            return true
        }
        if (!RearProjectionLaunchWake.settleAfterWakeup(app, settleCapMs)) {
            if (spec.disableMode == OfficialDisableAfterLaunch.MIROOT_PROJECTION_SESSION) {
                OfficialSubscreenMiRootProjectionSession.release(app, taskService)
            }
            return false
        }
        return true
    }

    private fun sendUnconditionalWakeup(taskService: ITaskService, rearDisplayId: Int) {
        val d = if (rearDisplayId > 0) rearDisplayId else REAR_DISPLAY_ID
        try {
            taskService.executeShellCommand("input -d $d keyevent KEYCODE_WAKEUP")
        } catch (e: Exception) {
            LogHelper.w(TAG, "KEYCODE_WAKEUP: ${e.message}")
        }
    }

    @JvmStatic
    fun pollTaskIdWithGrep(
        taskService: ITaskService,
        grepActivityName: String,
        retryCmd: String,
    ): String? {
        val grepCmd = "am stack list | grep $grepActivityName"
        for (attempt in 0 until POLL_ATTEMPTS) {
            if (attempt > 0 && !sleepQuiet(POLL_INTERVAL_FAST_MS)) {
                return null
            }
            val result = taskService.executeShellCommandWithResult(grepCmd)
            if (!result.isNullOrBlank()) {
                val matcher = TASK_ID_PATTERN.matcher(result)
                if (matcher.find()) {
                    val id = matcher.group(1)
                    LogHelper.d(TAG, "taskId=$id (attempt=${attempt + 1})")
                    return id
                }
            }
            if (attempt == POLL_RETRY_AT_1 || attempt == POLL_RETRY_AT_2 || attempt == POLL_RETRY_AT_3) {
                LogHelper.d(TAG, "重发主屏占位 (attempt=${attempt + 1})")
                try {
                    taskService.executeShellCommand(retryCmd)
                } catch (e: Exception) {
                    LogHelper.w(TAG, "重发主屏占位失败: ${e.message}")
                }
            }
        }
        return null
    }

    @JvmStatic
    fun sleepQuiet(ms: Long): Boolean {
        if (ms <= 0L) return true
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
