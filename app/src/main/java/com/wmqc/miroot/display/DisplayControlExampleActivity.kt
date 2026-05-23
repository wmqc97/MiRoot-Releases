package com.wmqc.miroot.display

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wmqc.miroot.lyrics.LogHelper
import kotlinx.coroutines.launch

/**
 * 示例 Activity：
 * - onCreate 保存原始配置
 * - 进入背屏/投屏时应用自定义 DPI + 旋转（这里用 onStart 模拟“开始投屏/进入背屏”）
 * - onDestroy 自动恢复
 *
 * 说明：
 * - 小米背屏通常是 displayId=1；若你的设备分配不同，请改 EXTRA_DISPLAY_ID。
 * - 真实业务中你可以把 apply/restore 放在「投屏开始/结束」的明确事件点调用。
 */
class DisplayControlExampleActivity : AppCompatActivity() {

    private lateinit var mgr: DisplayControlManager

    private var appliedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent?.getIntExtra(EXTRA_DISPLAY_ID, DEFAULT_REAR_DISPLAY_ID) ?: DEFAULT_REAR_DISPLAY_ID
        val dpi = intent?.getIntExtra(EXTRA_DPI, DEFAULT_DPI) ?: DEFAULT_DPI
        val deg = intent?.getIntExtra(EXTRA_ROTATION_DEG, DEFAULT_ROTATION_DEG) ?: DEFAULT_ROTATION_DEG

        mgr = DisplayControlManager(displayId = displayId)

        val tv = TextView(this).apply {
            text =
                "DisplayControlExampleActivity\n" +
                "displayId=$displayId\n" +
                "targetDpi=$dpi\n" +
                "targetRotationDeg=$deg\n" +
                "\n" +
                "生命周期：onCreate capture → onStart apply → onDestroy restore"
            setPadding(48, 48, 48, 48)
        }
        setContentView(tv)

        lifecycleScope.launch {
            val snap = mgr.captureOriginal()
            LogHelper.d(TAG, "captureOriginal done: $snap")
        }
    }

    override fun onStart() {
        super.onStart()
        if (appliedOnce) return
        appliedOnce = true

        val dpi = intent?.getIntExtra(EXTRA_DPI, DEFAULT_DPI) ?: DEFAULT_DPI
        val deg = intent?.getIntExtra(EXTRA_ROTATION_DEG, DEFAULT_ROTATION_DEG) ?: DEFAULT_ROTATION_DEG

        lifecycleScope.launch {
            val r = mgr.applyCustom(dpi = dpi, rotationDegrees = deg, disableAutoRotation = true)
            LogHelper.d(TAG, "applyCustom result: $r")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            val r = mgr.restoreOriginal()
            LogHelper.d(TAG, "restoreOriginal result: $r")
        }
    }

    companion object {
        private const val TAG = "DisplayControlExample"

        const val EXTRA_DISPLAY_ID = "displayId"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_ROTATION_DEG = "rotationDeg"

        private const val DEFAULT_REAR_DISPLAY_ID = 1
        private const val DEFAULT_DPI = 420
        private const val DEFAULT_ROTATION_DEG = 0
    }
}

