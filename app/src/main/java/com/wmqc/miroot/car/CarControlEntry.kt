package com.wmqc.miroot.car
import com.wmqc.miroot.display.MainDisplayUi

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 功能页车控入口：三连击标题进入车控设置（登录态由 [LoginService] 判断）。
 */
object CarControlEntry {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 未登录则打开登录页，已登录则打开车控设置。 */
    @JvmStatic
    fun openCarControlSettingsFromFeatures(context: Context) {
        val app = context.applicationContext
        if (!CarControlDeviceGate.isAllowed(app)) {
            MainDisplayUi.showToast(app, "当前设备未授权使用车控", Toast.LENGTH_SHORT)
            return
        }
        executor.execute {
            val expired = try {
                LoginService.isLoginExpired(app)
            } catch (_: Exception) {
                true
            }
            mainHandler.post {
                val cls = if (expired) {
                    CarControlLoginActivity::class.java
                } else {
                    CarControlSettingsActivity::class.java
                }
                context.startActivity(Intent(context, cls))
            }
        }
    }
}
