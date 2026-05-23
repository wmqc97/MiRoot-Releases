package com.wmqc.miroot.license

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 外部广播：查询当前是否已离线激活。
 *
 * 请求：[ActivationStatusIntents.ACTION_QUERY_ACTIVATION_STATUS]；
 * 回执默认 [ActivationStatusIntents.ACTION_ACTIVATION_STATUS_RESULT]（与 [com.wmqc.miroot.lyrics.LyricsMusicProjectionReceiver] 查询模式一致：可选 `requestId`、`replyAction`）。
 */
class ActivationStatusQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (intent.action != ActivationStatusIntents.ACTION_QUERY_ACTIVATION_STATUS) return
        val app = context.applicationContext
        val activated = OfflineActivationRepository.isActivated(app)
        var replyAction = intent.getStringExtra(ActivationStatusIntents.EXTRA_REPLY_ACTION)
        if (replyAction.isNullOrEmpty()) {
            replyAction = ActivationStatusIntents.ACTION_ACTIVATION_STATUS_RESULT
        }
        val requestId = intent.getStringExtra(ActivationStatusIntents.EXTRA_REQUEST_ID) ?: ""
        val reply = Intent(replyAction).apply {
            putExtra(ActivationStatusIntents.EXTRA_REQUEST_ID, requestId)
            putExtra("success", "true")
            putExtra(
                ActivationStatusIntents.EXTRA_ACTIVATED,
                if (activated) "true" else "false",
            )
        }
        app.sendBroadcast(reply)
    }
}
