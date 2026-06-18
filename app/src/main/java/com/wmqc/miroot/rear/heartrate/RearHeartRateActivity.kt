package com.wmqc.miroot.rear.heartrate

import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.wmqc.miroot.BottomSwipeExitHelper
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.RearComposeProjectionSetup
import com.wmqc.miroot.rear.RearMirootProjectionLifecycle

/**
 * 背屏蓝牙心率广播：接收手环/手表标准 Heart Rate Service 广播，红色脉搏动画显示 BPM。
 */
class RearHeartRateActivity : ComponentActivity() {

    private companion object {
        const val TAG = "RearHeartRate"
        const val REAR_DISPLAY_ID = 1
    }

    private var bottomSwipeHandler: BottomSwipeExitHelper.Handler? = null
    private val bottomSwipeExitCallback: () -> Unit = {
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "bottom swipe exit")
        }
        window.decorView.post {
            if (!isFinishing) {
                try {
                    window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
                    window.setWindowAnimations(0)
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    )
                } catch (_: Exception) {
                }
                finish()
            }
        }
    }

    private var hideSystemUiRetry = 0
    private val projectionSetup = RearComposeProjectionSetup.Session(TAG)
    private var contentInflated = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rearUiInitPollRunnable: Runnable? = null
    private var rearUiInitPollAttempts = 0

    private var heartRateMonitor: BleHeartRateMonitor? = null
    private var uiState by mutableStateOf(HeartRateUiState())
    private var monitorStarted = false
    private var mainPermissionFlowTriggered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            RearMirootProjectionLifecycle.getDisplayIdSafe(this) == Display.DEFAULT_DISPLAY
        ) {
            LogHelper.d(TAG, "在主屏启动，透明占位等待迁往背屏")
            RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(
                this,
                RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER,
            )
            schedulePollRearUiInit()
            return
        }
        inflateContentOnRear()
    }

    private fun schedulePollRearUiInit() {
        cancelRearUiInitPoll()
        rearUiInitPollAttempts = 0
        rearUiInitPollRunnable = Runnable { pollRearUiInitStep() }
        mainHandler.post(rearUiInitPollRunnable!!)
    }

    private fun cancelRearUiInitPoll() {
        rearUiInitPollRunnable?.let { mainHandler.removeCallbacks(it) }
        rearUiInitPollRunnable = null
        rearUiInitPollAttempts = 0
    }

    private fun pollRearUiInitStep() {
        rearUiInitPollRunnable = null
        if (isFinishing || isDestroyed) return
        if (contentInflated) return
        if (RearMirootProjectionLifecycle.getDisplayIdSafe(this) == REAR_DISPLAY_ID) {
            inflateContentOnRear()
            return
        }
        rearUiInitPollAttempts++
        if (rearUiInitPollAttempts < RearMirootProjectionLifecycle.REAR_UI_INIT_POLL_MAX_ATTEMPTS) {
            rearUiInitPollRunnable = Runnable { pollRearUiInitStep() }
            mainHandler.postDelayed(
                rearUiInitPollRunnable!!,
                RearMirootProjectionLifecycle.REAR_UI_INIT_POLL_INTERVAL_MS,
            )
        } else {
            LogHelper.w(TAG, "迁屏轮询超时仍未到背屏")
        }
    }

    private fun inflateContentOnRear() {
        if (contentInflated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            RearMirootProjectionLifecycle.getDisplayIdSafe(this) != REAR_DISPLAY_ID
        ) {
            return
        }
        contentInflated = true
        cancelRearUiInitPoll()
        RearComposeProjectionSetup.applyRearWindowFlags(this)
        RearComposeProjectionSetup.onRearProjectionStarted(this)
        window.setFormat(PixelFormat.OPAQUE)
        window.setBackgroundDrawableResource(android.R.color.black)

        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFFE53935),
                        onSurface = Color(0xFFE3E3E3),
                    ),
            ) {
                HeartRateBody()
            }
        }

        window.decorView.post {
            hideSystemUiRetry = 0
            hideSystemUi()
            ensureBleReady()
        }
    }

    @Composable
    private fun HeartRateBody() {
        RearHeartRateDisplay(
            state = uiState,
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
    }

    private fun ensureBleReady() {
        refreshBleEnvironment()
        if (!uiState.permissionsGranted || !uiState.bluetoothEnabled) {
            if (!mainPermissionFlowTriggered) {
                mainPermissionFlowTriggered = true
                HeartRateBleGate.startMainDisplayPermissionFlow(this)
            }
            return
        }
        startMonitorIfReady()
    }

    private fun refreshBleEnvironment() {
        val monitor = heartRateMonitor ?: BleHeartRateMonitor(this).also { heartRateMonitor = it }
        uiState =
            uiState.copy(
                permissionsGranted = BlePermissionHelper.hasAll(this),
                bluetoothEnabled = monitor.isBluetoothEnabled(),
            )
    }

    private fun startMonitorIfReady() {
        if (monitorStarted) return
        if (!uiState.permissionsGranted || !uiState.bluetoothEnabled) return
        val monitor = heartRateMonitor ?: BleHeartRateMonitor(this).also { heartRateMonitor = it }
        monitor.onReading = { reading ->
            uiState =
                uiState.copy(
                    bpm = reading.bpm,
                    deviceName = reading.deviceName,
                )
        }
        monitor.onStatus = { status ->
            uiState = uiState.copy(status = status)
        }
        monitor.start()
        monitorStarted = true
    }

    private fun stopMonitor() {
        monitorStarted = false
        heartRateMonitor?.stop()
    }

    override fun onResume() {
        super.onResume()
        val displayId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                RearMirootProjectionLifecycle.getDisplayIdSafe(this)
            } else {
                REAR_DISPLAY_ID
            }
        val mainMode =
            RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                displayId,
                false,
                contentInflated,
            )
        if (RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)) {
            if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
                finish()
            } else if (!contentInflated) {
                schedulePollRearUiInit()
            }
            return
        }
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        RearComposeProjectionSetup.updateKeepScreenOnWindowFlag(this)
        if (!contentInflated && displayId == REAR_DISPLAY_ID) {
            inflateContentOnRear()
        } else if (contentInflated) {
            refreshBleEnvironment()
            startMonitorIfReady()
        }
    }

    override fun onPause() {
        stopMonitor()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayId = RearMirootProjectionLifecycle.getDisplayIdSafe(this)
            val mainMode = RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                displayId, false, contentInflated,
            )
            if (RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)) {
                if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
                    finish()
                } else if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER
                    && contentInflated
                ) {
                    LogHelper.w(TAG, "onConfigurationChanged: 占位态仍在主屏且 UI 已 inflate，强制结束")
                    finishAndRemoveTask()
                }
                return
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        }
    }

    override fun onStart() {
        super.onStart()
        projectionSetup.onStart(this, RearHeartRateActivity::class.java)
    }

    override fun onDestroy() {
        cancelRearUiInitPoll()
        stopMonitor()
        heartRateMonitor = null
        projectionSetup.onDestroy(this, RearHeartRateActivity::class.java)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (getCurrentDisplayIdSafe() == REAR_DISPLAY_ID) {
            if (bottomSwipeHandler == null) {
                bottomSwipeHandler = BottomSwipeExitHelper.Handler(this, bottomSwipeExitCallback)
            }
            bottomSwipeHandler!!.handleTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hideSystemUi() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) {
            if (hideSystemUiRetry++ < 6) {
                decor.postDelayed({ hideSystemUi() }, 16L)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            decor.windowInsetsController?.let { c ->
                c.show(android.view.WindowInsets.Type.navigationBars())
                c.hide(android.view.WindowInsets.Type.statusBars())
                c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_DEFAULT
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun getCurrentDisplayIdSafe(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
        return try {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: Exception) {
            Display.DEFAULT_DISPLAY
        }
    }
}
