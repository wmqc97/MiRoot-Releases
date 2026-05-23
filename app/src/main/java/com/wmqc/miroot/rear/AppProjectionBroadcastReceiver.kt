package com.wmqc.miroot.rear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wmqc.miroot.lyrics.LogHelper

class AppProjectionBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != AppProjectionIntents.ACTION_APP_PROJECTION) return
        LogHelper.d(TAG, "app projection broadcast")
        try {
            val app = context.applicationContext
            val svc = Intent(app, AppProjectionService::class.java).apply {
                action = AppProjectionIntents.ACTION_APP_PROJECTION
                val op = intent.getStringExtra(AppProjectionIntents.EXTRA_APP_PROJECTION_OP)
                if (op != null) {
                    putExtra(AppProjectionIntents.EXTRA_APP_PROJECTION_OP, op)
                }
            }
            app.startService(svc)
        } catch (e: Exception) {
            LogHelper.e(TAG, "start AppProjectionService failed", e)
        }
    }

    private companion object {
        private const val TAG = "AppProjectionRcvr"
    }
}
