package com.wmqc.miroot.rear

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskServiceConnector

/**
 * 音乐 / 车控背屏投屏与 [com.wmqc.miroot.charging.RearScreenChargingActivity] 对齐的窗口与退出语义：
 * - 背屏：不透明窗口 + 可触摸/系统返回
 * - 结束：{@link Activity#finish()} 前仅唤醒背屏，不压黑、不在 finish 前 restore 官方 Launcher
 * - 恢复官方背屏：在 {@code onDestroy} 之后后台线程执行（避免闪白/黑屏空窗）
 */
object RearMirootProjectionLifecycle {

    private const val TAG = "RearMirootProjLife"

    const val REAR_DISPLAY_ID: Int = 1

    /** 主屏 displayId（占位迁屏起点）。 */
    const val MAIN_DISPLAY_ID: Int = Display.DEFAULT_DISPLAY

    /** 非主屏横屏磁贴等例外：主屏仅透明占位，禁止 setContentView / Compose 内容。 */
    const val MAIN_DISPLAY_MODE_NOT_APPLICABLE: Int = 0

    /** 主屏、尚无投屏 UI：仅透明窗口占位。 */
    const val MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER: Int = 1

    /** 主屏却已有投屏 UI（迁屏失败或 task 落回主屏）：隐藏内容并结束投屏。 */
    const val MAIN_DISPLAY_MODE_MUST_END_PROJECTION: Int = 2

    /** 主屏占位迁背屏后轮询间隔（与车控一致）。 */
    const val REAR_UI_INIT_POLL_INTERVAL_MS: Long = 40L

    const val REAR_UI_INIT_POLL_MAX_ATTEMPTS: Int = 45

    /** 与充电动画 onDestroy 恢复背屏前的短延迟一致。 */
    const val RESTORE_OFFICIAL_DELAY_MS: Long = 50L

    /**
     * 同一次投屏结束链路（finishProjectionFromUser → finish → onDestroy restore）
     * 可能在数百毫秒内多次调用 [sendMainDisplayHomeBeforeProjectionEnd]；合并为一次，减轻锁屏下系统「请解锁后再试」。
     */
    private const val MAIN_HOME_DEDUP_WINDOW_MS: Long = 800L

    private val mainHomeDedupLock = Any()
    private var lastMainHomeSentElapsedMs: Long = 0L

