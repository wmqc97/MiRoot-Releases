package com.wmqc.miroot

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 首次安装或 [BuildConfig.VERSION_CODE] 升级后展示一次欢迎与说明。
 * 更新文案：修改 [R.string.welcome_dialog_message]；发版时同步 [app/build.gradle.kts] 的 versionName / versionCode。
 *
 * [showReadmeDialog] 与首次弹窗共用同一套文案，供用户随时从版本信息入口查看，不写入「已读」偏好。
 */
object WelcomeIntro {

    private const val PREFS_NAME = "miroot_app_prefs"
    private const val KEY_WELCOME_SEEN_VERSION = "welcome_seen_version_code"

    fun showIfNeeded(activity: AppCompatActivity) {
        if (activity.isFinishing) return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        val vc = BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_WELCOME_SEEN_VERSION, 0) >= vc) return

        showReadmeDialogInternal(activity, cancelable = false) {
            prefs.edit().putInt(KEY_WELCOME_SEEN_VERSION, vc).apply()
        }
    }

    /**
     * 更新介绍与使用说明（与首次启动弹窗一致）。不修改已读状态。
     */
    fun showReadmeDialog(context: Context) {
        val act = context as? Activity
        if (act != null && act.isFinishing) return
        showReadmeDialogInternal(context, cancelable = true, onPositive = null)
    }

    private fun showReadmeDialogInternal(
        context: Context,
        cancelable: Boolean,
        onPositive: (() -> Unit)?,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.welcome_dialog_title)
            .setMessage(R.string.welcome_dialog_message)
            .setCancelable(cancelable)
            .setPositiveButton(R.string.welcome_dialog_confirm) { _, _ ->
                onPositive?.invoke()
            }
            .show()
    }
}
