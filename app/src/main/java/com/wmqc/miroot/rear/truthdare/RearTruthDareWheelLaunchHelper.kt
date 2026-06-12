package com.wmqc.miroot.rear.truthdare



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



object RearTruthDareWheelLaunchHelper {



    const val REAR_DISPLAY_ID: Int = RearDesktopLaunchHelper.REAR_DISPLAY_ID



    fun requestOpenTruthDareWheel(context: Context): Boolean {

        val app = context.applicationContext

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {

            MainDisplayUi.showToast(app, R.string.rear_truth_dare_need_api26, Toast.LENGTH_SHORT)

            return false

        }

        return RearDisplayOpenHelper.startLaunchService(

            app,

            RearTruthDareWheelLaunchService::class.java,

            RearTruthDareWheelIntents.ACTION_OPEN_TRUTH_DARE_WHEEL,

        )

    }



    fun startTruthDareWheelOnRearDisplayWithTaskService(taskService: ITaskService, context: Context): Boolean {

        val app = context.applicationContext

        return try {

            try {

                if (!DisplayInfoCache.getInstance().isInitialized) {

                    DisplayInfoCache.getInstance().initialize(taskService)

                }

            } catch (e: Exception) {

                LogHelper.w(TAG, "DisplayInfoCache init (ignore): ${e.message}")

            }

            val component = "${app.packageName}/${RearTruthDareWheelActivity::class.java.name}"

            val fromService = context is android.app.Service

            val spec =

                RearProjectionLaunchSequence.openOnRearLaunchSpec(

                    "RearTruthDareWheelActivity",

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

            LogHelper.d(TAG, "truth dare wheel launch ok=$ok")

            ok

        } catch (e: Exception) {

            LogHelper.d(TAG, "startTruthDareWheelOnRearDisplayWithTaskService failed", e)

            OfficialSubscreenMiRootProjectionSession.release(app, taskService)

            false

        }

    }



    fun startTruthDareWheelOnRearDisplay(context: Context): Boolean {

        val app = context.applicationContext

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {

            MainDisplayUi.showToast(app, R.string.rear_truth_dare_need_api26, Toast.LENGTH_SHORT)

            return false

        }

        if (!RearDisplayOpenHelper.prepareRearDisplayForOpen(app)) {

            MainDisplayUi.showToast(app, R.string.rear_truth_dare_display_unavailable, Toast.LENGTH_SHORT)

            return false

        }

        return if (

            RearDisplayOpenHelper.startActivityOnRearDisplay(app, RearTruthDareWheelActivity::class.java)

        ) {

            true

        } else {

            MainDisplayUi.showToast(app, R.string.rear_truth_dare_start_failed, Toast.LENGTH_SHORT)

            false

        }

    }



    private const val TAG = "RearTruthDareLaunch"

}

