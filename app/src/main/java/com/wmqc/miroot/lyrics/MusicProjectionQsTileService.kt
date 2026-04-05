package com.wmqc.miroot.lyrics

import android.content.ComponentName
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.ui.music.MusicProjectionController
import kotlin.concurrent.thread

/**
 * 快捷设置磁贴：在主屏以横屏全屏打开音乐歌词界面（Intent 带 `isMainScreenLandscape`）。
 */
class MusicProjectionQsTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        applyTileState()
    }

    override fun onClick() {
        unlockAndRun {
            if (isMainScreenMusicActive()) {
                MusicProjectionController.stop(this)
                scheduleRefreshTile()
                return@unlockAndRun
            }
            thread(name = "MiRoot-QS-MainMusic") {
                if (!privilegedShellAvailable()) {
                    mainHandler.post {
                        Toast.makeText(
                            this@MusicProjectionQsTileService,
                            R.string.privilege_shell_required,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@thread
                }
                if (RearScreenLyricsActivity.hasConflictingLyricsActivityForMainScreenTile()) {
                    MusicProjectionController.stop(applicationContext)
                    Thread.sleep(800)
                }
                val cmp = ComponentName(
                    packageName,
                    RearScreenLyricsActivity::class.java.name,
                ).flattenToString()
                val cmd =
                    "am start -n $cmp --ez isMainScreenLandscape true --ez isBroadcast true"
                PrivilegedShell.runAndWait(cmd)
                mainHandler.post { scheduleRefreshTile() }
            }
        }
    }

    private fun scheduleRefreshTile() {
        applyTileState()
        mainHandler.postDelayed({ applyTileState() }, 600)
        mainHandler.postDelayed({ applyTileState() }, 2200)
    }

    private fun isMainScreenMusicActive(): Boolean {
        val act = RearScreenLyricsActivity.getCurrentInstance() ?: return false
        return !act.isFinishing && act.isMainScreenLandscapeLyricsActive()
    }

    private fun applyTileState() {
        val tile = qsTile ?: return
        val on = isMainScreenMusicActive()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.tile_music_subtitle)
        }
        tile.updateTile()
    }

    private fun privilegedShellAvailable(): Boolean =
        EnvironmentProbe.hasPrivilegedShellChannelSync()
}
