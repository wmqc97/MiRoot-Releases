package com.wmqc.miroot.charging;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.Log;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.wmqc.miroot.R;
import com.wmqc.miroot.RearDisplayInputHelper;
import com.wmqc.miroot.lyrics.DisplayInfoCache;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.RearDisplayHelper;
import com.wmqc.miroot.lyrics.RootTaskServiceConnector;
import com.wmqc.miroot.rear.RearMirootProjectionLifecycle;
import com.wmqc.miroot.rear.RearSwitchKeeperService;
import com.wmqc.miroot.shell.SwitchToRearQsTileService;

/**
 * 背屏充电动画 Activity。
 * <ul>
 *   <li>主屏 {@code displayId == 0}：仅透明主题占位，不 {@code setContentView}、不加窗口标志，等待 task 迁往副屏</li>
 *   <li>迁屏后通常触发配置变化并重建 Activity，第二次 {@code onCreate} 在非默认显示屏执行涨水与 UI</li>
 *   <li>若同一实例未重建：由 {@link Choreographer} 逐帧探测 displayId（优先于生命周期回调）、以及
 *       {@link #onConfigurationChanged}、{@link #onResume}、{@link DisplayManager.DisplayListener} 调用
 *       {@link #ensureChargingUiOnRearDisplay()}</li>
 * </ul>
 */
public class RearScreenChargingActivity extends ComponentActivity {

    private static final String TAG = "RearScreenChargingActivity";

    // --- MiRoot-3.4 对齐：仅下方 AUTO/PROTECT/resume 时长；涨水仍读 ChargingAnimationPrefs + 线性刻度（非 3.4 液面曲线，勿改回）。除本人强制要求外请勿改动。---

    /** 对齐 3.4：背屏 onCreate 后约 8s 自动关闭（非常亮模式）。 */
    public static final long AUTO_FINISH_MS = 10_000L;
    /** 对齐 3.4：主屏占位未安排 auto-finish 时，onResume 补偿 5s 后 finish。 */
    private static final long RESUME_COMPENSATE_FINISH_MS = 5_000L;
    /** 背屏左摄像头区域固定避让值（px），不使用系统 DisplayCutout 返回值。 */
    private static final int REAR_CAMERA_FALLBACK_LEFT_PX = 260;

    private static volatile RearScreenChargingActivity currentInstance;
    public static final String EXTRA_BATTERY_LEVEL = "batteryLevel";
    /** 功能页预览：按设置涨水并自动结束，不进入充电常亮唤醒循环。 */
    public static final String EXTRA_PREVIEW_MODE = "previewMode";
    public static final String EXTRA_REAR_TASK_ID = "rearTaskId";
    /** 与 {@link com.wmqc.miroot.charging.ChargingService} 解析的副屏 id 一致，用于结束后 move / am start --display */
    public static final String EXTRA_REAR_DISPLAY_ID = "rearDisplayId";

    private int rearTaskId = -1;
    private int rearDisplayIdForRestore = 1;
    private int pendingBatteryLevel;
    private GyroWaterRippleView waterView;
    private ChargingBackgroundVideoPlayer backgroundVideoPlayer;
    private TextView batteryTextView;
    private Typeface chargingDisplayTypeface;
    private ImageView lightningIcon;
    private LinearLayout chargingInfoBar;
    private ValueAnimator lightningPulseAnimator;
    private final Runnable batteryInfoRunnable = this::queryAndUpdateBatteryInfo;
    private float targetFillLevel;
    private boolean chargingUiInflated;
    private boolean previewMode;
    /** 防止 setContentView 重入时再次 inflate。 */
    private boolean rearInflateInProgress;
    private boolean fillPresentationStarted;
    /** 首段涨水动画进行中时，忽略外部液位直设，避免被实时电量更新覆盖。 */
    private boolean initialFillAnimating;
    private ValueAnimator initialFillAnimator;
    /** 是否已安排自动 finish（含常亮模式下「已处理」）。 */
    private boolean autoFinishScheduled;
    /** 仅拔电 / 8s 自动关闭 / 补偿 finish 等 MiRoot 主动结束；系统双击息屏销毁时为 false。 */
    private boolean finishRequestedByMiRoot;
    /** 背屏投屏被新动画打断：不唤醒背屏、不恢复下层投屏，立即销毁。 */
    private boolean interrupted;
    /** 背屏息屏期间暂停自动 finish，避免误关下层投屏。 */
    private boolean rearScreenOffPaused;
    /** 是否已屏蔽官方手势服务（对齐音乐投屏逻辑）。 */
    private boolean isOfficialGestureDisabled;
    private boolean screenStateReceiverRegistered;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable resumeCompensateFinishRunnable = this::finishFromMiRoot;

