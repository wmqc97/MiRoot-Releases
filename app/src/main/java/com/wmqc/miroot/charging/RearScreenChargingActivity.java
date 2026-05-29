package com.wmqc.miroot.charging;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
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
import com.wmqc.miroot.lyrics.DeviceModelHelper;
import com.wmqc.miroot.lyrics.DisplayInfoCache;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.RearDisplayHelper;
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
    public static final String EXTRA_REAR_TASK_ID = "rearTaskId";
    /** 与 {@link com.wmqc.miroot.charging.ChargingService} 解析的副屏 id 一致，用于结束后 move / am start --display */
    public static final String EXTRA_REAR_DISPLAY_ID = "rearDisplayId";

    private int rearTaskId = -1;
    private int rearDisplayIdForRestore = 1;
    private int pendingBatteryLevel;
    private GyroWaterRippleView waterView;
    private TextView batteryTextView;
    private ImageView lightningIcon;
    private LinearLayout chargingInfoBar;
    private ValueAnimator lightningPulseAnimator;
    private final Runnable batteryInfoRunnable = this::queryAndUpdateBatteryInfo;
    private float targetFillLevel;
    private boolean chargingUiInflated;
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
    /** 背屏息屏期间暂停自动 finish，避免误关下层投屏。 */
    private boolean rearScreenOffPaused;
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
        if (finishRequestedByMiRoot) {
        }
        prepareRearProjectionVisibleBeforeFinish();
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
        int level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, 0);
        level = Math.max(0, Math.min(100, level));
        pendingBatteryLevel = level;
        targetFillLevel = level / 100f;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getCurrentDisplayIdSafe() == Display.DEFAULT_DISPLAY) {
                // 对齐 3.4：主屏仅占位，等待 ChargingService 迁屏后重建本 Activity。
                LogHelper.d(TAG, "在主屏启动，保持透明占位符，等待移动");
                RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(
                        this, RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER);
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
        } finally {
            rearInflateInProgress = false;
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

    private void bindChargingViews(int level) {
        waterView = findViewById(R.id.gyro_water_ripple);
        batteryTextView = findViewById(R.id.battery_text);
        batteryTextView.setText(level + "%");
        // 对齐 3.4 activity_rear_screen_charging：中央电量为纯白字。
        batteryTextView.setTextColor(0xFFFFFFFF);
        waterView.setBatteryPercentForTint(level);
        waterView.setFillLevel(0f);

        lightningIcon = findViewById(R.id.lightning_icon);
        startLightningPulse();

        // 安全区域包装层统一左避让摄像头，⚡73% 和信息卡在其内居中
        View safeWrapper = findViewById(R.id.safe_area_wrapper);
        if (safeWrapper != null) {
            Insets safe = computeInitialBatterySafeInsets();
            int rightPx = Math.round(getResources().getDisplayMetrics().density * 10);
            safeWrapper.setPadding(safe.left, 0, rightPx, 0);
        }

        chargingInfoBar = findViewById(R.id.charging_info_bar);
        rebuildInfoBar();
        // 延迟 1s 等 UI 稳定后开始读取电池信息
        mainHandler.postDelayed(this::queryAndUpdateBatteryInfo, 1000L);
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
        initialFillAnimator.addUpdateListener(a -> waterView.setFillLevel((Float) a.getAnimatedValue()));
        initialFillAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                initialFillAnimating = false;
                if (waterView != null) {
                    // 动画期间可能收到电量更新，结束后对齐到最新目标值。
                    waterView.setFillLevel(targetFillLevel);
                }
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                initialFillAnimating = false;
            }
        });
        initialFillAnimator.start();

        batteryTextView.setAlpha(0f);
        batteryTextView.setScaleX(0.8f);
        batteryTextView.setScaleY(0.8f);
        batteryTextView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setStartDelay(600)
            .setInterpolator(new DecelerateInterpolator(2.0f))
            .start();

        View container = findViewById(R.id.charging_container);
        if (container != null) {
            container.removeCallbacks(autoFinishRunnable);
            if (!isChargingAlwaysOn() && !rearScreenOffPaused) {
                container.postDelayed(autoFinishRunnable, AUTO_FINISH_MS);
                LogHelper.d(TAG, "动画已启动，" + (AUTO_FINISH_MS / 1000L) + " 秒后自动关闭");
            } else {
                LogHelper.d(TAG, "动画已启动，充电常亮模式，不自动关闭");
            }
            autoFinishScheduled = true;
        }
    }

    private boolean isChargingAlwaysOn() {
        return ChargingAnimationPrefs.isAlwaysOn(this);
    }

    private void updateBatteryLevel(int newLevel) {
        newLevel = Math.max(0, Math.min(100, newLevel));
        pendingBatteryLevel = newLevel;
        targetFillLevel = newLevel / 100f;
        try {
            if (waterView != null) {
                // 首段涨水动画期间仅更新目标值，不直接跳液位，避免覆盖滑块控制的时长。
                if (!initialFillAnimating) {
                    waterView.setFillLevel(targetFillLevel);
                }
                waterView.setBatteryPercentForTint(newLevel);
            }
            if (batteryTextView != null) {
                batteryTextView.setText(newLevel + "%");
                batteryTextView.setTextColor(0xFFFFFFFF);
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "updateBatteryLevel: " + e.getMessage());
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

    /** 根据用户配置重建底部信息卡：清空后按选中项动态添加 TextView。 */
    private void rebuildInfoBar() {
        if (chargingInfoBar == null) return;
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
    }

    /** 返回指定信息项的当前显示文本。 */
    private String getItemDisplayValue(String itemId, int voltage, int temp,
                                       int plugged, int level, int scale,
                                       int currentMicroA, int chargeCounterMicroAh,
                                       float powerW, boolean validPower) {
        float levelPct = level * 100f / Math.max(scale, 1);
        switch (itemId) {
            case "power":
                return validPower
                        ? String.format(Locale.US, "%.1fW", powerW) : "--W";
            case "charging_type": {
                if (plugged == BatteryManager.BATTERY_PLUGGED_AC)
                    return validPower && powerW >= 18f ? "快充" : "充电";
                else if (plugged == BatteryManager.BATTERY_PLUGGED_USB)
                    return "USB";
                else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS)
                    return "无线";
                return "--";
            }
            case "time":
                return estimatetime(levelPct, currentMicroA, chargeCounterMicroAh);
            case "temperature":
                return String.format(Locale.US, "%.1f°C", temp / 10f);
            case "voltage":
                return voltage > 0
                        ? String.format(Locale.US, "%.2fV", voltage / 1000f) : "--";
            case "current": {
                float ma = Math.abs(currentMicroA) / 1000f;
                return ma >= 1000
                        ? String.format(Locale.US, "%.1fA", ma / 1000f)
                        : String.format(Locale.US, "%.0fmA", ma);
            }
            case "capacity": {
                if (chargeCounterMicroAh > 0) {
                    return String.format(Locale.US, "%.0fmAh",
                            chargeCounterMicroAh / 1000f);
                }
                return "--";
            }
            default:
                return "--";
        }
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
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);

            // 电流和电荷计数通过 getIntProperty 获取
            int current = 0;
            int chargeCounterMicroAh = 0;
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                chargeCounterMicroAh = bm.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            }

            // 功率 W = V × A
            float powerW = (voltage / 1000f) * (Math.abs(current) / 1000000f);
            boolean validPower = powerW >= 0.1f;

            // 遍历底部信息卡的所有 TextView，根据 tag 更新对应数值
            if (chargingInfoBar != null) {
                int childCount = chargingInfoBar.getChildCount();
                boolean anyUpdated = false;
                for (int i = 0; i < childCount; i++) {
                    View child = chargingInfoBar.getChildAt(i);
                    if (child instanceof TextView) {
                        String tag = (String) child.getTag();
                        if (tag != null) {
                            ((TextView) child).setText(getItemDisplayValue(
                                    tag, voltage, temp, plugged, level, scale,
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

    /** 估算充满剩余时间，格式 "约 N 分钟" 或 "--"。 */
    private String estimatetime(float levelPct, int currentMicroA,
                                int chargeCounterMicroAh) {
        if (levelPct >= 99f) {
            return "已充满";
        }
        if (chargeCounterMicroAh <= 0 || Math.abs(currentMicroA) < 50000) {
            return "--";
        }
        float currentMA = Math.abs(currentMicroA) / 1000f;
        float totalCapacityMicroAh = chargeCounterMicroAh / (levelPct / 100f);
        float remainingMicroAh = totalCapacityMicroAh - chargeCounterMicroAh;
        float hoursRemaining = remainingMicroAh / Math.abs(currentMicroA);
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
        final int displayIdBeforeDestroy =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? getCurrentDisplayIdSafe() : Display.DEFAULT_DISPLAY;
        super.onDestroy();

        if (this != currentInstance) {
            LogHelper.w(TAG, "旧实例 onDestroy，跳过协调器与恢复");
            return;
        }

        boolean shouldRestore = RearChargingAnimationCoordinator.endAnimation(
            RearChargingAnimationCoordinator.AnimationType.CHARGING);
        currentInstance = null;

        // 充电动画已结束，通知 ChargingService 停止常亮唤醒循环
        Intent stopWakeup = new Intent(ChargingIntents.ACTION_STOP_CHARGING_WAKEUP);
        stopWakeup.setPackage(getPackageName());
        sendBroadcast(stopWakeup);

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
