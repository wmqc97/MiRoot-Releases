package com.wmqc.miroot.lyrics
import com.wmqc.miroot.display.MainDisplayUi

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.wmqc.miroot.AppExecutors
import com.wmqc.miroot.R
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.capability.PermissionCache
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.license.OfflineActivationRepository
import com.wmqc.miroot.ui.music.MusicProjectionController

/**
 * Quick settings tile: launch main screen landscape lyrics via MainScreenMusicActivity.
 * Independent from back screen RearScreenLyricsActivity.
 */
class MusicProjectionQsTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        applyTileState()
    }

    override fun onClick() {
        if (!isMainScreenMusicActive() && !OfflineActivationRepository.isActivated(applicationContext)) {
            MainDisplayUi.showToast(
                this@MusicProjectionQsTileService,
                R.string.activation_required_to_use,
                Toast.LENGTH_SHORT,
            )
            val intent = Intent(this@MusicProjectionQsTileService, MainActivity::class.java).apply {
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
            if (isMainScreenMusicActive()) {
                MainScreenMusicActivity.getCurrentInstance()?.let {
                    if (!it.isFinishing) it.finish()
                }
                scheduleRefreshTile()
                return@unlockAndRun
            }
            AppExecutors.runInBackground {
                if (!privilegedShellAvailable()) {
                    mainHandler.post {
                        MainDisplayUi.showToast(
                            this@MusicProjectionQsTileService,
                            R.string.privilege_shell_required,
                            Toast.LENGTH_LONG,
                        )
                    }
                    return@runInBackground
                }
                val cmp = ComponentName(
                    packageName,
                    MainScreenMusicActivity::class.java.name,
                ).flattenToString()
                val cmd =
                    "am start -n $cmp"
                PrivilegedShell.execCmd(cmd)
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
        val act = MainScreenMusicActivity.getCurrentInstance() ?: return false
        return !act.isFinishing && act.hasActiveLyricsUI()
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
        PermissionCache.privileged
}
