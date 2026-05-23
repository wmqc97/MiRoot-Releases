package com.wmqc.miroot.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * 仅将 **Toast 文字提示** 固定到 [Display.DEFAULT_DISPLAY]（主屏），
 * 避免在背屏 Activity / 背屏相关 Context 上弹出时出现在背屏。
 *
 * 对话框、Snackbar 等请使用各自组件的常规 Context，不要经此类包装。
 */
object MainDisplayUi {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun mainDisplayContext(base: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return base
        return try {
            val app = base.applicationContext
            val dm = app.getSystemService(DisplayManager::class.java) ?: return base
            val main = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return base
            base.createDisplayContext(main)
        } catch (_: Exception) {
            base
        }
    }

    @JvmStatic
    fun showToast(base: Context, text: CharSequence, duration: Int) {
        val app = base.applicationContext
        val action = Runnable {
            try {
                val ctx = mainDisplayContext(app)
                val toast = Toast.makeText(ctx, text, duration)
                trySetToastDisplayId(toast)
                toast.show()
            } catch (_: Exception) {
                try {
                    val fallback = Toast.makeText(app, text, duration)
                    trySetToastDisplayId(fallback)
                    fallback.show()
                } catch (_: Exception) {
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    @JvmStatic
    fun showToast(base: Context, @StringRes resId: Int, duration: Int) {
        showToast(base, base.applicationContext.getText(resId), duration)
    }

    @JvmStatic
    fun trySetToastDisplayId(toast: Toast, displayId: Int = Display.DEFAULT_DISPLAY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            Toast::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(toast, displayId)
        } catch (_: Exception) {
        }
    }
}
