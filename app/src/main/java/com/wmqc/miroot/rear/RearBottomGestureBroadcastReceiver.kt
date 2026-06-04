package com.wmqc.miroot.rear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wmqc.miroot.car.CarControlDeviceGate
import com.wmqc.miroot.car.CarControlIntents
import com.wmqc.miroot.charging.ChargingPreviewLauncher
import com.wmqc.miroot.charging.ChargingService
import com.wmqc.miroot.car.CarControlProjectionService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.LyricsIntents
import com.wmqc.miroot.lyrics.MusicProjectionService
import com.wmqc.miroot.lyrics.RootTaskServiceConnector
import com.wmqc.miroot.ui.music.MusicProjectionController
import com.wmqc.miroot.rear.desktop.RearDesktopIntents
import com.wmqc.miroot.rear.desktop.RearDesktopLaunchService
import kotlin.math.roundToInt

/**
 * 背屏主题注入发出的三槽位手势广播：[RearBottomGestureIntents.ACTION_REAR_BOTTOM_GESTURE] +
 * [RearBottomGestureIntents.EXTRA_GESTURE_SLOT] 1/2/3。
 */
class RearBottomGestureBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (intent.action != RearBottomGestureIntents.ACTION_REAR_BOTTOM_GESTURE) return
        val slot = parseGestureSlot(intent)
        if (slot !in 1..3) {
            LogHelper.w(TAG, "invalid slot=$slot")
            return
        }
        val app = context.applicationContext
        RootTaskServiceConnector.prewarm(app)
        val spec = RearGesturePrefs.readInjectSpec(app)
        val gestureAction = spec.slotAction(slot)
        if (gestureAction == RearGestureAction.NONE) {
            return
        }
        LogHelper.d(TAG, "gesture slot=$slot action=$gestureAction")
        when (gestureAction) {
            RearGestureAction.NONE -> Unit
            RearGestureAction.MUSIC_LYRICS -> startMusicProjection(app)
            RearGestureAction.REAR_DESKTOP -> openRearDesktop(app)
            RearGestureAction.CAR_CONTROL -> openCarProjection(app)
            RearGestureAction.LAUNCH_APP -> {
                val pkg = spec.launchPackage(slot)
                if (pkg.isEmpty()) return
                try {
                    app.startService(
                        Intent(app, RearAppLaunchService::class.java).apply {
                            setAction(RearAppLaunchService.ACTION_LAUNCH_APP_ON_REAR)
                            putExtra(RearAppLaunchService.EXTRA_PACKAGE_NAME, pkg)
                        },
                    )
                } catch (e: Exception) {
                    LogHelper.e(TAG, "launch app failed pkg=$pkg", e)
                }
            }
            RearGestureAction.FOREGROUND_APP_TO_REAR -> startForegroundAppProjectionLikeTile(app)
            RearGestureAction.CHARGING_PREVIEW -> startChargingPreview(app)
        }
    }

    /** 与 [AppProjectionBroadcastReceiver] / 磁贴「切换至背屏」同源：[AppProjectionService] + [ForegroundAppRearSwitcher]。 */
    private fun startForegroundAppProjectionLikeTile(app: Context) {
        try {
            app.startService(
                Intent(app, AppProjectionService::class.java).apply {
                    action = AppProjectionIntents.ACTION_APP_PROJECTION
                    putExtra(AppProjectionIntents.EXTRA_APP_PROJECTION_OP, AppProjectionIntents.OP_START)
                },
            )
        } catch (e: Exception) {
            LogHelper.e(TAG, "foreground app to rear (tile parity) failed", e)
        }
    }

    private fun startMusicProjection(app: Context) {
        val playing = MusicProjectionController.hasAnyPlayingSession(app)
        if (playing == false) {
            LogHelper.d(TAG, "music gesture ignored: no active playing media session")
            return
        }
        try {
            val svc =
                Intent(app, MusicProjectionService::class.java).apply {
                    action = LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION
                    putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP, LyricsIntents.VALUE_MUSIC_PROJECTION_OP_START)
                }
            app.startService(svc)
        } catch (e: Exception) {
            LogHelper.e(TAG, "music projection start failed", e)
        }
    }

    private fun openRearDesktop(app: Context) {
        try {
            app.startService(
                Intent(app, RearDesktopLaunchService::class.java).apply {
                    setAction(RearDesktopIntents.ACTION_OPEN_REAR_DESKTOP)
                },
            )
        } catch (e: Exception) {
            LogHelper.e(TAG, "rear desktop launch failed", e)
        }
    }

    /** 与功能页长按预览一致：需 [ChargingService] 处理广播，不强制充电动画总开关开启。 */
    private fun startChargingPreview(app: Context) {
        try {
            val svc = Intent(app, ChargingService::class.java)
            try {
                ContextCompat.startForegroundService(app, svc)
            } catch (_: Exception) {
                app.startService(svc)
            }
            ChargingPreviewLauncher.requestPreview(app)
        } catch (e: Exception) {
            LogHelper.e(TAG, "charging preview failed", e)
        }
    }

    private fun openCarProjection(app: Context) {
        if (!CarControlDeviceGate.isAllowed(app)) {
            LogHelper.w(TAG, "car gesture ignored: device not allowed")
            return
        }
        try {
            app.startService(
                Intent(app, CarControlProjectionService::class.java).apply {
                    action = CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION
                    putExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP, CarControlIntents.VALUE_CAR_PROJECTION_OP_START)
                },
            )
        } catch (e: Exception) {
            LogHelper.e(TAG, "car projection start failed", e)
        }
    }

    private fun parseGestureSlot(intent: Intent): Int {
        val key = RearBottomGestureIntents.EXTRA_GESTURE_SLOT
        when (val raw = intent.extras?.get(key)) {
            null -> Unit
            is Int -> if (raw in 1..3) return raw
            is Long -> if (raw in 1..3L) return raw.toInt()
            is Short -> if (raw.toInt() in 1..3) return raw.toInt()
            is Byte -> if (raw.toInt() in 1..3) return raw.toInt()
            is String -> raw.trim().toIntOrNull()?.let { if (it in 1..3) return it }
            is Double -> {
                val v = raw.roundToInt()
                if (v in 1..3) return v
            }
            is Float -> {
                val v = raw.roundToInt()
                if (v in 1..3) return v
            }
            is Number -> {
                val v = raw.toDouble().roundToInt()
                if (v in 1..3) return v
            }
            else -> LogHelper.w(TAG, "gesture slot extra unexpected type=${raw.javaClass.name}")
        }
        return -1
    }

    private companion object {
        private const val TAG = "RearBottomGestureRcvr"
    }
}
