package com.wmqc.miroot.rear.balance

import android.content.Context
import android.os.Build
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.display.MainDisplayUi
import com.wmqc.miroot.lyrics.DisplayInfoCache
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.OfficialSubscreenMiRootProjectionSession
import com.wmqc.miroot.rear.RearDisplayOpenHelper
import com.wmqc.miroot.rear.RearProjectionLaunchSequence
import com.wmqc.miroot.rear.desktop.RearDesktopLaunchHelper

object RearBalanceGameLaunchHelper {

    const val REAR_DISPLAY_ID: Int = RearDesktopLaunchHelper.REAR_DISPLAY_ID

    fun requestOpenBalanceGame(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            MainDisplayUi.showToast(app, R.string.rear_balance_game_need_api26, Toast.LENGTH_SHORT)
            return false
        }
        return RearDisplayOpenHelper.startLaunchService(
            app,
            RearBalanceGameLaunchService::class.java,
            RearBalanceGameIntents.ACTION_OPEN_BALANCE_GAME,
        )
    }

    fun startBalanceGameOnRearDisplayWithTaskService(taskService: ITaskService, context: Context): Boolean {
        val app = context.applicationContext
        return try {
            try {
                if (!DisplayInfoCache.getInstance().isInitialized) {
                    DisplayInfoCache.getInstance().initialize(taskService)
                }
            } catch (e: Exception) {
                LogHelper.w(TAG, "DisplayInfoCache init (ignore): ${e.message}")
            }
            val component = "${app.packageName}/${RearBalanceGameActivity::class.java.name}"
            val fromService = context is android.app.Service
            val spec =
                RearProjectionLaunchSequence.openOnRearLaunchSpec(
                    "RearBalanceGameActivity",
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
            LogHelper.d(TAG, "balance game launch ok=$ok")
            ok
        } catch (e: Exception) {
            LogHelper.d(TAG, "startBalanceGameOnRearDisplayWithTaskService failed", e)
            OfficialSubscreenMiRootProjectionSession.release(app, taskService)
            false
        }
    }

    fun startBalanceGameOnRearDisplay(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            MainDisplayUi.showToast(app, R.string.rear_balance_game_need_api26, Toast.LENGTH_SHORT)
            return false
        }
        if (!RearDisplayOpenHelper.prepareRearDisplayForOpen(app)) {
            MainDisplayUi.showToast(app, R.string.rear_balance_game_display_unavailable, Toast.LENGTH_SHORT)
            return false
        }
        return if (RearDisplayOpenHelper.startActivityOnRearDisplay(app, RearBalanceGameActivity::class.java)) {
            true
        } else {
            MainDisplayUi.showToast(app, R.string.rear_balance_game_start_failed, Toast.LENGTH_SHORT)
            false
        }
    }

    private const val TAG = "RearBalanceGameLaunch"
}
