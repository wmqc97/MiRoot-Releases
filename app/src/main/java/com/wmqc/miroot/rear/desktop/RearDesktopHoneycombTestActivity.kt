package com.wmqc.miroot.rear.desktop

import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wmqc.miroot.BottomSwipeExitHelper
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.RearAppLaunchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 蜂窝全量列表调试页：与 [RearScreenDesktopActivity] 相同的窗口与触摸处理，仅固定展示
 * [RearDesktopHoneycombScreen]，数据源为 [RearDesktopRepository.resolveAllHoneycombAppsIgnoringListMode]。
 * 设置页默认在主屏打开；若用 `am start --display 1` 等方式在背屏打开，底部横滑退出仍仅在 display 1 生效。
 */
class RearDesktopHoneycombTestActivity : ComponentActivity() {

    private companion object {
        const val TAG = "RearHoneycombTest"
        const val REAR_DISPLAY_ID = 1
    }

    private val bottomSwipeHandler = BottomSwipeExitHelper.Handler(this) {
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
                } catch (_: Exception) {}
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyRearWindowFlags()
        window.setFormat(PixelFormat.OPAQUE)
        window.setBackgroundDrawableResource(android.R.color.black)

        setContent {
            val scheme =
                darkColorScheme(
                    primary = Color(0xFF90CAF9),
                    onSurface = Color(0xFFE3E3E3),
                )
            MaterialTheme(colorScheme = scheme) {
                val density = LocalDensity.current
                val layoutDirection = LocalLayoutDirection.current
                val cutoutLeftDp =
                    WindowInsets.displayCutout
                        .asPaddingValues()
                        .calculateLeftPadding(layoutDirection)
                val minSafeStartDp = with(density) { REAR_DESKTOP_SAFE_LEFT_PX.toDp() }
                val safeStartDp = minSafeStartDp.coerceAtLeast(cutoutLeftDp)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(start = safeStartDp)
                        .clip(RectangleShape),
                ) {
                    TestBody()
                }
            }
        }

        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    @Composable
    private fun TestBody() {
        val ctx = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var apps by remember { mutableStateOf<List<RearDesktopAppEntry>?>(null) }
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val next =
                    withContext(Dispatchers.Default) {
                        RearDesktopRepository.resolveAllHoneycombAppsIgnoringListMode(ctx)
                    }
                apps = next
            }
        }
        RearDesktopHoneycombScreen(
            apps = apps,
            onLaunchApp = { pkg -> launchAppOnCurrentDisplay(pkg) },
            emptyHint = ctx.getString(R.string.rear_desktop_empty_hint),
        )
    }

    private fun launchAppOnCurrentDisplay(packageName: String) {
        RearDesktopPrefs.recordLaunch(applicationContext, packageName)
        try {
            val app = applicationContext
            val svc =
                Intent(app, RearAppLaunchService::class.java).apply {
                    action = RearAppLaunchService.ACTION_LAUNCH_APP_ON_REAR
                    putExtra(RearAppLaunchService.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(RearAppLaunchService.EXTRA_LAUNCH_FROM_REAR_DESKTOP, true)
                }
            app.startService(svc)
        } catch (e: Exception) {
            LogHelper.w(TAG, "rear launch service failed: $packageName — ${e.message}")
        }
    }

    private fun applyRearWindowFlags() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = lp
        }
    }

    private fun hideSystemUi() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            decor.windowInsetsController?.let { c ->
                c.show(android.view.WindowInsets.Type.navigationBars())
                c.hide(android.view.WindowInsets.Type.statusBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_DEFAULT
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (getCurrentDisplayIdSafe() == REAR_DISPLAY_ID) {
            bottomSwipeHandler.handleTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
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
