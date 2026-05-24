package com.wmqc.miroot.shell
import com.wmqc.miroot.display.MainDisplayUi

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.wmqc.miroot.AppExecutors
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.EnvironmentProbe

/**
 * 快捷设置磁贴：背屏截图；是否带壳与功能页「带壳截图」开关一致（需 Root 或 Shizuku）。
 */
class RearScreenshotQsTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val shell = DeviceGeometry.isScreenshotShellEnabled(this)
            tile.subtitle = getString(
                if (shell) R.string.tile_screenshot_subtitle_shell
                else R.string.tile_screenshot_subtitle_plain,
            )
        }
        tile.updateTile()
    }

    override fun onClick() {
        unlockAndRun {
            AppExecutors.runInBackground {
                val priv = privilegedShellAvailable()
                if (!priv) {
                    mainHandler.post {
                        MainDisplayUi.showToast(
                            this@RearScreenshotQsTileService,
                            R.string.privilege_shell_required,
                            Toast.LENGTH_LONG,
                        )
                    }
                    return@runInBackground
                }
                val composite = DeviceGeometry.isScreenshotShellEnabled(applicationContext)
                RearScreenshotCoordinator.capture(applicationContext, composite) { ok, msg ->
                    MainDisplayUi.showToast(
                        applicationContext,
                        msg,
                        if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    )
                }
            }
        }
    }

    private fun privilegedShellAvailable(): Boolean =
        EnvironmentProbe.hasPrivilegedShellChannelSync()
}
