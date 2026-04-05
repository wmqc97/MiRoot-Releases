package com.wmqc.miroot.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskService
import com.wmqc.miroot.rear.ForegroundAppRearSwitcher

/**
 * 快捷设置磁贴：将当前前台应用切换到背屏。
 * 迁屏磁贴：通过 [ITaskService.moveTaskToDisplay] 将任务切到 displayId 1（背屏）。
 */
class SwitchToRearQsTileService : TileService() {

    companion object {
        private const val TAG = "SwitchToRearQsTile"

        @Volatile
        private var lastMovedTask: String? = null

        @JvmStatic
        fun getLastMovedTask(): String? = lastMovedTask

        @JvmStatic
        fun clearLastMovedTask() {
            lastMovedTask = null
        }

        @JvmStatic
        fun recordLastMovedTask(spec: String) {
            lastMovedTask = spec
        }
    }

    private var taskService: ITaskService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val taskServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            taskService = ITaskService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            taskService = null
            scheduleReconnectTaskService()
        }
    }

    private val reconnectTaskServiceRunnable: Runnable = object : Runnable {
        override fun run() {
            if (taskService == null) {
                bindTaskService()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun scheduleReconnectTaskService() {
        mainHandler.postDelayed(reconnectTaskServiceRunnable, 200)
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                stateDescription = ""
            }
            updateTile()
        }
        bindTaskService()
    }

    override fun onStopListening() {
        super.onStopListening()
        mainHandler.removeCallbacks(reconnectTaskServiceRunnable)
        unbindTaskService()
    }

    override fun onClick() {
        super.onClick()
        switchCurrentAppToRearDisplay()
    }

    private fun bindTaskService() {
        if (taskService != null) return
        try {
            val intent = Intent(this, RootTaskService::class.java)
            bindService(intent, taskServiceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            LogHelper.e(TAG, "Failed to bind TaskService", e)
        }
    }

    private fun unbindTaskService() {
        if (taskService != null) {
            try {
                unbindService(taskServiceConnection)
            } catch (e: Exception) {
                LogHelper.e(TAG, "Error unbinding TaskService", e)
            }
            taskService = null
        }
    }

    private fun switchCurrentAppToRearDisplay() {
        if (taskService == null) {
            LogHelper.w(TAG, "TaskService not available!")
            showTemporaryFeedback(getString(R.string.tile_switch_feedback_service_waiting))
            bindTaskService()
            mainHandler.postDelayed({
                if (taskService != null) {
                    performSwitch()
                } else {
                    Toast.makeText(
                        applicationContext,
                        R.string.tile_switch_toast_open_app_auth,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }, 1000)
            return
        }
        performSwitch()
    }

    private fun performSwitch() {
        mainHandler.post {
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = getString(R.string.tile_switch_subtitle_switching)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    stateDescription = ""
                }
                updateTile()
            }

            val ts = taskService
            if (ts == null) {
                showTemporaryFeedback(getString(R.string.tile_switch_feedback_service_waiting))
                return@post
            }

            val ctx = this@SwitchToRearQsTileService
            ForegroundAppRearSwitcher.switchCurrentForegroundToRear(ctx, ts, mainHandler) { msg ->
                showTemporaryFeedback(msg)
            }
        }
    }

    private fun showTemporaryFeedback(message: String) {
        mainHandler.post {
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = message
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    stateDescription = ""
                }
                updateTile()
            }
            mainHandler.postDelayed({
                qsTile?.apply {
                    state = Tile.STATE_INACTIVE
                    subtitle = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        stateDescription = ""
                    }
                    updateTile()
                }
            }, 1500)
        }
    }
}
