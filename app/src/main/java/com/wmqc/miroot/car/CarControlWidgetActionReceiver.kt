package com.wmqc.miroot.car

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CarControlWidgetActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pending = goAsync()
        val appWidgetId = intent.getIntExtra(
            EXTRA_APP_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        when (intent.action) {
            ACTION_BUTTON -> {
                val key = intent.getStringExtra(EXTRA_FUNCTION_KEY)?.trim().orEmpty()
                val display = intent.getStringExtra(EXTRA_DISPLAY_TEXT)?.trim().orEmpty()
                if (key.isEmpty() || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                    pending.finish()
                    return
                }
                scope.launch {
                    try {
                        CarControlWidgetPrefs.setPending(
                            context,
                            appWidgetId,
                            key,
                            display.ifEmpty { key },
                        )
                        CarControlAppWidgetProvider.updateWidgets(
                            context,
                            intArrayOf(appWidgetId),
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_CONFIRM -> {
                val confirm = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    CarControlWidgetPrefs.getPending(context, appWidgetId)
                } else {
                    null
                }
                if (confirm == null) {
                    pending.finish()
                    return
                }
                scope.launch {
                    try {
                        if (CarControlDeviceGate.isAllowed(context)) {
                            CarControlWidgetExecutor.executeFunctionKey(context, confirm.functionKey)
                        }
                        CarControlWidgetPrefs.clearPending(context, appWidgetId)
                        CarControlAppWidgetProvider.updateWidgets(
                            context,
                            intArrayOf(appWidgetId),
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_CANCEL -> {
                scope.launch {
                    try {
                        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            CarControlWidgetPrefs.clearPending(context, appWidgetId)
                            CarControlAppWidgetProvider.updateWidgets(
                                context,
                                intArrayOf(appWidgetId),
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_REFRESH -> {
                scope.launch {
                    try {
                        val appCtx = context.applicationContext
                        val manager = AppWidgetManager.getInstance(appCtx)
                        val targetIds = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            intArrayOf(appWidgetId)
                        } else {
                            val components = listOf(
                                android.content.ComponentName(appCtx, CarControlAppWidgetProvider::class.java),
                                android.content.ComponentName(appCtx, CarControlWidget6x2Provider::class.java),
                                android.content.ComponentName(appCtx, CarControlWidget6x4Provider::class.java),
                            )
                            components.flatMap {
                                manager.getAppWidgetIds(it).toList()
                            }.toIntArray()
                        }
                        if (targetIds.isNotEmpty()) {
                            CarControlAppWidgetProvider.updateAll(appCtx, manager, targetIds)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> pending.finish()
        }
    }

    companion object {
        const val ACTION_BUTTON = "com.wmqc.miroot.car.WIDGET_BUTTON"
        const val ACTION_CONFIRM = "com.wmqc.miroot.car.WIDGET_CONFIRM"
        const val ACTION_CANCEL = "com.wmqc.miroot.car.WIDGET_CANCEL"
        const val ACTION_REFRESH = "com.wmqc.miroot.car.WIDGET_REFRESH"
        const val EXTRA_FUNCTION_KEY = "functionKey"
        const val EXTRA_DISPLAY_TEXT = "displayText"
        const val EXTRA_APP_WIDGET_ID = "appWidgetId"
    }
}
