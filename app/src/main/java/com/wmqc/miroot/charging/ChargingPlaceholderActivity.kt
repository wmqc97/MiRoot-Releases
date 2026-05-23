package com.wmqc.miroot.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity

/**
 * Android 11+ 充电动画“零绘制”占位页：
 * - 用于在主屏创建 taskId，但不创建窗口/StartingWindow，避免主屏闪黑
 * - 等 task 迁移到背屏后再启动真正的充电动画 UI Activity
 */
class ChargingPlaceholderActivity : ComponentActivity() {

    private var realUiLaunched = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mainDisplayTimeoutFinish = Runnable {
        // NoDisplay + singleInstance 在部分 ROM 上如果长时间停留在主屏，会在 resume 阶段触发系统的
        // “必须 finish” 约束并直接抛异常。占位页如果未能迁到背屏，兜底自杀即可（本轮动画也不会渲染）。
        if (isFinishing || isDestroyed) return@Runnable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val did = display?.displayId ?: Display.DEFAULT_DISPLAY
            if (did == Display.DEFAULT_DISPLAY && !realUiLaunched) {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                ChargingIntents.ACTION_NOTIFY_CHARGING_TASK_MOVED_TO_REAR -> maybeStartRealChargingUi()
                ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION -> {
                    finish()
                    overridePendingTransition(0, 0)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 注意：此 Activity 在 Manifest 使用 NoDisplay 主题，避免创建窗口导致主屏闪黑。
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val filter = IntentFilter().apply {
            addAction(ChargingIntents.ACTION_NOTIFY_CHARGING_TASK_MOVED_TO_REAR)
            addAction(ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(finishReceiver, filter)
        }

        // 若已在背屏（或系统直接把 task 放到背屏），立即拉起真实 UI。
        maybeStartRealChargingUi()

        // 主屏占位兜底：避免某些 ROM 在 resume 阶段强制要求 finish。
        mainHandler.postDelayed(mainDisplayTimeoutFinish, 1500L)
    }

    private fun maybeStartRealChargingUi() {
        if (isFinishing || realUiLaunched) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val d = display
            val did = d?.displayId ?: Display.DEFAULT_DISPLAY
            if (did == Display.DEFAULT_DISPLAY) {
                // 仍在主屏：等待 ChargingService 迁屏完成后的广播通知
                return
            }
        }
        realUiLaunched = true
        val next = Intent(this, RearScreenChargingActivity::class.java).apply {
            putExtras(intent ?: Intent())
            // 避免背屏端再触发系统默认转场
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(next)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        // 迁屏后并不一定会立即收到广播（或广播丢失），resume 时再判定一次，避免停留在占位页。
        maybeStartRealChargingUi()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(mainDisplayTimeoutFinish)
        try {
            unregisterReceiver(finishReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}

