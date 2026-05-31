package com.wmqc.miroot.charging

import android.content.Context
import android.content.Intent

/** 功能页长按充电动画区块标题时，请求 [ChargingService] 在背屏启动预览。 */
object ChargingPreviewLauncher {

    @JvmStatic
    fun requestPreview(context: Context) {
        val app = context.applicationContext
        val intent = Intent(ChargingIntents.ACTION_PREVIEW_CHARGING_ANIMATION)
            .setPackage(app.packageName)
        app.sendBroadcast(intent)
    }
}
