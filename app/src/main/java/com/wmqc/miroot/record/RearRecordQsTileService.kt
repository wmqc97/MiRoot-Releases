package com.wmqc.miroot.record
import com.wmqc.miroot.display.MainDisplayUi

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.capability.RuntimePermissionGate
import com.wmqc.miroot.license.OfflineActivationRepository

/**
 * 快捷设置磁贴：开关录屏悬浮窗（与旧版 RearScreenRecordTileService 一致）。
 */
class RearRecordQsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val active = RearScreenRecordService.isRunning()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.tile_record_subtitle)
        }
        tile.updateTile()
    }

    override fun onClick() {
        // 仅在“下一步是启动录屏服务”时拦截；若当前已在录制中，则允许点击磁贴停止（不依赖激活状态）。
        if (!RearScreenRecordService.isRunning() && !OfflineActivationRepository.isActivated(applicationContext)) {
            MainDisplayUi.showToast(
                applicationContext,
                R.string.activation_required_to_use,
                Toast.LENGTH_SHORT,
            )
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            startActivity(intent)
            return
        }
        unlockAndRun {
            if (!Settings.canDrawOverlays(this)) {
                MainDisplayUi.showToast(
                    applicationContext,
                    R.string.record_need_overlay,
                    Toast.LENGTH_LONG,
                )
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return@unlockAndRun
            }
            if (!RuntimePermissionGate.canPostNotifications(this)) {
                MainDisplayUi.showToast(
                    applicationContext,
                    R.string.record_need_post_notifications,
                    Toast.LENGTH_LONG,
                )
                startActivity(
                    RuntimePermissionGate.intentAppNotificationSettings(this).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
                )
                return@unlockAndRun
            }
            if (RearScreenRecordService.isRunning()) {
                if (RearScreenRecordService.isRecordingActive()) {
                    MainDisplayUi.showToast(
                        applicationContext,
                        R.string.record_stopping,
                        Toast.LENGTH_SHORT,
                    )
                }
                stopService(Intent(this, RearScreenRecordService::class.java))
                qsTile?.apply {
                    state = Tile.STATE_INACTIVE
                    updateTile()
                }
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, RearScreenRecordService::class.java),
                )
                qsTile?.apply {
                    state = Tile.STATE_ACTIVE
                    updateTile()
                }
            }
        }
    }
}
