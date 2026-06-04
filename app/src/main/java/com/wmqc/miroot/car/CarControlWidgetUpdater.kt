package com.wmqc.miroot.car

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

object CarControlWidgetUpdater {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollDelaysMs = longArrayOf(2_000, 5_000, 9_000)

    fun refreshAll(context: Context) {
        val appCtx = context.applicationContext
        val manager = AppWidgetManager.getInstance(appCtx)
        val component = ComponentName(appCtx, CarControlAppWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        CarControlAppWidgetProvider.updateAll(appCtx, manager, ids)
    }

    /** 乐观刷新 + 与背屏一致的退避轮询，等待云端状态回写。 */
    fun scheduleRefreshAfterCommand(context: Context) {
        val appCtx = context.applicationContext
        refreshAll(appCtx)
        pollDelaysMs.forEach { delay ->
            mainHandler.postDelayed({ refreshAll(appCtx) }, delay)
        }
    }

}
