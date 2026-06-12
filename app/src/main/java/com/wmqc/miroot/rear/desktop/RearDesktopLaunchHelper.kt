package com.wmqc.miroot.rear.desktop
import com.wmqc.miroot.display.MainDisplayUi

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.DisplayInfoCache
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.OfficialSubscreenMiRootProjectionSession
import com.wmqc.miroot.rear.RearDisplayOpenHelper
import com.wmqc.miroot.rear.RearProjectionLaunchSequence
import kotlin.jvm.JvmStatic

object RearDesktopLaunchHelper {

    /** 与投屏/应用列表约定一致，副屏多为 displayId=1。 */
    const val REAR_DISPLAY_ID: Int = 1

    /** 功能页/设置页「在背屏打开桌面」：经 LaunchService 先点亮背屏再启动。 */
    @JvmStatic
    fun requestOpenDesktop(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            MainDisplayUi.showToast(app, R.string.rear_desktop_need_api26, Toast.LENGTH_SHORT)
            return false
        }
        return RearDisplayOpenHelper.startLaunchService(
            app,
            RearDesktopLaunchService::class.java,
            RearDesktopIntents.ACTION_OPEN_REAR_DESKTOP,
        )
    }

    /**
     * 背屏直启优先，失败主屏占位迁屏（3.4，与充电动画 / 音乐 / 车控一致）。
     */
    @JvmStatic
    fun startDesktopOnRearDisplayWithTaskService(taskService: ITaskService, context: Context): Boolean {
        val app = context.applicationContext
        return try {
            try {
                if (!DisplayInfoCache.getInstance().isInitialized) {
                    DisplayInfoCache.getInstance().initialize(taskService)
                }
            } catch (e: Exception) {
                LogHelper.w(TAG, "DisplayInfoCache init (ignore): ${e.message}")
            }

            val component = "${app.packageName}/${RearScreenDesktopActivity::class.java.name}"
            val fromService = context is android.app.Service
            val spec =
                RearProjectionLaunchSequence.openOnRearLaunchSpec(
                    "RearScreenDesktopActivity",
                    component,
                    fromService,
                    REAR_DISPLAY_ID,
                )
            val ok =
                RearProjectionLaunchSequence.runPreferDirectRearLaunchWithPlaceholderFallback(
                    context,
                    taskService,
                    spec,
                    null,
                )
            LogHelper.d(TAG, "rear desktop launch ok=$ok")
            ok
        } catch (e: Exception) {
            LogHelper.d(TAG, "startDesktopOnRearDisplayWithTaskService failed", e)
            OfficialSubscreenMiRootProjectionSession.release(app, taskService)
            false
        }
    }

    /**
     * 在背屏启动背屏桌面（回退路径：先点亮背屏再 ActivityOptions 直启）。
     */
    fun startDesktopOnRearDisplay(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LogHelper.d(TAG, "startDesktopOnRearDisplay abort api=${Build.VERSION.SDK_INT}")
            MainDisplayUi.showToast(app, R.string.rear_desktop_need_api26, Toast.LENGTH_SHORT)
            return false
        }
        if (!RearDisplayOpenHelper.prepareRearDisplayForOpen(app)) {
            LogHelper.d(TAG, "startDesktopOnRearDisplay abort rear display unavailable")
            MainDisplayUi.showToast(app, R.string.rear_desktop_display_unavailable, Toast.LENGTH_SHORT)
            return false
        }
        return if (RearDisplayOpenHelper.startActivityOnRearDisplay(app, RearScreenDesktopActivity::class.java)) {
            LogHelper.d(TAG, "startDesktopOnRearDisplay ok")
            true
        } else {
            LogHelper.d(TAG, "startDesktopOnRearDisplay startActivity failed")
            MainDisplayUi.showToast(app, R.string.rear_desktop_start_failed, Toast.LENGTH_SHORT)
            false
        }
    }

    /** 从已在前台的 [android.app.Activity] 启动，以便不使用 NEW_TASK 标记（可选）。 */
    fun startDesktopOnRearDisplay(activity: android.app.Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            MainDisplayUi.showToast(activity, R.string.rear_desktop_need_api26, Toast.LENGTH_SHORT)
            return false
        }
        if (!RearDisplayOpenHelper.prepareRearDisplayForOpen(activity)) {
            MainDisplayUi.showToast(activity, R.string.rear_desktop_display_unavailable, Toast.LENGTH_SHORT)
            return false
        }
        val rear = RearDisplayOpenHelper.rearDisplay(activity) ?: run {
            MainDisplayUi.showToast(activity, R.string.rear_desktop_display_unavailable, Toast.LENGTH_SHORT)
            return false
        }
        return try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(rear.displayId)
            activity.startActivity(Intent(activity, RearScreenDesktopActivity::class.java), opts.toBundle())
            true
        } catch (_: Exception) {
            MainDisplayUi.showToast(activity, R.string.rear_desktop_start_failed, Toast.LENGTH_SHORT)
            false
        }
    }

    private const val TAG = "RearDesktopLaunch"
}
