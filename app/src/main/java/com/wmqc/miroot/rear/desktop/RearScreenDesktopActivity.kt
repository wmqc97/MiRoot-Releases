package com.wmqc.miroot.rear.desktop

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
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
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wmqc.miroot.rear.AppProjectionDisplayPrefs
import com.wmqc.miroot.rear.RearAppLaunchService
import com.wmqc.miroot.lyrics.RearScreenWakeManager
import com.wmqc.miroot.lyrics.RearScreenWakeService
import com.wmqc.miroot.rear.RearAssistPrefs
import com.wmqc.miroot.rear.RearMirootProjectionLifecycle
import com.wmqc.miroot.rear.RearSwitchKeeperService
import android.os.Handler
import android.os.Looper
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskServiceConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 背屏桌面 → [RearAppLaunchService] 交接：桌面 [onDestroy] 勿立刻向 [RearSwitchKeeperService] 发
 * [RearSwitchKeeperService.ACTION_RELEASE_MONITOR_IF_MATCH]，否则 Keeper 仍监控桌面 task 时会误走
 * [RearSwitchKeeperService.performUnifiedExit]，投屏被立刻收口。由启动服务在条末 [end] 清除标志。
 */
internal object RearDesktopToAppLaunchHandoff {
    private val active = AtomicBoolean(false)

    fun begin() {
        active.set(true)
    }

    fun end() {
        active.set(false)
    }

    fun shouldSkipKeeperRelease(): Boolean = active.get()
}

/**
 * 背屏应用桌面。设计基准约 **976×596**；左侧至少预留 [REAR_DESKTOP_SAFE_LEFT_PX]（277px）避让摄像头，
 * 实际边距取 max（刘海/displayCutout，277px），列表内容仅在右侧有效区内绘制、经 clip 不侵入遮挡带。
 * 「全部应用」为固定 4 列蜂窝交错网格（竖直位置决定圆形图标缩放、橡皮筋回弹，见 [RearDesktopHoneycombScreen]）；「自选应用」为底部单环转盘。
 * 底部窄条左右滑退出，与歌词/车控投屏一致。
 */
class RearScreenDesktopActivity : ComponentActivity() {

    private companion object {
        const val TAG = "RearDesktop"
        const val REAR_DISPLAY_ID = 1

        const val BOTTOM_SWIPE_EXIT_ZONE_FRACTION = 0.10f
        const val BOTTOM_SWIPE_EXIT_MIN_HORIZ_DP = 48f
        const val BOTTOM_SWIPE_EXIT_HORIZONTAL_DOMINANCE = 1.35f
    }

    private var bottomSwipeExitPointerDownInZone = false
    private var bottomSwipeExitStartY = 0f
    private var bottomSwipeExitStartX = 0f
    private var bottomSwipeExitPending = false

    /**
     * 某些 ROM/迁屏瞬间 decor 尺寸会短暂为 0（高度为 0 时 InsetsSource 可能刷屏警告）。
     * 这里做“延迟到布局完成再沉浸式”的轻量兜底，不影响任何业务逻辑。
     */
    private var hideSystemUiRetry = 0

    /** 本 Activity 对应 Keeper 监控 key（pkg:taskId）；用于退出时仅释放本会话。 */
    private var rearDesktopKeeperMonitorKey: String? = null