    private final Runnable autoFinishRunnable = this::finishFromMiRoot;
    private final Runnable rehideSystemUiRunnable = this::hideSystemUi;
    private int hideSystemUiRetry = 0;

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION.equals(action)) {
                LogHelper.d(TAG, "收到拔电广播，立即销毁");
                finishFromMiRoot();
            } else if (ChargingIntents.ACTION_INTERRUPT_CHARGING_ANIMATION.equals(action)) {
                LogHelper.d(TAG, "收到打断广播（新动画来了），立即销毁但不恢复背屏");
                interrupted = true;
                finishFromMiRoot();
            } else if (ChargingIntents.ACTION_UPDATE_CHARGING_BATTERY.equals(action)) {
                int newLevel = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1);
                if (newLevel >= 0) {
                    updateBatteryLevel(newLevel);
                }
            } else if (ChargingIntents.ACTION_NOTIFY_CHARGING_TASK_MOVED_TO_REAR.equals(action)) {
                int movedDisplayId = intent.getIntExtra(EXTRA_REAR_DISPLAY_ID, rearDisplayIdForRestore);
                if (movedDisplayId > 0) {
                    rearDisplayIdForRestore = movedDisplayId;
                }
                LogHelper.d(TAG, "收到迁屏完成广播，立即加载背屏充电动画 UI");
                runOnUiThread(RearScreenChargingActivity.this::ensureChargingUiOnRearDisplay);
            } else if (ChargingIntents.ACTION_RELOAD_CHARGING_SETTINGS.equals(action)) {
                runOnUiThread(() -> {
                    if (chargingUiInflated) {
                        applyChargingLiquidStyleFromPrefs();
                        applyChargingDisplayFont();
                    }
                });
            }
        }
    };

    public static void updateBatteryLevelStatic(int newLevel) {
        RearScreenChargingActivity inst = currentInstance;
        if (inst == null) {
            return;
        }
        inst.runOnUiThread(() -> inst.updateBatteryLevel(newLevel));
    }

    /** MiRoot 主动结束充电动画（拔电、超时、占位补偿等）。 */
    private void finishFromMiRoot() {
        finishRequestedByMiRoot = true;
        finish();
    }

    @Override
    public void finish() {
        if (this == currentInstance) {
            ChargingService.requestStopWakeupLoop(getApplicationContext());
        }
        if (!interrupted) {
            prepareRearProjectionVisibleBeforeFinish();
        }
        super.finish();
        overridePendingTransition(0, 0);
    }

    /**
     * 与 {@link com.wmqc.miroot.car.RearScreenCarControlActivity#prepareRearDisplayBeforeGestureFinish} 对齐：
     * 充电动画结束前唤醒背屏并露出底层音乐投屏，减轻收层后歌词逐字刷新卡顿/空窗。
     */
    private void prepareRearProjectionVisibleBeforeFinish() {
        Log.d("CHARGING_FIX", "STEP 1: 唤醒背屏");
        try {
            ITaskService ts = ChargingService.getTaskService();
            int d = rearDisplayIdForRestore > 0 ? rearDisplayIdForRestore : 1;
            if (ts != null) {
                ts.executeShellCommand("input -d " + d + " keyevent KEYCODE_WAKEUP");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "充电动画结束前唤醒背屏失败: " + e.getMessage());
        }
        Log.d("CHARGING_FIX", "STEP 2: 调用 RearScreenLyricsActivity.forceShowProjectionUiAfterChargingOverlay()");
        try {
            com.wmqc.miroot.lyrics.RearScreenLyricsActivity.forceShowProjectionUiAfterChargingOverlay();
        } catch (Throwable t) {
            LogHelper.w(TAG, "充电动画结束前强制歌词 UI 可见失败: " + t.getMessage());
        }
        Log.d("CHARGING_FIX", "STEP 4: 准备关闭充电窗口，执行 finish()");
    }

    /** 供背屏唤醒等链路检测充电动画实例是否存活。 */
    public static RearScreenChargingActivity getCurrentInstance() {
        return currentInstance;
    }

    /** 当前充电动画 Activity 是否仍存活（非 finishing/destroyed）。 */
    public static boolean isChargingOverlayAlive() {
        RearScreenChargingActivity inst = currentInstance;
        if (inst == null) {
            return false;
        }
        if (inst.isFinishing()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && inst.isDestroyed()) {
            return false;
        }
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int level = ChargingBatteryLevel.getPercent(this);
        if (level <= 0) {
            level = Math.max(0, Math.min(100, intent.getIntExtra(EXTRA_BATTERY_LEVEL, pendingBatteryLevel)));
        }
        pendingBatteryLevel = level;
        targetFillLevel = level / 100f;
        rearTaskId = intent.getIntExtra(EXTRA_REAR_TASK_ID, rearTaskId);
        int displayId = intent.getIntExtra(EXTRA_REAR_DISPLAY_ID, rearDisplayIdForRestore);
        if (displayId > 0) {
            rearDisplayIdForRestore = displayId;
        }
        previewMode = intent.getBooleanExtra(EXTRA_PREVIEW_MODE, previewMode);
        if (chargingUiInflated) {
            updateBatteryLevel(level);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        // 旧系统：透明主题；主屏占位时不绘制充电底色。
        // 背屏在 inflateChargingContentOnRear 里 setWindowBackground + 布局底色保证不透（避免副屏全黑）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setTheme(R.style.Theme_MiRoot_ChargingRear);
        }
        super.onCreate(savedInstanceState);
        // 覆盖层进入也禁用系统默认切换动画，避免背屏闪白/淡入淡出。
        overridePendingTransition(0, 0);

        rearTaskId = intent.getIntExtra(EXTRA_REAR_TASK_ID, -1);
        rearDisplayIdForRestore = intent.getIntExtra(EXTRA_REAR_DISPLAY_ID, 1);
        if (rearDisplayIdForRestore <= 0) {
            rearDisplayIdForRestore = 1;
        }
        int level = ChargingBatteryLevel.getPercent(this);
        if (level <= 0) {
            level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, 0);
            level = Math.max(0, Math.min(100, level));
        }
        pendingBatteryLevel = level;
        targetFillLevel = level / 100f;
        previewMode = intent.getBooleanExtra(EXTRA_PREVIEW_MODE, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getCurrentDisplayIdSafe() == Display.DEFAULT_DISPLAY) {
                // 对齐 3.4：主屏仅占位，等待 ChargingService 迁屏后重建本 Activity。
                LogHelper.d(TAG, "在主屏启动，保持透明占位符，等待移动");
                RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(
                        this, RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER);
                // 清除 Manifest 声明的锁屏显示/亮屏属性，防止占位窗口在主屏唤醒屏幕
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(false);
                    setTurnScreenOn(false);
                }
                registerFinishReceiverOnly();
                registerScreenStateReceiverIfNeeded();
                currentInstance = this;
                return;
            }
        }

        setupChargingUiOnRear(level);
    }

    /** 背屏 displayId≠0 时加载充电视图与动画（3.4 onCreate 背屏分支；亦供迁屏后未重建时兜底）。 */
    private void setupChargingUiOnRear(int level) {
        if (chargingUiInflated || rearInflateInProgress) {
            return;
        }
        rearInflateInProgress = true;
        try {
            registerFinishReceiverOnly();
            registerScreenStateReceiverIfNeeded();
            getWindow().setFormat(PixelFormat.OPAQUE);
            getWindow().setBackgroundDrawableResource(R.color.charging_window_bg);
            applyRearChargingWindowFlags();
            applyRearDensityBeforeInflateIfPossible();
            setContentView(R.layout.activity_rear_screen_charging);
            bindChargingViews(level);
            // applySafeInsetsToBatteryText 已不再需要——safe_area_wrapper 统一处理摄像头避让
            hideSystemUi();
            chargingUiInflated = true;
            currentInstance = this;
            startFillAndBatteryPresentation();
            disableOfficialGesture();
        } finally {
            rearInflateInProgress = false;
        }
    }

    /** 屏蔽官方手势服务（com.xiaomi.subscreencenter），对齐音乐投屏逻辑，防止双击退出投屏。 */
    private void disableOfficialGesture() {
        if (isOfficialGestureDisabled) return;
        ITaskService ts = RootTaskServiceConnector.getIfConnected();
        if (ts == null) {
            LogHelper.w(TAG, "TaskService未连接，暂无法屏蔽官方手势服务");
            return;
        }
        try {
            boolean success = ts.disableSubScreenLauncher();
            if (success) {
                isOfficialGestureDisabled = true;
                LogHelper.d(TAG, "已屏蔽官方手势服务（com.xiaomi.subscreencenter）");
            } else {
                LogHelper.w(TAG, "disableSubScreenLauncher返回false");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "屏蔽官方手势服务失败", e);
        }
    }

    /** 恢复官方手势服务（com.xiaomi.subscreencenter）。 */
    private void enableOfficialGesture() {
        if (!isOfficialGestureDisabled) return;
        ITaskService ts = RootTaskServiceConnector.getIfConnected();
        if (ts == null) {
            isOfficialGestureDisabled = false;
            return;
        }
        try {
            boolean success = ts.enableSubScreenLauncher();
            isOfficialGestureDisabled = false;
            LogHelper.d(TAG, "已恢复官方手势服务（com.xiaomi.subscreencenter） ok=" + success);
        } catch (Exception e) {
            LogHelper.e(TAG, "恢复官方手势服务失败", e);
            isOfficialGestureDisabled = false;
        }
    }

    private void registerFinishReceiverOnly() {
        if (finishReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION);
        filter.addAction(ChargingIntents.ACTION_INTERRUPT_CHARGING_ANIMATION);
        filter.addAction(ChargingIntents.ACTION_UPDATE_CHARGING_BATTERY);
        filter.addAction(ChargingIntents.ACTION_NOTIFY_CHARGING_TASK_MOVED_TO_REAR);
        filter.addAction(ChargingIntents.ACTION_RELOAD_CHARGING_SETTINGS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishReceiver, filter);
        }
        finishReceiverRegistered = true;
    }

    private boolean finishReceiverRegistered;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && getCurrentDisplayIdSafe() == Display.DEFAULT_DISPLAY) {
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                onRearChargingScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                onRearChargingScreenOn();
            }
        }
    };

    private void registerScreenStateReceiverIfNeeded() {
        if (screenStateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, filter);
        }
        screenStateReceiverRegistered = true;
    }

    private void onRearChargingScreenOff() {
        if (rearScreenOffPaused || isFinishing()) {
            return;
        }
        rearScreenOffPaused = true;
        LogHelper.d(TAG, "背屏息屏：暂停充电动画自动关闭，保留下层投屏");
        cancelScheduledFinishes();
        prepareRearProjectionVisibleBeforeFinish();
        try {
            ITaskService ts = ChargingService.getTaskService();
            int d = rearDisplayIdForRestore > 0 ? rearDisplayIdForRestore : 1;
            if (ts != null) {
                ts.executeShellCommand("input -d " + d + " keyevent KEYCODE_WAKEUP");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "息屏后唤醒背屏失败: " + e.getMessage());
        }
    }

    private void onRearChargingScreenOn() {
        if (!rearScreenOffPaused) {
            return;
        }
        rearScreenOffPaused = false;
        LogHelper.d(TAG, "背屏亮屏：恢复充电动画层");
        if (!chargingUiInflated || isFinishing()) {
            return;
        }
        hideSystemUi();
        ensureChargingUiOnRearDisplay();
    }

    private void cancelScheduledFinishes() {
        mainHandler.removeCallbacks(resumeCompensateFinishRunnable);
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.removeCallbacks(autoFinishRunnable);
        }
        View container = findViewById(R.id.charging_container);
        if (container != null) {
            container.removeCallbacks(autoFinishRunnable);
        }
    }

    private void ensureChargingUiOnRearDisplay() {
        if (chargingUiInflated || rearInflateInProgress) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && getCurrentDisplayIdSafe() == Display.DEFAULT_DISPLAY) {
            return;
        }
        setupChargingUiOnRear(pendingBatteryLevel);
    }

    private static int getCurrentDisplayIdSafe(ComponentActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Display.DEFAULT_DISPLAY;
        }
        try {
            Display d = activity.getDisplay();
            return d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY;
        } catch (Exception e) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    private int getCurrentDisplayIdSafe() {
        return getCurrentDisplayIdSafe(this);
    }

    /** 对齐 3.4：只要不在 DEFAULT_DISPLAY 即视为可渲染背屏。 */
    private boolean isOnExpectedRearDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        return getCurrentDisplayIdSafe() != Display.DEFAULT_DISPLAY;
    }

    private void applyRearChargingWindowFlags() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        // 对齐 3.4：KEEP_SCREEN_ON + 锁屏显示 + 点亮屏幕
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(lp);
        }
    }

    /**
     * 在 inflate 前按 {@link ITaskService#getCurrentRearDpi()} 统一背屏密度。
     */
    private void applyRearDensityBeforeInflateIfPossible() {
        try {
            ITaskService ts = ChargingService.getTaskService();
            int rearDpi = 0;
            if (ts != null) {
                try {
                    rearDpi = ts.getCurrentRearDpi();
                } catch (RemoteException e) {
                    LogHelper.w(TAG, "getCurrentRearDpi: " + e.getMessage());
                }
            }
            if (rearDpi <= 0) {
                return;
            }
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int currentDpi = metrics.densityDpi;
            if (currentDpi == rearDpi) {
                return;
            }
            LogHelper.d(TAG, "inflate 前 DPI: 当前=" + currentDpi + " → 背屏=" + rearDpi);
            metrics.densityDpi = rearDpi;
            metrics.density = rearDpi / 160f;
            metrics.scaledDensity = metrics.density;
            Configuration config = new Configuration(getResources().getConfiguration());
            config.densityDpi = rearDpi;
            getResources().updateConfiguration(config, metrics);
        } catch (Exception e) {
            LogHelper.w(TAG, "applyRearDensityBeforeInflate: " + e.getMessage());
        }
    }

    /**
     * 用电量 TextView 的 WindowInsets 避让刘海（背景仍全屏）。
     */
    private void applySafeInsetsToBatteryText() {
        if (batteryTextView == null) {
            return;
        }
        Insets initialSafe = computeInitialBatterySafeInsets();
        applyBatteryTextSafePadding(initialSafe.left, initialSafe.top, initialSafe.right, initialSafe.bottom);
        ViewCompat.setOnApplyWindowInsetsListener(batteryTextView, (v, insets) -> {
            Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int left = Math.max(cut.left, sys.left);
            int right = Math.max(cut.right, sys.right);
            // 关键：首帧前已应用一次安全区，后续 insets 只允许“增量修正”，防止出现先贴左再跳右。
            int resolvedLeft = Math.max(v.getPaddingLeft(), left);
            int resolvedRight = Math.max(v.getPaddingRight(), right);
            // 仅对左右做 cutout/system bars 安全区，保持垂直方向严格居中。
            applyBatteryTextSafePadding(resolvedLeft, 0, resolvedRight, 0);
            return insets;
        });
        ViewCompat.requestApplyInsets(batteryTextView);
    }

    private void applyBatteryTextSafePadding(int left, int top, int right, int bottom) {
        if (batteryTextView == null) {
            return;
        }
        if (batteryTextView.getPaddingLeft() == left
            && batteryTextView.getPaddingTop() == top
            && batteryTextView.getPaddingRight() == right
            && batteryTextView.getPaddingBottom() == bottom) {
            return;
        }
        // 通过 padding 把可绘制区域限制到安全区，gravity=center 会自然落在“右侧可视区居中”。
        batteryTextView.setPadding(left, top, right, bottom);
    }

    private Insets computeInitialBatterySafeInsets() {
        int left = 0;
        int right = 0;
        if (isOnExpectedRearDisplay()) {
            // 不使用系统 DisplayCutout 返回值，统一使用固定避让值。
            left = REAR_CAMERA_FALLBACK_LEFT_PX;
        }
        return Insets.of(left, 0, right, 0);
    }

    /** Canvas 底部信息与 [R.id.safe_area_wrapper] 使用同一套左右安全区。 */
    private void applyFloatingInfoSafeInsetsToWaterView() {
        if (waterView == null) {
            return;
        }
        Insets safe = computeInitialBatterySafeInsets();
        int rightPx = Math.round(getResources().getDisplayMetrics().density * 10);
        waterView.setFloatingInfoSafeHorizontalInsetsPx(safe.left, rightPx);
    }

    private void bindChargingViews(int level) {
        waterView = findViewById(R.id.gyro_water_ripple);
        TextureView backgroundVideoView = findViewById(R.id.charging_background_video);
        if (backgroundVideoView != null) {
            backgroundVideoPlayer = new ChargingBackgroundVideoPlayer(this, backgroundVideoView);
        }
        batteryTextView = findViewById(R.id.battery_text);
        pendingBatteryLevel = level;
        targetFillLevel = level / 100f;
        applyBatteryPercentDisplay(isFloatingBatteryMode() ? 0 : level);
        waterView.setFillLevel(0f);
        applyChargingLiquidStyleFromPrefs();

        lightningIcon = findViewById(R.id.lightning_icon);
        updateCenterBatteryVisibility();

        // 安全区域包装层统一左避让摄像头，⚡73% 和信息卡在其内居中
        View safeWrapper = findViewById(R.id.safe_area_wrapper);
        if (safeWrapper != null) {
            Insets safe = computeInitialBatterySafeInsets();
            int rightPx = Math.round(getResources().getDisplayMetrics().density * 10);
            safeWrapper.setPadding(safe.left, 0, rightPx, 0);
        }

        chargingInfoBar = findViewById(R.id.charging_info_bar);
        rebuildInfoBar();
        updateChargingInfoVisibility();
        applyFloatingInfoSafeInsetsToWaterView();
        applyChargingDisplayFont();
        // 延迟 1s 等 UI 稳定后开始读取电池信息
        mainHandler.postDelayed(this::queryAndUpdateBatteryInfo, 1000L);
    }

    private void applyChargingSceneBackground() {
        if (waterView == null) {
            return;
        }
        boolean videoActive = backgroundVideoPlayer != null
            && backgroundVideoPlayer.applyFromStorage();
        waterView.setSceneVideoBackgroundActive(videoActive);
        if (videoActive) {
            waterView.setBackgroundBitmap(null);
        } else {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxEdge = Math.max(dm.widthPixels, dm.heightPixels);
            waterView.setBackgroundBitmap(ChargingBackgroundLoader.load(this, maxEdge));
        }
    }

    private void applyChargingLiquidStyleFromPrefs() {
        if (waterView == null) {
            return;
        }
        applyChargingSceneBackground();

        String floating = ChargingAnimationPrefs.getFloatingDisplay(this);
        GyroWaterRippleView.FloatingDisplay displayMode;
        if (ChargingAnimationPrefs.FLOATING_IMAGE.equals(floating)) {
            displayMode = GyroWaterRippleView.FloatingDisplay.IMAGE;
            waterView.setMascotBitmap(ChargingMascotLoader.load(this));
        } else if (ChargingAnimationPrefs.FLOATING_BATTERY.equals(floating)) {
            displayMode = GyroWaterRippleView.FloatingDisplay.BATTERY;
            waterView.setMascotBitmap(null);
        } else {
            displayMode = GyroWaterRippleView.FloatingDisplay.NONE;
            waterView.setMascotBitmap(null);
        }
        waterView.setFloatingDisplay(displayMode);
        waterView.setWaterColorCustom(ChargingAnimationPrefs.getWaterColor(this));
        waterView.setWaterOpacityPercent(ChargingAnimationPrefs.getWaterOpacityPercent(this));
        updateCenterBatteryVisibility();
        updateChargingInfoVisibility();
        applyFloatingInfoSafeInsetsToWaterView();
    }

    private boolean isFloatingBatteryMode() {
        return ChargingAnimationPrefs.FLOATING_BATTERY.equals(
                ChargingAnimationPrefs.getFloatingDisplay(this));
    }

    private boolean isCenterBatteryVisible() {
        return !ChargingAnimationPrefs.FLOATING_BATTERY.equals(
                ChargingAnimationPrefs.getFloatingDisplay(this));
    }

    /** 水中电量漂浮时隐藏居中 ⚡ + 电量。 */
    private void updateCenterBatteryVisibility() {
        View center = findViewById(R.id.center_content);
        boolean show = isCenterBatteryVisible();
        if (center != null) {
            center.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (!show) {
            if (lightningPulseAnimator != null) {
                lightningPulseAnimator.cancel();
                lightningPulseAnimator = null;
            }
        } else if (lightningIcon != null && lightningPulseAnimator == null) {
            startLightningPulse();
        }
    }

    /** 仅「水中漂浮 · 电量」时 Canvas 悬浮；否则用 XML 底部信息栏。 */
    private void updateChargingInfoVisibility() {
        if (isFloatingBatteryMode()) {
            if (chargingInfoBar != null) {
                chargingInfoBar.setVisibility(View.GONE);
            }
            refreshFloatingInfoLabels();
        } else {
            if (waterView != null) {
                waterView.setFloatingInfoLabels(new ArrayList<>());
            }
            rebuildInfoBar();
        }
    }

    private void refreshFloatingInfoLabels() {
        if (waterView == null || !isFloatingBatteryMode()) {
            return;
        }
        List<String> items = ChargingAnimationPrefs.getInfoItems(this);
        if (items.isEmpty()) {
            waterView.setFloatingInfoLabels(new ArrayList<>());
        } else {
            waterView.setFloatingInfoLabels(collectFloatingInfoDisplayLines());
        }
    }

    private void startFillAndBatteryPresentation() {
        if (fillPresentationStarted || waterView == null || batteryTextView == null) {
            return;
        }
        fillPresentationStarted = true;

        waterView.setFillLevel(0f);
        initialFillAnimating = true;
        if (initialFillAnimator != null) {
            initialFillAnimator.cancel();
            initialFillAnimator = null;
        }
        initialFillAnimator = ValueAnimator.ofFloat(0f, targetFillLevel);
        // 涨水：保留功能页「涨水速度」与线性刻度（与 3.4 老版固定 2s 减速液面不同，勿改回老版曲线）。
        int speedPercent = ChargingAnimationPrefs.getFillRiseSpeedPercent(this);
        int fullFillMs = ChargingAnimationPrefs.fillDurationMsForFullFill(speedPercent);
        long durationMs = Math.max(1L, Math.round(fullFillMs * Math.max(0f, targetFillLevel)));
        initialFillAnimator.setDuration(durationMs);
        initialFillAnimator.setInterpolator(new LinearInterpolator());
        initialFillAnimator.addUpdateListener(a -> {
            float fill = (Float) a.getAnimatedValue();
            waterView.setFillLevel(fill);
            if (isFloatingBatteryMode()) {
                applyBatteryPercentDisplayFromFillLevel(fill);
            }
        });
        initialFillAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                initialFillAnimating = false;
                if (waterView != null) {
                    // 动画期间可能收到电量更新，结束后对齐到最新目标值。
                    waterView.setFillLevel(targetFillLevel);
                }
                applyBatteryPercentDisplay(pendingBatteryLevel);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                initialFillAnimating = false;
                applyBatteryPercentDisplay(pendingBatteryLevel);
            }
        });
        initialFillAnimator.start();

        if (isCenterBatteryVisible()) {
            View center = findViewById(R.id.center_content);
            if (center != null) {
                center.setAlpha(0f);
                center.setScaleX(0.8f);
                center.setScaleY(0.8f);
                center.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(800)
                    .setStartDelay(600)
                    .setInterpolator(new DecelerateInterpolator(2.0f))
                    .start();
            }
        }

        View container = findViewById(R.id.charging_container);
        if (container != null) {
            container.removeCallbacks(autoFinishRunnable);
            long finishDelayMs = resolveAutoFinishDelayMs();
            if (finishDelayMs > 0L) {
                container.postDelayed(autoFinishRunnable, finishDelayMs);
                LogHelper.d(TAG, (previewMode ? "预览" : "动画")
                    + "已启动，" + (finishDelayMs / 1000L) + " 秒后自动关闭");
            } else {
                LogHelper.d(TAG, "动画已启动，充电常亮模式，不自动关闭");
            }
            autoFinishScheduled = finishDelayMs > 0L;
        }
    }

    /** 充电常亮开启时不自动关闭；预览与真实插电一致。 */
    private long resolveAutoFinishDelayMs() {
        if (rearScreenOffPaused) {
            return 0L;
        }
        if (isChargingAlwaysOn()) {
            return 0L;
        }
        return AUTO_FINISH_MS;
    }

    private boolean isChargingAlwaysOn() {
        return ChargingAnimationPrefs.isAlwaysOn(this);
    }

    private void updateBatteryLevel(int newLevel) {
        newLevel = Math.max(0, Math.min(100, newLevel));
        pendingBatteryLevel = newLevel;
        targetFillLevel = newLevel / 100f;
        try {
            if (initialFillAnimating) {
                return;
            }
            if (waterView != null) {
                waterView.setFillLevel(targetFillLevel);
            }
            applyBatteryPercentDisplay(newLevel);
        } catch (Exception e) {
            LogHelper.w(TAG, "updateBatteryLevel: " + e.getMessage());
        }
    }

    /** 涨水进度 0–1 映射为展示用整数电量（仅水中漂浮大数字与液位同步）。 */
    private void applyBatteryPercentDisplayFromFillLevel(float fillLevel) {
        int displayPct = Math.max(0, Math.min(100, Math.round(fillLevel * 100f)));
        applyBatteryPercentDisplay(displayPct);
    }

    /** 更新居中 ⚡ 旁数字与水中漂浮大数字（含低电量橙/红配色）。居中模式显示真实电量，不随液位动画。 */
    private void applyBatteryPercentDisplay(int displayPct) {
        displayPct = Math.max(0, Math.min(100, displayPct));
        if (waterView != null && isFloatingBatteryMode()) {
            waterView.setBatteryPercentForTint(displayPct);
        }
        if (batteryTextView != null && isCenterBatteryVisible()) {
            int centerPct = Math.max(0, Math.min(100, pendingBatteryLevel));
            batteryTextView.setText(centerPct + "%");
            batteryTextView.setTextColor(ChargingBatteryLevel.largePercentTextColorArgb(centerPct));
        }
    }

    /** 闪电图标呼吸脉冲动画：透明度与缩放同步起伏。 */
    private void startLightningPulse() {
        if (lightningIcon == null) {
            return;
        }
        if (lightningPulseAnimator != null) {
            lightningPulseAnimator.cancel();
        }
        lightningPulseAnimator = ValueAnimator.ofFloat(0.6f, 1.0f);
        lightningPulseAnimator.setDuration(1200L);
        lightningPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        lightningPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        lightningPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        lightningPulseAnimator.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            lightningIcon.setAlpha(v);
            lightningIcon.setScaleX(v * 0.15f + 0.85f);
            lightningIcon.setScaleY(v * 0.15f + 0.85f);
        });
        lightningPulseAnimator.start();
    }

    /** 充电动画全部文案（中央电量、底部信息、漂浮信息）统一使用功能页所选字体。 */
    private void applyChargingDisplayFont() {
        chargingDisplayTypeface = ChargingAnimationPrefs.resolveTypeface(this);
        if (batteryTextView != null) {
            batteryTextView.setTypeface(chargingDisplayTypeface, Typeface.NORMAL);
        }
        if (waterView != null) {
            waterView.setChargingDisplayTypeface(chargingDisplayTypeface);
        }
        if (chargingInfoBar != null) {
            for (int i = 0; i < chargingInfoBar.getChildCount(); i++) {
                View child = chargingInfoBar.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setTypeface(chargingDisplayTypeface, Typeface.NORMAL);
                }
            }
        }
    }

    /** 根据用户配置重建底部信息：电量漂浮为 Canvas 逐项悬浮，否则 XML 信息栏。 */
    private void rebuildInfoBar() {
        if (chargingInfoBar == null) return;
        if (isFloatingBatteryMode()) {
            chargingInfoBar.setVisibility(View.GONE);
            refreshFloatingInfoLabels();
            return;
        }
        chargingInfoBar.removeAllViews();
        List<String> items = ChargingAnimationPrefs.getInfoItems(this);
        if (items.isEmpty()) {
            chargingInfoBar.setVisibility(View.GONE);
            return;
        }
        int count = items.size();
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                View divider = new View(this);
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        Math.round(1 * density), Math.round(20 * density)));
                divider.setBackgroundColor(0x33FFFFFF);
                chargingInfoBar.addView(divider);
            }
            TextView tv = new TextView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextColor(0xDDFFFFFF);
            tv.setTextSize(12f);
            tv.setSingleLine(true);
            tv.setTag(items.get(i));
            tv.setText("--");
            chargingInfoBar.addView(tv);
        }
        applyChargingDisplayFont();
        chargingInfoBar.setVisibility(View.VISIBLE);
    }

    /** 电量漂浮：各参数独立一行悬浮。 */
    private List<String> collectFloatingInfoDisplayLines() {
        List<String> items = ChargingAnimationPrefs.getInfoItems(this);
        List<String> lines = new ArrayList<>(items.size());
        for (String id : items) {
            lines.add("--");
        }
        return lines;
    }

    private void pushFloatingInfoLabels(
            int voltage, int temp, int plugged, int batteryPct,
            int currentMicroA, int chargeCounterMicroAh,
            float powerW, boolean validPower) {
        if (waterView == null || !isFloatingBatteryMode()) {
            return;
        }
        List<String> items = ChargingAnimationPrefs.getInfoItems(this);
        if (items.isEmpty()) {
            waterView.setFloatingInfoLabels(new ArrayList<>());
            return;
        }
        List<String> lines = new ArrayList<>(items.size());
        for (String id : items) {
            lines.add(getItemDisplayValue(
                    id, voltage, temp, plugged, batteryPct, 100,
                    currentMicroA, chargeCounterMicroAh, powerW, validPower));
        }
        waterView.setFloatingInfoLabels(lines);
    }

    /** 返回指定信息项的当前显示文本。 */
    private String getItemDisplayValue(String itemId, int voltage, int temp,
                                       int plugged, int level, int scale,
                                       int currentMicroA, int chargeCounterMicroAh,
                                       float powerW, boolean validPower) {
        float levelPct = level * 100f / Math.max(scale, 1);
        switch (itemId) {
            case "power":
                return formatSignedPower(powerW, validPower);
            case "charging_type": {
                if (plugged == 0 || !isBatteryPropertyAvailable(currentMicroA)
                        || currentMicroA <= 0) {
                    return "--";
                }
                if (plugged == BatteryManager.BATTERY_PLUGGED_AC) {
                    return validPower && powerW >= 18f ? "快充" : "充电";
                } else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
                    return "USB";
                } else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                    return "无线";
                }
                return "--";
            }
            case "time":
                return estimatetime(levelPct, plugged, currentMicroA, chargeCounterMicroAh);
            case "temperature":
                return temp > 0
                        ? String.format(Locale.US, "%.1f°C", temp / 10f) : "--";
            case "voltage":
                return voltage > 0
                        ? String.format(Locale.US, "%.2fV", voltage / 1000f) : "--";
            case "current":
                return formatSignedCurrent(currentMicroA);
            case "capacity": {
                if (isBatteryPropertyAvailable(chargeCounterMicroAh)
                        && chargeCounterMicroAh > 0) {
                    return String.format(Locale.US, "%.0fmAh",
                            chargeCounterMicroAh / 1000f);
                }
                return "--";
            }
            default:
                return "--";
        }
    }

    private static boolean isBatteryPropertyAvailable(int propertyValue) {
        return propertyValue != Integer.MIN_VALUE;
    }

    /**
     * 统一电流符号：充电流入为正、放电流出为负。
     * 部分机型（含小米/HyperOS）{@link BatteryManager#BATTERY_PROPERTY_CURRENT_NOW}
     * 与 AOSP 约定相反（插电为负、未插为正）。
     */
    private static int normalizeCurrentMicroA(int currentMicroA, int plugged) {
        if (!isBatteryPropertyAvailable(currentMicroA) || currentMicroA == 0) {
            return currentMicroA;
        }
        boolean pluggedIn = plugged != 0;
        if (pluggedIn && currentMicroA < 0) {
            return -currentMicroA;
        }
        if (!pluggedIn && currentMicroA > 0) {
            return -currentMicroA;
        }
        return currentMicroA;
    }

    /** 电流 µA：充电为正、放电为负，原样显示符号。 */
    private static String formatSignedCurrent(int currentMicroA) {
        if (!isBatteryPropertyAvailable(currentMicroA)) {
            return "--";
        }
        if (currentMicroA == 0) {
            return "0mA";
        }
        float ma = currentMicroA / 1000f;
        float absMa = Math.abs(ma);
        String magnitude = absMa >= 1000f
                ? String.format(Locale.US, "%.1fA", absMa / 1000f)
                : String.format(Locale.US, "%.0fmA", absMa);
        return ma < 0f ? "-" + magnitude : magnitude;
    }

    private static String formatSignedPower(float powerW, boolean valid) {
        if (!valid) {
            return "--W";
        }
        return String.format(Locale.US, "%.1fW", powerW);
    }

    /** 查询 BatteryManager 获取功率/充电类型/预估时间/温度并更新底部信息卡。 */
    private void queryAndUpdateBatteryInfo() {
        try {
            // 电压/温度/充电类型仅 ACTION_BATTERY_CHANGED sticky intent 中有
            Intent batteryIntent = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent == null) {
                scheduleNextBatteryInfoQuery(3000L);
                return;
            }
            int voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            // 电流和电荷计数通过 getIntProperty 获取
            int current = 0;
            int chargeCounterMicroAh = 0;
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                chargeCounterMicroAh = bm.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            }
            current = normalizeCurrentMicroA(current, plugged);

            // 功率 W = V × I（充电为正、放电为负，单位 µA）
            boolean currentValid = isBatteryPropertyAvailable(current);
            float powerW = 0f;
            if (currentValid && voltage > 0) {
                powerW = (voltage / 1000f) * (current / 1_000_000f);
            }
            boolean validPower = currentValid && voltage > 0 && Math.abs(powerW) >= 0.1f;

            int batteryPct = ChargingBatteryLevel.getPercent(this);
            updateBatteryLevel(batteryPct);

            if (isFloatingBatteryMode()) {
                pushFloatingInfoLabels(
                        voltage, temp, plugged, batteryPct,
                        current, chargeCounterMicroAh, powerW, validPower);
            } else if (chargingInfoBar != null) {
                int childCount = chargingInfoBar.getChildCount();
                boolean anyUpdated = false;
                for (int i = 0; i < childCount; i++) {
                    View child = chargingInfoBar.getChildAt(i);
                    if (child instanceof TextView) {
                        String tag = (String) child.getTag();
                        if (tag != null) {
                            ((TextView) child).setText(getItemDisplayValue(
                                tag, voltage, temp, plugged, batteryPct, 100,
                                current, chargeCounterMicroAh, powerW, validPower));
                            anyUpdated = true;
                        }
                    }
                }
                if (anyUpdated && chargingInfoBar.getVisibility() != View.VISIBLE) {
                    chargingInfoBar.setAlpha(0f);
                    chargingInfoBar.setVisibility(View.VISIBLE);
                    chargingInfoBar.animate().alpha(1f).setDuration(500L).start();
                }
            }
            scheduleNextBatteryInfoQuery(3000L);
        } catch (Exception e) {
            LogHelper.w(TAG, "queryAndUpdateBatteryInfo: " + e.getMessage());
            scheduleNextBatteryInfoQuery(5000L);
        }
    }

    /** 估算充满剩余时间（仅插电且充电电流为正时有效）。 */
    private String estimatetime(float levelPct, int plugged, int currentMicroA,
                                int chargeCounterMicroAh) {
        if (levelPct >= 99f) {
            return "已充满";
        }
        if (plugged == 0 || !isBatteryPropertyAvailable(currentMicroA)
                || currentMicroA < 50_000 || chargeCounterMicroAh <= 0) {
            return "--";
        }
        float totalCapacityMicroAh = chargeCounterMicroAh / (levelPct / 100f);
        float remainingMicroAh = totalCapacityMicroAh - chargeCounterMicroAh;
        float hoursRemaining = remainingMicroAh / currentMicroA;
        int minutes = Math.round(hoursRemaining * 60);
        if (minutes <= 0) {
            return "即将充满";
        }
        if (minutes >= 180) {
            return "> 3 小时";
        }
        return "约 " + minutes + " 分钟";
    }

    private void scheduleNextBatteryInfoQuery(long delayMs) {
        mainHandler.removeCallbacks(batteryInfoRunnable);
        mainHandler.postDelayed(batteryInfoRunnable, delayMs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int mainMode = RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                    getCurrentDisplayIdSafe(), false, chargingUiInflated);
            if (RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)) {
                if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
                    finishFromMiRoot();
                } else if (mainMode
                    == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER) {
                    // 占位态回到主屏：迁屏未生效或 task 被系统落回，立即销毁避免泄露到主屏
                    LogHelper.w(TAG, "onConfigurationChanged: 占位态仍在主屏，强制结束");
                    finishAndRemoveTask();
                }
                return;
            }
        }
        ensureChargingUiOnRearDisplay();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
            ensureChargingUiOnRearDisplay();
        }
    }

    @Override
    protected void onPause() {
        if (backgroundVideoPlayer != null) {
            backgroundVideoPlayer.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int mainMode = RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                    getCurrentDisplayIdSafe(), false, chargingUiInflated);
            if (RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)) {
                if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
                    LogHelper.w(TAG, "主屏禁止展示充电动画 UI，结束");
                    finishFromMiRoot();
                }
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        ensureChargingUiOnRearDisplay();
        if (backgroundVideoPlayer != null && chargingUiInflated) {
            backgroundVideoPlayer.resume();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int did = getCurrentDisplayIdSafe();
            if (did != Display.DEFAULT_DISPLAY
                && !chargingUiInflated
                && !autoFinishScheduled
                && !isChargingAlwaysOn()
                && !rearScreenOffPaused) {
                LogHelper.d(TAG, "未安排自动销毁，补偿 " + RESUME_COMPENSATE_FINISH_MS + "ms 后 finish");
                mainHandler.removeCallbacks(resumeCompensateFinishRunnable);
                mainHandler.postDelayed(resumeCompensateFinishRunnable, RESUME_COMPENSATE_FINISH_MS);
                autoFinishScheduled = true;
            }
        }
    }

    /**
     * 对齐 3.4：全屏显示，隐藏状态栏与导航栏。
     */
    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            // 迁屏/首帧阶段 decor 可能为 0×0；此时强行 setDecorFitsSystemWindows/隐藏系统栏容易触发 InsetsSource 刷屏。
            if (hideSystemUiRetry++ < 6) {
                decor.postDelayed(this::hideSystemUi, 16L);
            }
            return;
        }
        hideSystemUiRetry = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onDestroy() {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.removeCallbacks(autoFinishRunnable);
        }
        View container = findViewById(R.id.charging_container);
        if (container != null) {
            container.removeCallbacks(autoFinishRunnable);
        }
        if (initialFillAnimator != null) {
            initialFillAnimator.cancel();
            initialFillAnimator = null;
        }
        if (lightningPulseAnimator != null) {
            lightningPulseAnimator.cancel();
            lightningPulseAnimator = null;
        }
        if (backgroundVideoPlayer != null) {
            backgroundVideoPlayer.release();
            backgroundVideoPlayer = null;
        }
        mainHandler.removeCallbacks(batteryInfoRunnable);
        initialFillAnimating = false;
        mainHandler.removeCallbacks(rehideSystemUiRunnable);
        mainHandler.removeCallbacks(resumeCompensateFinishRunnable);
        if (finishReceiverRegistered) {
            try {
                unregisterReceiver(finishReceiver);
            } catch (Exception e) {
                LogHelper.w(TAG, "unregisterReceiver: " + e.getMessage());
            }
            finishReceiverRegistered = false;
        }
        if (screenStateReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception e) {
                LogHelper.w(TAG, "unregister screenStateReceiver: " + e.getMessage());
            }
            screenStateReceiverRegistered = false;
        }
        enableOfficialGesture();
        final int displayIdBeforeDestroy =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? getCurrentDisplayIdSafe() : Display.DEFAULT_DISPLAY;
        super.onDestroy();

        final boolean ownsSession = (this == currentInstance);
        final boolean wasInterrupted = interrupted;
        interrupted = false;
        boolean shouldRestore = false;
        if (ownsSession) {
            currentInstance = null;
            shouldRestore = RearChargingAnimationCoordinator.endAnimation(
                RearChargingAnimationCoordinator.AnimationType.CHARGING);
            ChargingService.requestStopWakeupLoop(getApplicationContext());
        } else if (!isChargingOverlayAlive()
            && RearChargingAnimationCoordinator.getCurrentAnimation()
                == RearChargingAnimationCoordinator.AnimationType.CHARGING) {
            LogHelper.w(TAG, "非当前实例 onDestroy，协调器仍为 CHARGING，补清理常亮");
            RearChargingAnimationCoordinator.endAnimation(
                RearChargingAnimationCoordinator.AnimationType.CHARGING);
            ChargingService.requestStopWakeupLoop(getApplicationContext());
            return;
        } else {
            LogHelper.w(TAG, "旧实例 onDestroy，跳过协调器与恢复");
            return;
        }

        if (wasInterrupted) {
            LogHelper.d(TAG, "充电动画被打断，跳过背屏恢复与唤醒");
            return;
        }

        if (!shouldRestore) {
            LogHelper.d(TAG, "充电动画被打断，跳过恢复背屏");
            return;
        }

        int rearDisplay = rearDisplayIdForRestore > 0 ? rearDisplayIdForRestore : 1;
        if (displayIdBeforeDestroy != rearDisplay) {
            return;
        }
        if (!finishRequestedByMiRoot) {
            restoreAfterOverlayDismissWithoutOfficialLauncher();
            return;
        }
        final int finalTaskId = resolveUnderlyingProjectionTaskId();
        new Thread(() -> {
            try {
                Thread.sleep(50L);
                if (finalTaskId > 0) {
                    restoreRearProjectedTask(finalTaskId);
                } else {
                    restoreRearOfficialLauncher();
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "恢复背屏失败: " + e.getMessage());
            }
        }, "miroot-charging-restore").start();
    }

    /**
     * 双击息屏等系统销毁覆盖层：露出下层投屏，不拉起官方副屏 Launcher。
     */
    private void restoreAfterOverlayDismissWithoutOfficialLauncher() {
        prepareRearProjectionVisibleBeforeFinish();
        final int taskId = resolveUnderlyingProjectionTaskId();
        new Thread(() -> {
            try {
                Thread.sleep(50L);
                ITaskService ts = ChargingService.getTaskService();
                ChargingOfficialSubscreen.reviveOfficialPackageAfterChargingWithoutLauncher(
                    getApplicationContext(), ts);
                if (taskId > 0) {
                    restoreRearProjectedTask(taskId);
                } else {
                    RearSwitchKeeperService.resumeMonitoring();
                    int d = rearDisplayIdForRestore > 0 ? rearDisplayIdForRestore : 1;
                    if (ts != null) {
                        ts.executeShellCommand("input -d " + d + " keyevent KEYCODE_WAKEUP");
                    }
                    LogHelper.d(TAG, "覆盖层系统销毁：未解析到投屏 task，已跳过官方 Launcher");
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "覆盖层销毁后恢复投屏失败: " + e.getMessage());
            }
        }, "miroot-charging-overlay-dismiss").start();
    }

    /** 解析充电覆盖层下方的 MiRoot 投屏 task（歌词 / 车控 / 桌面）。 */
    private int resolveUnderlyingProjectionTaskId() {
        if (rearTaskId > 0) {
            return rearTaskId;
        }
        String lastTask = SwitchToRearQsTileService.getLastMovedTask();
        if (lastTask != null && lastTask.contains(":") && !lastTask.contains("RearScreenChargingActivity")) {
            try {
                String[] parts = lastTask.split(":");
                int tid = Integer.parseInt(parts[parts.length - 1].trim());
                ITaskService ts = ChargingService.getTaskService();
                if (ts != null && ts.isTaskOnDisplay(tid, rearDisplayIdForRestore)) {
                    return tid;
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "parse lastMovedTask: " + e.getMessage());
            }
        }
        try {
            ITaskService ts = ChargingService.getTaskService();
            if (ts == null) {
                return -1;
            }
            String stack = ts.executeShellCommandWithResult("am stack list");
            if (stack == null || stack.isEmpty()) {
                return -1;
            }
            int rearDisplay = rearDisplayIdForRestore > 0 ? rearDisplayIdForRestore : 1;
            boolean inTargetDisplay = false;
            for (String line : stack.split("\n")) {
                if (line.startsWith("RootTask")) {
                    int did = parseRootTaskDisplayIdFromLine(line);
                    inTargetDisplay = (did == rearDisplay);
                    continue;
                }
                if (!inTargetDisplay || !line.contains("taskId=")) {
                    continue;
                }
                if (line.contains("RearScreenLyricsActivity")
                    || line.contains("RearScreenCarControlActivity")
                    || line.contains("RearScreenDesktopActivity")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    if (tidEnd > tidStart) {
                        return Integer.parseInt(line.substring(tidStart, tidEnd).trim());
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "resolveUnderlyingProjectionTaskId: " + e.getMessage());
        }
        return -1;
    }

    private static int parseRootTaskDisplayIdFromLine(String line) {
        int idx = line.indexOf("displayId=");
        if (idx < 0) {
            return -1;
        }
        int start = idx + 10;
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 对齐 3.4：恢复投送 app 并重启 Keeper，不 enable 官方 Launcher。 */
    private void restoreRearProjectedTask(int taskId) {
        try {
            ITaskService ts = ChargingService.getTaskService();
            int d = rearDisplayIdForRestore > 0 ? rearDisplayIdForRestore : 1;
            if (ts != null) {
                ts.forceStopOfficialSubscreenForCharging();
                Thread.sleep(200L);
                ts.executeShellCommand("service call activity_task 50 i32 " + taskId + " i32 " + d);
                Thread.sleep(200L);
                ts.executeShellCommand("service call activity_task 50 i32 " + taskId + " i32 " + d);
                Thread.sleep(300L);
                restartKeeperService();
                LogHelper.d(TAG, "投送 app 已恢复 (taskId=" + taskId + ")");
            } else {
                ChargingRestoreFallback.runPrivilegedShell(
                    "service call activity_task 50 i32 " + taskId + " i32 " + d);
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "恢复投送 app 失败: " + e.getMessage());
            RearSwitchKeeperService.resumeMonitoring();
            restoreRearOfficialLauncher();
        }
    }

    /** 对齐 3.4：恢复官方副屏 Launcher。 */
    private void restoreRearOfficialLauncher() {
        try {
            ITaskService ts = ChargingService.getTaskService();
            int d = rearDisplayIdForRestore > 0 ? rearDisplayIdForRestore : 1;
            String cmd = "am start --display " + d
                + " -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity";
            if (ts != null) {
                ts.executeShellCommand(cmd);
            } else {
                ChargingRestoreFallback.runPrivilegedShell(cmd);
            }
            LogHelper.d(TAG, "官方副屏桌面已恢复");
        } catch (Exception e) {
            LogHelper.w(TAG, "恢复官方副屏桌面失败: " + e.getMessage());
        }
    }

    /** 对齐 3.4：恢复投送后重启 {@link RearSwitchKeeperService}。 */
    private void restartKeeperService() {
        try {
            String lastTask = SwitchToRearQsTileService.getLastMovedTask();
            if (lastTask == null) {
                return;
            }
            Intent serviceIntent = new Intent(this, RearSwitchKeeperService.class);
            serviceIntent.putExtra("lastMovedTask", lastTask);
            boolean keepScreenOn = true;
            try {
                keepScreenOn = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                    .getBoolean("flutter.keep_screen_on_enabled", true);
            } catch (Exception ignored) {
            }
            serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOn);
            startService(serviceIntent);
            LogHelper.d(TAG, "RearSwitchKeeperService 已重启: " + lastTask);
        } catch (Exception e) {
            LogHelper.w(TAG, "重启 Keeper 失败: " + e.getMessage());
        }
    }
}
