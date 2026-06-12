package com.wmqc.miroot.rear.balance

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import com.wmqc.miroot.BottomSwipeExitHelper
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.RearComposeProjectionSetup
import com.wmqc.miroot.rear.RearMirootProjectionLifecycle

/**
 * 背屏平衡球小游戏：倾斜手机控制小球，尽量长时间留在圆形竞技区内。
 * 底部左右滑退出，与背屏桌面/歌词投屏一致。
 */
class RearBalanceGameActivity : ComponentActivity() {

    private companion object {
        const val TAG = "RearBalanceGame"
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
                        primary = Color(0xFF4FC3F7),
                        onSurface = Color(0xFFE3E3E3),
                    ),
            ) {
                GameBody()
            }
        }

        window.decorView.post {
            hideSystemUiRetry = 0
            hideSystemUi()
        }
    }

    @Composable
    private fun GameBody() {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val cutoutLeftDp =
            WindowInsets.displayCutout
                .asPaddingValues()
                .calculateLeftPadding(layoutDirection)
        val minSafeStartDp = with(density) { BALANCE_GAME_SAFE_LEFT_PX.toDp() }
        val safeStartDp = minSafeStartDp.coerceAtLeast(cutoutLeftDp)
        val safeStartPx = with(density) { safeStartDp.toPx() }
        BalanceBallGame(safeStartPx = safeStartPx)
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
        projectionSetup.onStart(this, RearBalanceGameActivity::class.java)
    }

    override fun onDestroy() {
        cancelRearUiInitPoll()
        projectionSetup.onDestroy(this, RearBalanceGameActivity::class.java)
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
