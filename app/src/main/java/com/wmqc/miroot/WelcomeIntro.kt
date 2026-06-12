package com.wmqc.miroot

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.license.OfflineActivationRepository

/**
 * 首次激活成功、或 [BuildConfig.VERSION_CODE] 升级后展示一次欢迎说明（与 [R.string.post_activation_welcome_message] 一致）。
 * 须将正文滚动到底部方可点击「知道了」。
 *
 * [showReadmeDialog] 供「状态」页版本行等入口随时查看，不写入「已读」偏好。
 */
object WelcomeIntro {

    private const val PREFS_NAME = "miroot_app_prefs"
    private const val KEY_UPDATE_LOG_SEEN_VERSION = "update_log_seen_version_code"
    private const val KEY_POST_ACTIVATION_DONE = "post_activation_welcome_done"

    private enum class IntroContentType {
        WELCOME,
        UPDATE_LOG,
    }

    fun showIfNeeded(activity: AppCompatActivity) {
        if (activity.isFinishing) return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val vc = BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_UPDATE_LOG_SEEN_VERSION, 0) >= vc) return

        showScrollDialog(activity, IntroContentType.UPDATE_LOG, cancelable = false) {
            prefs.edit().putInt(KEY_UPDATE_LOG_SEEN_VERSION, vc).apply()
        }
    }

    /**
     * 首次在本机完成离线激活成功后调用：与 [showIfNeeded] 同一套正文与滚动确认，并写入激活欢迎已读。
     */
    fun showAfterSuccessfulActivation(activity: AppCompatActivity) {
        if (activity.isFinishing) return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_POST_ACTIVATION_DONE, false)) return

        showScrollDialog(activity, IntroContentType.WELCOME, cancelable = false) {
            prefs.edit()
                .putBoolean(KEY_POST_ACTIVATION_DONE, true)
                .apply()
        }
    }

    /**
     * 旧版本已激活用户：首次运行带本逻辑的版本时写入已读，避免补弹激活欢迎。
     */
    fun migratePostActivationLegacyIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_POST_ACTIVATION_DONE) && OfflineActivationRepository.isActivated(context)) {
            prefs.edit().putBoolean(KEY_POST_ACTIVATION_DONE, true).apply()
        }
    }

    fun showReadmeDialog(context: Context) {
        val activity = context.appCompatActivity()
        if (activity == null || activity.isFinishing) return
        showScrollDialog(activity, IntroContentType.UPDATE_LOG, cancelable = true) { }
    }

    private fun showScrollDialog(
        activity: AppCompatActivity,
        contentType: IntroContentType,
        cancelable: Boolean,
        onConfirmed: () -> Unit,
    ) {
        val scrollView = LayoutInflater.from(activity).inflate(R.layout.dialog_welcome_scroll, null) as ScrollView
        scrollView.findViewById<TextView>(R.id.welcome_body).text = buildDialogBody(activity, contentType)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(buildDialogTitle(activity, contentType))
            .setView(scrollView)
            .setCancelable(cancelable)
            .setPositiveButton(R.string.welcome_dialog_confirm) { _, _ -> onConfirmed() }
            .create()

        dialog.setCanceledOnTouchOutside(cancelable)
        if (!cancelable) {
            dialog.setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK
            }
        }

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return@setOnShowListener
            positive.isEnabled = false

            fun syncScrollReadButton() {
                positive.isEnabled = !scrollView.canScrollVertically(1)
            }

            scrollView.post { syncScrollReadButton() }
            scrollView.setOnScrollChangeListener { _, _, _, _, _ -> syncScrollReadButton() }
            scrollView.viewTreeObserver.addOnGlobalLayoutListener { syncScrollReadButton() }
        }

        dialog.show()
    }

    private fun buildDialogTitle(ctx: Context, contentType: IntroContentType): String {
        return when (contentType) {
            IntroContentType.WELCOME -> ctx.getString(R.string.post_activation_welcome_title)
            IntroContentType.UPDATE_LOG -> updateLogTitle(ctx)
        }
    }

    private fun updateLogTitle(ctx: Context): String {
        val vn = BuildConfig.VERSION_NAME
        return when {
            vn.startsWith("2.2") -> ctx.getString(R.string.update_log_v220_title)
            vn.startsWith("2.1") -> ctx.getString(R.string.update_log_v210_title)
            vn.startsWith("2.0") -> ctx.getString(R.string.update_log_v200_title)
            vn.startsWith("1.9.5") -> ctx.getString(R.string.update_log_v195_title)
            vn.startsWith("1.9.4") -> ctx.getString(R.string.update_log_v194_title)
            vn.startsWith("1.9.3") -> ctx.getString(R.string.update_log_v193_title)
            vn.startsWith("1.9.2") -> ctx.getString(R.string.update_log_v192_title)
            vn.startsWith("1.9.1") -> ctx.getString(R.string.update_log_v191_title)
            vn.startsWith("1.9.0") -> ctx.getString(R.string.update_log_v190_title)
            vn.startsWith("1.8.9") -> ctx.getString(R.string.update_log_v189_title)
            vn.startsWith("1.8.8") -> ctx.getString(R.string.update_log_v188_title)
            vn.startsWith("1.8.7") -> ctx.getString(R.string.update_log_v187_title)
            vn.startsWith("1.8.6") -> ctx.getString(R.string.update_log_v186_title)
            vn.startsWith("1.8.5") -> ctx.getString(R.string.update_log_v185_title)
            vn.startsWith("1.8.4") -> ctx.getString(R.string.update_log_v184_title)
            vn.startsWith("1.8.3") -> ctx.getString(R.string.update_log_v183_title)
            vn.startsWith("1.8") -> ctx.getString(R.string.update_log_v18_title)
            vn.startsWith("1.7") -> ctx.getString(R.string.update_log_v17_title)
            vn.startsWith("1.6") -> ctx.getString(R.string.update_log_v16_title)
            vn.startsWith("1.5") -> ctx.getString(R.string.update_log_v15_title)
            else -> ctx.getString(R.string.post_activation_welcome_title)
        }
    }

    private fun buildDialogBody(ctx: Context, contentType: IntroContentType): String {
        return when (contentType) {
            IntroContentType.WELCOME -> {
                val tiles = buildString {
                    append("• ")
                    append(ctx.getString(R.string.tile_record_label))
                    append("（")
                    append(ctx.getString(R.string.tile_record_subtitle))
                    append("）")
                    append("\n• ")
                    append(ctx.getString(R.string.tile_screenshot_label))
                    append("\n• ")
                    append(ctx.getString(R.string.tile_music_label))
                    append("（")
                    append(ctx.getString(R.string.tile_music_subtitle))
                    append("）")
                    append("\n• ")
                    append(ctx.getString(R.string.tile_switch_rear_label))
                }
                ctx.getString(R.string.post_activation_welcome_message, tiles)
            }
            IntroContentType.UPDATE_LOG -> updateLogBody(ctx)
        }
    }

    private fun updateLogBody(ctx: Context): String {
        val vn = BuildConfig.VERSION_NAME
        return when {
            vn.startsWith("2.2") -> ctx.getString(R.string.update_log_v220_message)
            vn.startsWith("2.1") -> ctx.getString(R.string.update_log_v210_message)
            vn.startsWith("2.0") -> ctx.getString(R.string.update_log_v200_message)
            vn.startsWith("1.9.5") -> ctx.getString(R.string.update_log_v195_message)
            vn.startsWith("1.9.4") -> ctx.getString(R.string.update_log_v194_message)
            vn.startsWith("1.9.3") -> ctx.getString(R.string.update_log_v193_message)
            vn.startsWith("1.9.2") -> ctx.getString(R.string.update_log_v192_message)
            vn.startsWith("1.9.1") -> ctx.getString(R.string.update_log_v191_message)
            vn.startsWith("1.9.0") -> ctx.getString(R.string.update_log_v190_message)
            vn.startsWith("1.8.9") -> ctx.getString(R.string.update_log_v189_message)
            vn.startsWith("1.8.8") -> ctx.getString(R.string.update_log_v188_message)
            vn.startsWith("1.8.7") -> ctx.getString(R.string.update_log_v187_message)
            vn.startsWith("1.8.6") -> ctx.getString(R.string.update_log_v186_message)
            vn.startsWith("1.8.5") -> ctx.getString(R.string.update_log_v185_message)
            vn.startsWith("1.8.4") -> ctx.getString(R.string.update_log_v184_message)
            vn.startsWith("1.8.3") -> ctx.getString(R.string.update_log_v183_message)
            vn.startsWith("1.8") -> ctx.getString(R.string.update_log_v18_message)
            vn.startsWith("1.7") -> ctx.getString(R.string.update_log_v17_message)
            vn.startsWith("1.6") -> ctx.getString(R.string.update_log_v16_message)
            vn.startsWith("1.5") -> ctx.getString(R.string.update_log_v15_message)
            else -> {
                val tiles = buildString {
                    append("• ")
                    append(ctx.getString(R.string.tile_record_label))
                    append("（")
                    append(ctx.getString(R.string.tile_record_subtitle))
                    append("）")
                    append("\n• ")
                    append(ctx.getString(R.string.tile_screenshot_label))
                    append("\n• ")
                    append(ctx.getString(R.string.tile_music_label))
                    append("（")
                    append(ctx.getString(R.string.tile_music_subtitle))
                    append("）")
                    append("\n• ")
                    append(ctx.getString(R.string.tile_switch_rear_label))
                }
                ctx.getString(R.string.post_activation_welcome_message, tiles)
            }
        }
    }

    private fun Context.appCompatActivity(): AppCompatActivity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is AppCompatActivity) return ctx
            ctx = ctx.baseContext
        }
        return this as? AppCompatActivity
    }
}