    private var keepScreenOnPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var desktopContentInflated = false
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
            schedulePollRearDesktopUiInit()
            return
        }

        inflateDesktopContentOnRear()
    }

    private fun schedulePollRearDesktopUiInit() {
        cancelRearDesktopUiInitPoll()
        rearUiInitPollAttempts = 0
        rearUiInitPollRunnable = Runnable { pollRearDesktopUiInitStep() }
        mainHandler.post(rearUiInitPollRunnable!!)
    }

    private fun cancelRearDesktopUiInitPoll() {
        rearUiInitPollRunnable?.let { mainHandler.removeCallbacks(it) }
        rearUiInitPollRunnable = null
        rearUiInitPollAttempts = 0
    }

    private fun pollRearDesktopUiInitStep() {
        rearUiInitPollRunnable = null
        if (isFinishing || isDestroyed) return
        if (desktopContentInflated) return
        if (RearMirootProjectionLifecycle.getDisplayIdSafe(this) == REAR_DISPLAY_ID) {
            inflateDesktopContentOnRear()
            return
        }
        rearUiInitPollAttempts++
        if (rearUiInitPollAttempts < RearMirootProjectionLifecycle.REAR_UI_INIT_POLL_MAX_ATTEMPTS) {
            rearUiInitPollRunnable = Runnable { pollRearDesktopUiInitStep() }
            mainHandler.postDelayed(
                rearUiInitPollRunnable!!,
                RearMirootProjectionLifecycle.REAR_UI_INIT_POLL_INTERVAL_MS,
            )
        } else {
            LogHelper.w(TAG, "迁屏轮询超时仍未到背屏")
        }
    }

    private fun inflateDesktopContentOnRear() {
        if (desktopContentInflated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            RearMirootProjectionLifecycle.getDisplayIdSafe(this) != REAR_DISPLAY_ID
        ) {
            return
        }
        desktopContentInflated = true
        cancelRearDesktopUiInitPoll()
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
                        // 子项可用负 offset 画出 padding 区域；裁剪到安全内容区，彻底避开左侧摄像头带。
                        .clip(RectangleShape),
                ) {
                    DesktopBody()
                }
            }
        }

        // 延迟到 decor 完成一次测量后再执行沉浸式，避免极短暂的 0×0 尺寸触发系统 InsetsSource 警告刷屏。
        window.decorView.post {
            hideSystemUiRetry = 0
            hideSystemUi()
        }
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
                desktopContentInflated,
            )
        if (RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)) {
            if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
                LogHelper.w(TAG, "主屏禁止展示背屏桌面 UI，结束")
                finish()
            } else if (!desktopContentInflated) {
                schedulePollRearDesktopUiInit()
            }
            return
        }
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        updateRearKeepScreenOnWindowFlag()
        if (!desktopContentInflated && displayId == REAR_DISPLAY_ID) {
            inflateDesktopContentOnRear()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayId = RearMirootProjectionLifecycle.getDisplayIdSafe(this)
            val mainMode =
                RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                    displayId,
                    false,
                    desktopContentInflated,
                )
            if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
                RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)
                finish()
                return
            }
            if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER) {
                RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)
                return
            }
            if (displayId == REAR_DISPLAY_ID && !desktopContentInflated) {
                inflateDesktopContentOnRear()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        }
    }

    override fun finish() {
        RearMirootProjectionLifecycle.sendMainDisplayHomeBeforeProjectionEnd(
            RootTaskServiceConnector.getIfConnected(),
        )
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onStart() {
        super.onStart()
        registerKeepScreenOnPrefsListener()
        applyDesktopKeepScreenWakeFromPrefs()
        startRearDesktopKeeperSessionIfNeeded()
    }

    override fun onDestroy() {
        cancelRearDesktopUiInitPoll()
        unregisterKeepScreenOnPrefsListener()
        stopDesktopWakeService()
        if (!RearDesktopToAppLaunchHandoff.shouldSkipKeeperRelease()) {
            releaseRearDesktopKeeperSessionIfNeeded()
        }
        // 勿在此处 OfficialSubscreenMiRootProjectionSession.release：
        // 从桌面 am start 其它应用到背屏时本 Activity 会销毁，若此时减引用并恢复官方背屏，
        // 会立刻抢占刚启动的应用，表现为「投屏马上结束」。收口改由 RearSwitchKeeperService.performUnifiedExit 统一 release。
        super.onDestroy()
    }

    /**
     * 背屏桌面：Keeper 侧 [RearSwitchKeeperService.EXTRA_SKIP_INITIAL_LAUNCHER_KILLS] 不在此重复杀进程；
     * 官方背屏中心禁用/恢复由 [OfficialSubscreenMiRootProjectionSession] 与功能页总开关统一处理。
     */
    private fun startRearDesktopKeeperSessionIfNeeded() {
        if (rearDesktopKeeperMonitorKey != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (getCurrentDisplayIdSafe() != REAR_DISPLAY_ID) return
        val key = "${packageName}:${taskId}"
        rearDesktopKeeperMonitorKey = key
        val keeper =
            Intent(this, RearSwitchKeeperService::class.java).apply {
                putExtra("lastMovedTask", key)
                putExtra("keepScreenOnEnabled", RearAssistPrefs.isKeepScreenOnEnabled(this@RearScreenDesktopActivity))
                putExtra(RearSwitchKeeperService.EXTRA_SKIP_INITIAL_LAUNCHER_KILLS, true)
            }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(keeper)
            } else {
                @Suppress("DEPRECATION")
                startService(keeper)
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "start RearSwitchKeeper for desktop: ${e.message}")
            rearDesktopKeeperMonitorKey = null
        }
    }

    private fun releaseRearDesktopKeeperSessionIfNeeded() {
        val key = rearDesktopKeeperMonitorKey ?: return
        rearDesktopKeeperMonitorKey = null
        try {
            val release =
                Intent(this, RearSwitchKeeperService::class.java).apply {
                    action = RearSwitchKeeperService.ACTION_RELEASE_MONITOR_IF_MATCH
                    putExtra(RearSwitchKeeperService.EXTRA_MONITOR_KEY, key)
                }
            startService(release)
        } catch (e: Exception) {
            LogHelper.w(TAG, "release desktop keeper: ${e.message}")
        }
    }

    @Composable
    private fun DesktopBody() {
        val ctx = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        // null：首次/尚未拿到列表，避免慢速 resolve 时把空列表当成「无应用」
        var apps by remember { mutableStateOf<List<RearDesktopAppEntry>?>(null) }
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val next =
                    withContext(Dispatchers.Default) {
                        RearDesktopRepository.resolveApps(ctx)
                    }
                apps = next
            }
        }
        val listMode = RearDesktopPrefs.listMode(ctx)
        when (listMode) {
            RearDesktopListMode.ALL_BY_FREQUENCY ->
                RearDesktopHoneycombScreen(
                    apps = apps,
                    onLaunchApp = { pkg -> launchAppOnCurrentDisplay(pkg) },
                    emptyHint = ctx.getString(R.string.rear_desktop_empty_hint),
                )
            RearDesktopListMode.MANUAL ->
                RearDesktopWheelScreen(
                    apps = apps,
                    onLaunchApp = { pkg -> launchAppOnCurrentDisplay(pkg) },
                    emptyHint = ctx.getString(R.string.rear_desktop_empty_hint),
                )
        }
    }

    private fun launchAppOnCurrentDisplay(packageName: String) {
        RearDesktopPrefs.recordLaunch(applicationContext, packageName)
        // 频次会影响「全部应用」排序；下次进入桌面前清理缓存，避免显示旧顺序。
        RearDesktopRepository.invalidateCache()
        try {
            val app = applicationContext
            // 目标应用将占据背屏：桌面侧先释放 Wake 注册，避免 Keeper 收口后仍残留常亮/投屏通知
            stopDesktopWakeService()
            RearDesktopToAppLaunchHandoff.begin()
            val svc =
                Intent(app, RearAppLaunchService::class.java).apply {
                    action = RearAppLaunchService.ACTION_LAUNCH_APP_ON_REAR
                    putExtra(RearAppLaunchService.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(RearAppLaunchService.EXTRA_LAUNCH_FROM_REAR_DESKTOP, true)
                }
            app.startService(svc)
            if (AppProjectionDisplayPrefs.getConfig(app, packageName) != null) {
                finish()
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "rear launch service failed: $packageName — ${e.message}")
        }
    }

    private fun registerKeepScreenOnPrefsListener() {
        if (keepScreenOnPrefsListener != null) return
        try {
            val prefs = RearAssistPrefs.prefs(this)
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (RearAssistPrefs.KEY_KEEP_SCREEN_ON == key) {
                        window.decorView.post {
                            applyDesktopKeepScreenWakeFromPrefs()
                            updateRearKeepScreenOnWindowFlag()
                        }
                    }
                }
            keepScreenOnPrefsListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        } catch (e: Exception) {
            LogHelper.w(TAG, "register keep-screen prefs: ${e.message}")
            keepScreenOnPrefsListener = null
        }
    }

    private fun unregisterKeepScreenOnPrefsListener() {
        val l = keepScreenOnPrefsListener ?: return
        keepScreenOnPrefsListener = null
        try {
            RearAssistPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(l)
        } catch (_: Exception) {
        }
    }

    /**
     * 与功能页「投屏常亮」一致：开启时注册 [RearScreenWakeService]；关闭时注销（不弹音乐/车控专用仅通知）。
     */
    private fun applyDesktopKeepScreenWakeFromPrefs() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (getCurrentDisplayIdSafe() != REAR_DISPLAY_ID || isFinishing) return
        val keepOn = RearAssistPrefs.isKeepScreenOnEnabled(this)
        try {
            if (keepOn) {
                RearScreenWakeManager.getInstance()
                    .startWakeService(applicationContext, RearScreenDesktopActivity::class.java)
                RearScreenWakeService.requestNotificationRefresh(this)
            } else {
                RearScreenWakeManager.getInstance()
                    .stopWakeService(applicationContext, RearScreenDesktopActivity::class.java)
                if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                    applicationContext.stopService(
                        Intent(applicationContext, RearScreenWakeService::class.java),
                    )
                }
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "desktop wake prefs: ${e.message}")
        }
        updateRearKeepScreenOnWindowFlag()
    }

    private fun stopDesktopWakeService() {
        try {
            RearScreenWakeManager.getInstance()
                .stopWakeService(this, RearScreenDesktopActivity::class.java)
            if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                applicationContext.stopService(
                    Intent(applicationContext, RearScreenWakeService::class.java),
                )
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "stop desktop wake: ${e.message}")
        }
    }

    private fun applyRearWindowFlags() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        updateRearKeepScreenOnWindowFlag()
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
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

    private fun updateRearKeepScreenOnWindowFlag() {
        @Suppress("DEPRECATION")
        if (RearAssistPrefs.isKeepScreenOnEnabled(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hideSystemUi() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) {
            // 首帧或迁屏阶段 decor 可能暂时为 0×0；延后一帧重试，最多重试几次后放弃（避免无限 post）。
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
        tryTrackBottomSwipeExit(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun tryTrackBottomSwipeExit(ev: MotionEvent) {
        if (getCurrentDisplayIdSafe() != REAR_DISPLAY_ID || isFinishing || bottomSwipeExitPending) {
            return
        }
        val decor = window.decorView ?: return
        val xy = FloatArray(2)
        if (!com.wmqc.miroot.BottomSwipeExitHelper.decorLocalXY(decor, ev, xy)) {
            return
        }
        val action = ev.actionMasked
        val h = decor.height.toFloat()
        val y = xy[1]
        val x = xy[0]

        if (action == MotionEvent.ACTION_DOWN && ev.pointerCount == 1) {
            bottomSwipeExitPointerDownInZone = h > 0 && y > h * (1f - BOTTOM_SWIPE_EXIT_ZONE_FRACTION)
            bottomSwipeExitStartY = y
            bottomSwipeExitStartX = x
            return
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            bottomSwipeExitPointerDownInZone = false
            return
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (bottomSwipeExitPointerDownInZone && ev.pointerCount == 1) {
                maybeFireBottomSwipeExit(xy[1], xy[0])
            }
            return
        }
        if (action == MotionEvent.ACTION_UP) {
            if (bottomSwipeExitPointerDownInZone && ev.pointerCount == 1) {
                maybeFireBottomSwipeExit(xy[1], xy[0])
            }
            bottomSwipeExitPointerDownInZone = false
        } else if (action == MotionEvent.ACTION_CANCEL) {
            bottomSwipeExitPointerDownInZone = false
        }
    }

    private fun maybeFireBottomSwipeExit(endY: Float, endX: Float) {
        val horizDist = kotlin.math.abs(endX - bottomSwipeExitStartX)
        val vertDist = kotlin.math.abs(endY - bottomSwipeExitStartY)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var vd = vertDist
        if (vd < touchSlop * 2.5f) {
            vd = 0f
        }
        val minHoriz =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                BOTTOM_SWIPE_EXIT_MIN_HORIZ_DP,
                resources.displayMetrics,
            )
        if (horizDist < minHoriz || horizDist < vd * BOTTOM_SWIPE_EXIT_HORIZONTAL_DOMINANCE) {
            return
        }
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "bottom swipe exit (horizDist=$horizDist)")
        }
        bottomSwipeExitPointerDownInZone = false
        bottomSwipeExitPending = true
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

    private fun getCurrentDisplayIdSafe(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
        return try {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: Exception) {
            Display.DEFAULT_DISPLAY
        }
    }
}
