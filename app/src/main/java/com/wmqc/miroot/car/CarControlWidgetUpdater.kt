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
        val components = listOf(
            ComponentName(appCtx, CarControlAppWidgetProvider::class.java),
            ComponentName(appCtx, CarControlWidget6x2Provider::class.java),
            ComponentName(appCtx, CarControlWidget6x4Provider::class.java),
        )
        val ids = components.flatMap {
            manager.getAppWidgetIds(it).toList()
        }.toIntArray()
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
