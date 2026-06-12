package com.wmqc.miroot.rear

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RearScreenWakeManager
import com.wmqc.miroot.lyrics.RearScreenWakeService
import com.wmqc.miroot.lyrics.RootTaskServiceConnector

/**
 * 背屏 Compose 投屏页（平衡球 / 心率 / 真心话等）与音乐/车控投屏对齐：
 * 按设置注册常亮、Keeper 初始杀官方背屏中心，并在落屏后巩固禁用官方背屏手势。
 */
object RearComposeProjectionSetup {

    private const val REAR_DISPLAY_ID = RearMirootProjectionLifecycle.REAR_DISPLAY_ID

    fun applyRearWindowFlags(activity: ComponentActivity) {
        RearMirootProjectionLifecycle.applyRearOpaqueWindowBase(activity)
        updateKeepScreenOnWindowFlag(activity)
        @Suppress("DEPRECATION")
        activity.window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(activity)
    }

    fun updateKeepScreenOnWindowFlag(activity: Activity) {
        @Suppress("DEPRECATION")
        if (RearAssistPrefs.isKeepScreenOnEnabled(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** 背屏内容就绪：系统返回手势 + TaskService 已连时再次巩固禁用官方背屏中心。 */
    fun onRearProjectionStarted(activity: Activity) {
        RearMirootProjectionLifecycle.primeRearSystemBackGestures(activity)
        RearMirootProjectionLifecycle.reinforceOfficialSubscreenDisabled(activity.applicationContext)
    }

    class Session(private val logTag: String) {
        private var keeperMonitorKey: String? = null
        private var keepScreenOnPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

        fun onStart(activity: ComponentActivity, wakeActivityClass: Class<*>) {
            registerKeepScreenOnPrefsListener(activity, wakeActivityClass)
            applyKeepScreenWakeFromPrefs(activity, wakeActivityClass)
            startKeeperSessionIfNeeded(activity)
            if (getDisplayIdSafe(activity) == REAR_DISPLAY_ID) {
                onRearProjectionStarted(activity)
            }
        }

        fun onDestroy(activity: ComponentActivity, wakeActivityClass: Class<*>) {
            val shouldRestoreOfficial = keeperMonitorKey != null
            unregisterKeepScreenOnPrefsListener(activity)
            stopWakeService(activity, wakeActivityClass)
            releaseKeeperSessionIfNeeded(activity)
            if (shouldRestoreOfficial) {
                RearMirootProjectionLifecycle.scheduleOfficialSubscreenRestoreAfterDestroy(
                    activity.applicationContext,
                    RootTaskServiceConnector.getIfConnected(),
                )
            }
        }

        private fun registerKeepScreenOnPrefsListener(
            activity: ComponentActivity,
            wakeActivityClass: Class<*>,
        ) {
            if (keepScreenOnPrefsListener != null) return
            try {
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (RearAssistPrefs.KEY_KEEP_SCREEN_ON == key) {
                            activity.window.decorView.post {
                                applyKeepScreenWakeFromPrefs(activity, wakeActivityClass)
                                updateKeepScreenOnWindowFlag(activity)
                            }
                        }
                    }
                keepScreenOnPrefsListener = listener
                RearAssistPrefs.prefs(activity).registerOnSharedPreferenceChangeListener(listener)
            } catch (e: Exception) {
                LogHelper.w(logTag, "register keep-screen prefs: ${e.message}")
                keepScreenOnPrefsListener = null
            }
        }

        private fun unregisterKeepScreenOnPrefsListener(activity: ComponentActivity) {
            val listener = keepScreenOnPrefsListener ?: return
            keepScreenOnPrefsListener = null
            try {
                RearAssistPrefs.prefs(activity).unregisterOnSharedPreferenceChangeListener(listener)
            } catch (_: Exception) {
            }
        }

        private fun applyKeepScreenWakeFromPrefs(
            activity: ComponentActivity,
            wakeActivityClass: Class<*>,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            if (getDisplayIdSafe(activity) != REAR_DISPLAY_ID || activity.isFinishing) return
            val keepOn = RearAssistPrefs.isKeepScreenOnEnabled(activity)
            try {
                if (keepOn) {
                    RearScreenWakeManager.getInstance()
                        .startWakeService(activity.applicationContext, wakeActivityClass)
                    RearScreenWakeService.requestNotificationRefresh(activity)
                } else {
                    RearScreenWakeManager.getInstance()
                        .stopWakeService(activity.applicationContext, wakeActivityClass)
                    if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                        activity.applicationContext.stopService(
                            Intent(activity.applicationContext, RearScreenWakeService::class.java),
                        )
                    }
                }
            } catch (e: Exception) {
                LogHelper.w(logTag, "apply keep-screen wake: ${e.message}")
            }
            updateKeepScreenOnWindowFlag(activity)
        }

        private fun stopWakeService(activity: ComponentActivity, wakeActivityClass: Class<*>) {
            try {
                RearScreenWakeManager.getInstance().stopWakeService(activity, wakeActivityClass)
                if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                    activity.applicationContext.stopService(
                        Intent(activity.applicationContext, RearScreenWakeService::class.java),
                    )
                }
            } catch (e: Exception) {
                LogHelper.w(logTag, "stop wake service: ${e.message}")
            }
        }

        private fun startKeeperSessionIfNeeded(activity: ComponentActivity) {
            if (keeperMonitorKey != null) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            if (getDisplayIdSafe(activity) != REAR_DISPLAY_ID) return
            val key = "${activity.packageName}:${activity.taskId}"
            keeperMonitorKey = key
            val keeper =
                Intent(activity, RearSwitchKeeperService::class.java).apply {
                    putExtra("lastMovedTask", key)
                    putExtra("keepScreenOnEnabled", RearAssistPrefs.isKeepScreenOnEnabled(activity))
                    putExtra(RearSwitchKeeperService.EXTRA_SKIP_INITIAL_LAUNCHER_KILLS, false)
                }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(keeper)
                } else {
                    @Suppress("DEPRECATION")
                    activity.startService(keeper)
                }
            } catch (e: Exception) {
                LogHelper.w(logTag, "start keeper: ${e.message}")
                keeperMonitorKey = null
            }
        }

        private fun releaseKeeperSessionIfNeeded(activity: ComponentActivity) {
            val key = keeperMonitorKey ?: return
            keeperMonitorKey = null
            try {
                val release =
                    Intent(activity, RearSwitchKeeperService::class.java).apply {
                        action = RearSwitchKeeperService.ACTION_RELEASE_MONITOR_IF_MATCH
                        putExtra(RearSwitchKeeperService.EXTRA_MONITOR_KEY, key)
                    }
                activity.startService(release)
            } catch (e: Exception) {
                LogHelper.w(logTag, "release keeper: ${e.message}")
            }
        }

        private fun getDisplayIdSafe(activity: Activity): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
            return try {
                activity.display?.displayId ?: Display.DEFAULT_DISPLAY
            } catch (_: Exception) {
                Display.DEFAULT_DISPLAY
            }
        }
    }
}
