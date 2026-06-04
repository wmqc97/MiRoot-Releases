package com.wmqc.miroot.record

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi

/**
 * 透明页：向系统请求 [MediaProjection]，
 * 授权后把 result 交给已在运行的 [RearScreenRecordService] 再启动 screenrecord + [AudioCaptureHelper]。
 * 使用独立 [android:taskAffinity]，避免与 [MainActivity] 同任务导致弹系统授权时误把 MiRoot 主界面拉到前台。
 *
 * 使用 [ActivityResultContracts] 而非 [startActivityForResult]，避免系统向 [MainActivity] 分发结果时出现
 * `deliverResultsIfNeeded` / 空 Bundle 相关 NPE（见设备 log）。
 *
 * **关于弹窗与范围**（普通应用无法规避）：
 * - Android 对屏幕采集有强制用户确认，**不能**在应用内保存 consent 以永久免弹窗；
 *   系统应用 / Device Owner 等特权场景除外，MiRoot 作为用户应用每次新 token 均需授权。
 * - [MediaProjectionManager.createScreenCaptureIntent] 的「录制整个屏幕 / 单应用」由**系统界面**决定，
 *   应用侧无法通过公开 API 改为「仅共享全屏」；内录 [AudioPlaybackCaptureConfiguration] 匹配的是全局播放流（USAGE 等），
 *   与是否「整屏投屏」无单独开关。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionRequestActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val granted = result.resultCode == RESULT_OK && data != null
        notifyService(granted, result.resultCode, data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    notifyService(granted = false, resultCode = RESULT_CANCELED, data = null)
                    finish()
                }
            },
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finish()
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager?
        if (mgr == null) {
            notifyService(granted = false, resultCode = RESULT_CANCELED, data = null)
            finish()
            return
        }
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun notifyService(granted: Boolean, resultCode: Int, data: Intent?) {
        val i = Intent(this, RearScreenRecordService::class.java).setPackage(packageName)
        if (granted && data != null) {
            i.action = RearScreenRecordService.ACTION_START_WITH_PROJECTION
            i.putExtra(RearScreenRecordService.EXTRA_MEDIA_PROJECTION_RESULT_CODE, resultCode)
            i.putExtra(RearScreenRecordService.EXTRA_MEDIA_PROJECTION_DATA, data)
        } else {
            i.action = RearScreenRecordService.ACTION_PROJECTION_CANCELLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(i)
            } catch (t: Throwable) {
                RecordSynthDebugLog.diagW("notifyService startForegroundService failed: ${t.message}", t)
                startService(i)
            }
        } else {
            startService(i)
        }
    }
}