    /**
     * 与背屏桌面一致：迁屏/占位期间也清除 NOT_TOUCHABLE，背屏落屏后请求窗口焦点，
     * 使系统边缘返回在 UI 未 inflate 前即可送达 Activity（需已注册 OnBackPressedCallback）。
     */
    @JvmStatic
    fun primeRearSystemBackGestures(activity: Activity) {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (activity.display?.displayId != REAR_DISPLAY_ID) {
                    return
                }
            } catch (e: Exception) {
                LogHelper.w(TAG, "primeRearSystemBackGestures display: ${e.message}")
                return
            }
        }
        val decor = activity.window?.decorView ?: return
        try {
            decor.isFocusable = true
            decor.isFocusableInTouchMode = true
            if (!decor.hasFocus()) {
                decor.requestFocus()
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "primeRearSystemBackGestures focus: ${e.message}")
        }
    }

    /** TaskService 已连接时巩固禁用官方背屏中心（与投屏启动 Session.acquire 互补）。 */
    @JvmStatic
    fun reinforceOfficialSubscreenDisabled(appContext: android.content.Context) {
        val app = appContext.applicationContext
        val ts = RootTaskServiceConnector.getIfConnected() ?: return
        try {
            OfficialSubscreenMiRootProjectionSession.acquire(app, ts)
        } catch (e: Exception) {
            LogHelper.w(TAG, "reinforceOfficialSubscreenDisabled: ${e.message}")
        }
    }

    /** 背屏不透明窗口底：消除透明主题退出闪白；常亮 flags 由 Activity 按偏好自行添加。 */
    @JvmStatic
    @JvmOverloads
    fun applyRearOpaqueWindowBase(activity: Activity, backgroundColor: Int = 0xFF000000.toInt()) {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(activity)
        val w = activity.window ?: return
        try {
            w.setFormat(PixelFormat.OPAQUE)
            w.setBackgroundDrawable(ColorDrawable(backgroundColor))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = w.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                w.attributes = lp
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "applyRearOpaqueWindowBase: ${e.message}")
        }
    }

    /** 充电动画背屏窗口：不透明底 + 常亮 + 硬件加速。 */
    @JvmStatic
    @JvmOverloads
    fun applyRearChargingStyleWindowFlags(activity: Activity) {
        applyRearOpaqueWindowBase(activity)
        val w = activity.window ?: return
        try {
            w.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "applyRearChargingStyleWindowFlags: ${e.message}")
        }
    }

    /**
     * 主屏占位迁背屏后，系统返回可能先把 task 落回主屏再 finish；退出前隐藏窗口，避免主屏闪一下投屏 UI。
     */
    @JvmStatic
    fun hideWindowBeforeProjectionFinish(activity: Activity) {
        val w = activity.window ?: return
        try {
            w.setWindowAnimations(0)
            val decor = w.decorView
            decor.visibility = View.GONE
            activity.findViewById<View>(android.R.id.content)?.visibility = View.GONE
        } catch (e: Exception) {
            LogHelper.w(TAG, "hideWindowBeforeProjectionFinish: ${e.message}")
        }
    }

    /** 用户结束或 finish 进行中：勿在 onResume 再刷新 UI。 */
    @JvmStatic
    fun shouldSkipProjectionResume(
        isFinishing: Boolean,
        projectionExitFlowStarted: Boolean,
        finishRequestedByMiRoot: Boolean,
    ): Boolean = isFinishing || projectionExitFlowStarted || finishRequestedByMiRoot

    /**
     * 背屏 UI 已在 display 1 创建，但 onResume 落在主屏：占位迁屏 task 被系统拉回主屏，应立刻结束而非展示界面。
     */
    @JvmStatic
    fun shouldFinishOnMainDisplayDuringProjection(
        displayId: Int,
        initialDisplayId: Int,
        isMainScreenLandscapeMode: Boolean,
        hasProjectionUi: Boolean,
    ): Boolean =
        !isMainScreenLandscapeMode &&
            displayId != REAR_DISPLAY_ID &&
            initialDisplayId == REAR_DISPLAY_ID &&
            hasProjectionUi

    /**
     * 主屏占位迁背屏策略（音乐 / 车控 / 桌面 / 充电等共用）。
     *
     * @param allowMainScreenLandscape 磁贴主屏横屏等允许在主屏展示 UI 的例外
     * @param hasProjectionUi 是否已 inflate / setContent（歌词、车控 mainLayout、充电视图等）
     */
    @JvmStatic
    fun resolveMainDisplayProjectionMode(
        displayId: Int,
        allowMainScreenLandscape: Boolean,
        hasProjectionUi: Boolean,
    ): Int {
        if (allowMainScreenLandscape || displayId == REAR_DISPLAY_ID) {
            return MAIN_DISPLAY_MODE_NOT_APPLICABLE
        }
        return if (hasProjectionUi) {
            MAIN_DISPLAY_MODE_MUST_END_PROJECTION
        } else {
            MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER
        }
    }

    @JvmStatic
    fun getDisplayIdSafe(activity: Activity): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return MAIN_DISPLAY_ID
        }
        return try {
            activity.display?.displayId ?: MAIN_DISPLAY_ID
        } catch (_: Exception) {
            MAIN_DISPLAY_ID
        }
    }

    /**
     * 主屏占位：透明窗口、不绘制投屏底色；若已有 UI 则一并隐藏（配合 MUST_END 结束投屏）。
     */
    @JvmStatic
    fun applyMainDisplayTransparentPlaceholder(activity: Activity) {
        val w = activity.window ?: return
        try {
            w.setWindowAnimations(0)
            w.setFormat(PixelFormat.TRANSLUCENT)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val decor = w.decorView
            decor.setBackgroundColor(Color.TRANSPARENT)
            activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(Color.TRANSPARENT)
        } catch (e: Exception) {
            LogHelper.w(TAG, "applyMainDisplayTransparentPlaceholder: ${e.message}")
        }
    }

    /**
     * 按 [resolveMainDisplayProjectionMode] 结果应用主屏策略。
     *
     * @return 是否为主屏占位/收尾模式（调用方应 return，勿再建 UI）
     */
    @JvmStatic
    fun applyMainDisplayPlaceholderPolicy(activity: Activity, mode: Int): Boolean {
        when (mode) {
            MAIN_DISPLAY_MODE_NOT_APPLICABLE -> return false
            MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER -> {
                applyMainDisplayTransparentPlaceholder(activity)
                return true
            }
            MAIN_DISPLAY_MODE_MUST_END_PROJECTION -> {
                applyMainDisplayTransparentPlaceholder(activity)
                hideWindowBeforeProjectionFinish(activity)
                return true
            }
            else -> return false
        }
    }

    /**
     * 占位迁屏结束投屏时，主屏若仍有 MiRoot [MainActivity] 在任务栈，{@code finish}/{@code finishAndRemoveTask}
     * 后主屏焦点会回到应用主界面（用户感知为「返回却看到 MiRoot 主页」）。先在主屏执行 HOME 回系统桌面。
     */
    @JvmStatic
    fun sendMainDisplayHomeBeforeProjectionEnd(taskService: ITaskService?) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(mainHomeDedupLock) {
            if (now - lastMainHomeSentElapsedMs < MAIN_HOME_DEDUP_WINDOW_MS) {
                LogHelper.d(
                    TAG,
                    "main HOME skipped (dedup ${now - lastMainHomeSentElapsedMs}ms < ${MAIN_HOME_DEDUP_WINDOW_MS}ms)",
                )
                return
            }
            lastMainHomeSentElapsedMs = now
        }
        val main = MainActivity.getCurrentInstance()
        if (main != null) {
            val back = Runnable {
                try {
                    main.moveTaskToBack(true)
                } catch (e: Exception) {
                    LogHelper.w(TAG, "moveTaskToBack MainActivity: ${e.message}")
                }
            }
            try {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    back.run()
                } else {
                    main.runOnUiThread(back)
                }
            } catch (e: Exception) {
                LogHelper.w(TAG, "schedule moveTaskToBack: ${e.message}")
            }
        }
        val ts = taskService ?: RootTaskServiceConnector.getIfConnected()
        val cmds =
            arrayOf(
                "input -d $MAIN_DISPLAY_ID keyevent KEYCODE_HOME",
                "am start -d $MAIN_DISPLAY_ID -a android.intent.action.MAIN -c android.intent.category.HOME",
            )
        for (cmd in cmds) {
            try {
                if (ts != null) {
                    ts.executeShellCommand(cmd)
                    LogHelper.d(TAG, "main HOME before proj end: $cmd")
                } else {
                    PrivilegedShell.execCmd(cmd)
                    LogHelper.d(TAG, "main HOME before proj end (privileged): $cmd")
                }
            } catch (e: Exception) {
                LogHelper.w(TAG, "main HOME failed ($cmd): ${e.message}")
            }
        }
    }

    /** 对齐充电动画 {@code prepareRearProjectionVisibleBeforeFinish}：结束前唤醒背屏。 */
    @JvmStatic
    @JvmOverloads
    fun prepareRearDisplayBeforeFinish(
        rearDisplayId: Int,
        taskService: ITaskService?,
    ) {
        val d = if (rearDisplayId > 0) rearDisplayId else REAR_DISPLAY_ID
        try {
            taskService?.executeShellCommand("input -d $d keyevent KEYCODE_WAKEUP")
        } catch (e: Exception) {
            LogHelper.w(TAG, "KEYCODE_WAKEUP: ${e.message}")
        }
    }

    /**
     * 在 Activity 已 finish 后恢复官方背屏（勿在 finish 前调用 Session.release，否则会闪白底 Launcher）。
     */
    @JvmStatic
    fun scheduleOfficialSubscreenRestoreAfterDestroy(
        appContext: android.content.Context,
        taskService: ITaskService?,
    ) {
        val app = appContext.applicationContext
        Thread(
            {
                try {
                    // HOME 已在 finish() 中发送；此处不再重复，避免与 restore 叠加重试
                    Thread.sleep(RESTORE_OFFICIAL_DELAY_MS)
                    OfficialSubscreenMiRootProjectionSession.release(app, taskService)
                    LogHelper.d(TAG, "official subscreen restored after destroy")
                } catch (e: Exception) {
                    LogHelper.w(TAG, "restore official subscreen: ${e.message}")
                }
            },
            "miroot-proj-official-restore",
        ).start()
    }
}
