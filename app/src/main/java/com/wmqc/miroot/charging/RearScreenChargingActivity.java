package com.wmqc.miroot.charging;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import android.os.RemoteException;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.wmqc.miroot.R;
import com.wmqc.miroot.RearDisplayInputHelper;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.RearScreenWakeManager;
import com.wmqc.miroot.rear.RearAssistService;
import com.wmqc.miroot.rear.RearProjectionProximityGate;
import com.wmqc.miroot.rear.RearSwitchKeeperService;

/**
 * 背屏充电动画 Activity。
 * <ul>
 *   <li>主屏 {@code displayId == 0}：仅透明主题占位，不 {@code setContentView}、不加窗口标志，等待 task 迁往副屏</li>
 *   <li>迁屏后通常触发配置变化并重建 Activity，第二次 {@code onCreate} 在非默认显示屏执行涨水与 UI</li>
 *   <li>若同一实例未重建：由 {@link Choreographer} 逐帧探测 displayId（优先于生命周期回调）、以及
 *       {@link #onConfigurationChanged}、{@link #onResume}、{@link DisplayManager.DisplayListener} 调用
 *       {@link #ensureChargingUiOnRearDisplay()}</li>
 *   <li>调试预览：{@link #EXTRA_DEBUG_MAIN_PREVIEW} 时仍用不透主题并立即展示</li>
 * </ul>
 */
public class RearScreenChargingActivity extends ComponentActivity {

    private static final String TAG = "RearScreenChargingActivity";
    /**
     * 液面从 0 涨到 100% 时的总时长；实际时长 = 该值 × (当前电量/100)，保证「刻度/秒」恒定，
     * 避免固定总时长导致低电量涨得慢、高电量涨得快。
     */
    /** 非「常亮」时约 8s 自动结束。 */
    private static final long AUTO_FINISH_MS = 8000L;
    private static final long RESUME_COMPENSATE_FINISH_MS = 5000L;
    /** 主屏占位若一直未迁到背屏、未 inflate，否则窗口长期残留且不会走 PreDraw 的 8s 结束。 */
    private static final long MAIN_PLACEHOLDER_TIMEOUT_MS = 12_000L;
    /** inflate 后短暂等待 display 稳定，再判断是否错误地占在主屏。 */
    private static final long VERIFY_ON_DEFAULT_DISPLAY_AFTER_INFLATE_MS = 450L;

    private static volatile RearScreenChargingActivity currentInstance;
    public static final String EXTRA_BATTERY_LEVEL = "batteryLevel";
    public static final String EXTRA_REAR_TASK_ID = "rearTaskId";
    /** 与 {@link com.wmqc.miroot.charging.ChargingService} 解析的副屏 id 一致，用于结束后 move / am start --display */
    public static final String EXTRA_REAR_DISPLAY_ID = "rearDisplayId";
    public static final String EXTRA_DEBUG_MAIN_PREVIEW = "debugMainPreview";

    private int rearTaskId = -1;
    private int rearDisplayIdForRestore = 1;
    private int pendingBatteryLevel;
    private GyroWaterRippleView waterView;
    private TextView batteryTextView;
    private float targetFillLevel;
    private boolean debugMainPreview;
    private boolean chargingUiInflated;
    /** 防止 setContentView 重入时再次 inflate。 */
    private boolean rearInflateInProgress;
    private boolean fillPresentationStarted;
    /** 是否已安排自动 finish（含常亮模式下「已处理」）。 */
    private boolean autoFinishScheduled;

    /** 主屏占位阶段：每帧检查是否已迁到背屏，避免苦等 onConfigurationChanged / onResume */
    private boolean rearMigrationWatchActive;
    private final Choreographer.FrameCallback rearMigrationFrameCallback = this::onRearMigrationFrame;
    /**
     * 迁屏过程中 {@link Activity#getDisplay()} 可能短暂非 0，若在主屏误 inflate 会闪一下或被系统纠正。
     * 连续若干帧均为非默认屏后再加载 UI（Intent 里的副屏 id 仅用于结束后恢复，不与 getDisplay 强校验以免超时 finish）。
     */
    /** 迁屏后部分机型上 getDisplay() 会短暂抖动；与 3.1.5「仅背屏才 setContentView」 spirit 一致，多帧确认再 inflate。 */
    private static final int REAR_DISPLAY_STABLE_FRAMES = 2;
    private int rearDisplayStableFrames;

    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            View decor = getWindow() != null ? getWindow().getDecorView() : null;
            if (decor != null) {
                decor.post(() -> {
                    if (waterView != null) {
                        waterView.invalidate();
                    }
                    decor.invalidate();
                    ensureChargingUiOnRearDisplay();
                });
            }
        }
    };

    private final Runnable autoFinishRunnable = this::finish;

    private final Runnable mainPlaceholderTimeoutRunnable = this::finishIfStuckMainPlaceholderWithoutUi;

    private final Runnable verifyDefaultDisplayAfterInflateRunnable = () -> {
        if (isFinishing() || !chargingUiInflated || debugMainPreview) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && getCurrentDisplayIdSafe() == Display.DEFAULT_DISPLAY) {
            LogHelper.w(TAG, "充电视图已加载但窗口仍在默认屏，finish 避免占住主屏且不自动结束");
            cancelMainPlaceholderTimeout();
            finish();
        }
    };

    private void finishIfStuckMainPlaceholderWithoutUi() {
        if (isFinishing() || debugMainPreview || chargingUiInflated) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && getCurrentDisplayIdSafe() == Display.DEFAULT_DISPLAY) {
            LogHelper.w(TAG, "主屏占位超时未迁背屏且无充电视图，finish 避免 Activity 不销毁");
            finish();
        }
    }

    private void scheduleMainPlaceholderTimeout(View decor) {
        if (decor == null) {
            return;
        }
        decor.removeCallbacks(mainPlaceholderTimeoutRunnable);
        decor.postDelayed(mainPlaceholderTimeoutRunnable, MAIN_PLACEHOLDER_TIMEOUT_MS);
    }

    private void cancelMainPlaceholderTimeout() {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.removeCallbacks(mainPlaceholderTimeoutRunnable);
        }
    }

    /**
     * 已加载充电视图却仍绑定 {@link Display#DEFAULT_DISPLAY} 时，说明误在主屏展示，应尽快 finish。
     */
    private void scheduleFinishIfInflatedOnDefaultDisplay() {
        if (debugMainPreview || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor == null) {
            return;
        }
        decor.removeCallbacks(verifyDefaultDisplayAfterInflateRunnable);
        decor.postDelayed(verifyDefaultDisplayAfterInflateRunnable, VERIFY_ON_DEFAULT_DISPLAY_AFTER_INFLATE_MS);
    }

    private void cancelVerifyDefaultDisplayAfterInflate() {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.removeCallbacks(verifyDefaultDisplayAfterInflateRunnable);
        }
    }

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION.equals(action)) {
                LogHelper.d(TAG, "收到拔电/结束充电动画广播，finish");
                finish();
            } else if (ChargingIntents.ACTION_INTERRUPT_CHARGING_ANIMATION.equals(action)) {
                LogHelper.d(TAG, "收到打断充电动画广播，finish");
                finish();
            } else if (ChargingIntents.ACTION_UPDATE_CHARGING_BATTERY.equals(action)) {
                int newLevel = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1);
                if (newLevel >= 0) {
                    updateBatteryLevel(newLevel);
                }
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

    /** 供 {@link RearScreenWakeManager} 反射检测实例是否存活。 */
    public static RearScreenChargingActivity getCurrentInstance() {
        return currentInstance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        debugMainPreview = intent.getBooleanExtra(EXTRA_DEBUG_MAIN_PREVIEW, false);
        // 调试主屏预览或旧系统：透明主题；主屏占位时不绘制充电底色。
        // 背屏在 inflateChargingContentOnRear 里 setWindowBackground + 布局底色保证不透（避免副屏全黑）。
        if (debugMainPreview || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setTheme(R.style.Theme_MiRoot_ChargingRear);
        }
        super.onCreate(savedInstanceState);

        rearTaskId = intent.getIntExtra(EXTRA_REAR_TASK_ID, -1);
        rearDisplayIdForRestore = intent.getIntExtra(EXTRA_REAR_DISPLAY_ID, 1);
        if (rearDisplayIdForRestore <= 0) {
            rearDisplayIdForRestore = 1;
        }
        int level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, 0);
        level = Math.max(0, Math.min(100, level));
        pendingBatteryLevel = level;
        targetFillLevel = level / 100f;

        registerFinishReceiverAndDisplayListener();

        if (debugMainPreview) {
            getWindow().setFormat(PixelFormat.OPAQUE);
            applyRearChargingWindowFlags();
            hideSystemUi();
            applyRearDensityBeforeInflateIfPossible();
            setContentView(R.layout.activity_rear_screen_charging);
            TextView badge = findViewById(R.id.charging_debug_badge);
            if (badge != null) {
                badge.setVisibility(View.VISIBLE);
            }
            bindChargingViews(level);
            applySafeInsetsToBatteryText();
            chargingUiInflated = true;
            currentInstance = RearScreenChargingActivity.this;
            startFillAndBatteryPresentation();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int displayId = getCurrentDisplayIdSafe();
            if (displayId == Display.DEFAULT_DISPLAY) {
                LogHelper.d(TAG, "主屏占位：不 setContentView，等待迁屏；超时则 finish 避免窗口不销毁");
                currentInstance = this;
                View decor = getWindow().getDecorView();
                decor.post(this::startRearMigrationWatch);
                scheduleMainPlaceholderTimeout(decor);
                return;
            }
            LogHelper.d(TAG, "非默认显示屏启动 displayId=" + displayId + "，加载充电动画");
            inflateChargingContentOnRear();
            startFillAndBatteryPresentation();
            return;
        }

        getWindow().setFormat(PixelFormat.OPAQUE);
        applyRearChargingWindowFlags();
        hideSystemUi();
        applyRearDensityBeforeInflateIfPossible();
        setContentView(R.layout.activity_rear_screen_charging);
        bindChargingViews(level);
        applySafeInsetsToBatteryText();
        chargingUiInflated = true;
        currentInstance = this;
        startFillAndBatteryPresentation();
    }

    private void registerFinishReceiverAndDisplayListener() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION);
        filter.addAction(ChargingIntents.ACTION_INTERRUPT_CHARGING_ANIMATION);
        filter.addAction(ChargingIntents.ACTION_UPDATE_CHARGING_BATTERY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishReceiver, filter);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            displayManager = getSystemService(DisplayManager.class);
            if (displayManager != null) {
                displayManager.registerDisplayListener(
                    displayListener,
                    new Handler(Looper.getMainLooper()));
            }
        }
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

    /** 副屏可能是 1、2 等，不能写死 == 1 */
    private boolean isOnNonDefaultDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        return getCurrentDisplayIdSafe() != Display.DEFAULT_DISPLAY;
    }

    private void applyRearChargingWindowFlags() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
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
        ViewCompat.setOnApplyWindowInsetsListener(batteryTextView, (v, insets) -> {
            Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int left = Math.max(cut.left, sys.left);
            int top = Math.max(cut.top, sys.top);
            int right = Math.max(cut.right, sys.right);
            int bottom = Math.max(cut.bottom, sys.bottom);
            if (left == 0 && top == 0 && right == 0 && bottom == 0) {
                return insets;
            }
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (p != null) {
                p.leftMargin = left;
                p.topMargin = top;
                p.rightMargin = right;
                p.bottomMargin = bottom;
                v.setLayoutParams(p);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(batteryTextView);
    }

    private void startRearMigrationWatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || debugMainPreview || chargingUiInflated) {
            return;
        }
        if (rearMigrationWatchActive) {
            return;
        }
        rearDisplayStableFrames = 0;
        rearMigrationWatchActive = true;
        Choreographer.getInstance().postFrameCallback(rearMigrationFrameCallback);
    }

    private void stopRearMigrationWatch() {
        if (!rearMigrationWatchActive) {
            return;
        }
        rearMigrationWatchActive = false;
        Choreographer.getInstance().removeFrameCallback(rearMigrationFrameCallback);
    }

    private void onRearMigrationFrame(long frameTimeNanos) {
        if (!rearMigrationWatchActive || isFinishing() || chargingUiInflated) {
            rearMigrationWatchActive = false;
            return;
        }
        if (isDestroyed()) {
            rearMigrationWatchActive = false;
            return;
        }
        if (isOnNonDefaultDisplay()) {
            rearDisplayStableFrames++;
            if (rearDisplayStableFrames >= REAR_DISPLAY_STABLE_FRAMES) {
                ensureChargingUiOnRearDisplay();
            }
        } else {
            rearDisplayStableFrames = 0;
        }
        if (chargingUiInflated || isFinishing()) {
            rearMigrationWatchActive = false;
            return;
        }
        if (getWindow() == null) {
            rearMigrationWatchActive = false;
            return;
        }
        Choreographer.getInstance().postFrameCallback(rearMigrationFrameCallback);
    }

    private void bindChargingViews(int level) {
        waterView = findViewById(R.id.gyro_water_ripple);
        batteryTextView = findViewById(R.id.battery_text);
        batteryTextView.setText(level + "%");
        if (level > 20) {
            batteryTextView.setTextColor(0xFF66FF99);
        } else {
            batteryTextView.setTextColor(0xFFFFC896);
        }
        waterView.setBatteryPercentForTint(level);
        waterView.setFillLevel(0f);
    }

    /**
     * 在背屏（任意非 DEFAULT_DISPLAY）加载不透充电视图；可从主屏占位或重建后的 onCreate 调用。
     */
    private void inflateChargingContentOnRear() {
        if (chargingUiInflated || rearInflateInProgress) {
            return;
        }
        cancelMainPlaceholderTimeout();
        stopRearMigrationWatch();
        rearDisplayStableFrames = 0;
        rearInflateInProgress = true;
        try {
            applyRearChargingWindowFlags();
            getWindow().setFormat(PixelFormat.OPAQUE);
            getWindow().setBackgroundDrawableResource(R.color.charging_window_bg);

            hideSystemUi();
            applyRearDensityBeforeInflateIfPossible();
            setContentView(R.layout.activity_rear_screen_charging);
            bindChargingViews(pendingBatteryLevel);
            applySafeInsetsToBatteryText();
            batteryTextView.setAlpha(0f);
            batteryTextView.setScaleX(0.8f);
            batteryTextView.setScaleY(0.8f);
            if (waterView != null) {
                waterView.invalidate();
            }
            chargingUiInflated = true;
            currentInstance = this;
            scheduleFinishIfInflatedOnDefaultDisplay();
        } finally {
            rearInflateInProgress = false;
        }
    }

    /**
     * 同一 Activity 实例从主屏被迁到背屏且未走重建时，在此补载 UI（兜底）。
     */
    private void ensureChargingUiOnRearDisplay() {
        if (debugMainPreview || chargingUiInflated || rearInflateInProgress) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        if (!isOnNonDefaultDisplay()) {
            return;
        }
        LogHelper.d(TAG, "ensureChargingUi：副屏 displayId=" + getCurrentDisplayIdSafe());
        inflateChargingContentOnRear();
        startFillAndBatteryPresentation();
    }

    private void startFillAndBatteryPresentation() {
        if (fillPresentationStarted || waterView == null || batteryTextView == null) {
            return;
        }
        fillPresentationStarted = true;

        waterView.setFillLevel(0f);
        // 液面上涨：恒定刻度速率（Linear），时长随目标电量比例变化；基准时长由 prefs 涨水速度调节
        int speedPercent = ChargingAnimationPrefs.getFillRiseSpeedPercent(this);
        long baseMsForFull = Math.round(ChargingAnimationPrefs.FILL_MS_FOR_FULL_SCALE * 100.0 / (double) Math.max(1, speedPercent));
        ValueAnimator fillAnim = ValueAnimator.ofFloat(0f, targetFillLevel);
        fillAnim.setDuration(Math.max(0L, Math.round(targetFillLevel * baseMsForFull)));
        fillAnim.setInterpolator(new LinearInterpolator());
        fillAnim.addUpdateListener(a -> waterView.setFillLevel((Float) a.getAnimatedValue()));
        fillAnim.start();

        batteryTextView.setAlpha(0f);
        batteryTextView.setScaleX(0.8f);
        batteryTextView.setScaleY(0.8f);
        batteryTextView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setStartDelay(600)
            .setInterpolator(new DecelerateInterpolator(2f))
            .start();

        if (!debugMainPreview) {
            View container = findViewById(R.id.charging_container);
            if (container != null) {
                container.removeCallbacks(autoFinishRunnable);
                if (!isChargingAlwaysOn()) {
                    // 8s 从「即将首帧绘制」起算，避免 Surface 未就绪时已倒计时结束 → 黑屏到快结束才闪一下
                    scheduleAutoFinishAfterFirstPreDraw(container);
                }
                autoFinishScheduled = true;
            }
        }
    }

    private void scheduleAutoFinishAfterFirstPreDraw(View container) {
        ViewTreeObserver observer = container.getViewTreeObserver();
        if (!observer.isAlive()) {
            container.postDelayed(autoFinishRunnable, AUTO_FINISH_MS);
            return;
        }
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver obs = container.getViewTreeObserver();
                if (obs.isAlive()) {
                    obs.removeOnPreDrawListener(this);
                }
                if (isFinishing() || !chargingUiInflated) {
                    return true;
                }
                container.removeCallbacks(autoFinishRunnable);
                container.postDelayed(autoFinishRunnable, AUTO_FINISH_MS);
                return true;
            }
        });
    }

    private boolean isChargingAlwaysOn() {
        return getSharedPreferences(ChargingAnimationPrefs.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(ChargingAnimationPrefs.KEY_ALWAYS_ON, false);
    }

    private void updateBatteryLevel(int newLevel) {
        newLevel = Math.max(0, Math.min(100, newLevel));
        pendingBatteryLevel = newLevel;
        targetFillLevel = newLevel / 100f;
        try {
            if (waterView != null) {
                waterView.setFillLevel(newLevel / 100f);
                waterView.setBatteryPercentForTint(newLevel);
            }
            if (batteryTextView != null) {
                batteryTextView.setText(newLevel + "%");
                if (newLevel > 20) {
                    batteryTextView.setTextColor(0xFF66FF99);
                } else {
                    batteryTextView.setTextColor(0xFFFFC896);
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "updateBatteryLevel: " + e.getMessage());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ensureChargingUiOnRearDisplay();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (chargingUiInflated) {
                hideSystemUi();
            }
            ensureChargingUiOnRearDisplay();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int did = getCurrentDisplayIdSafe();
            if (did != Display.DEFAULT_DISPLAY && !chargingUiInflated) {
                applyRearChargingWindowFlags();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true);
                    setTurnScreenOn(true);
                }
            }
        }
        ensureChargingUiOnRearDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int did = getCurrentDisplayIdSafe();
            if (did != Display.DEFAULT_DISPLAY && !autoFinishScheduled && chargingUiInflated && !debugMainPreview) {
                if (!isChargingAlwaysOn()) {
                    LogHelper.d(TAG, "未安排自动销毁，补偿 " + RESUME_COMPENSATE_FINISH_MS + "ms 后 finish");
                    new Handler(Looper.getMainLooper()).postDelayed(this::finish, RESUME_COMPENSATE_FINISH_MS);
                }
                autoFinishScheduled = true;
            }
        }
        // 自动结束改由 scheduleAutoFinishAfterFirstPreDraw 在首帧前启动，不在每次 onResume 重置 8s，避免与 Surface 时序打架
    }

    /**
     * 与 {@link com.wmqc.miroot.car.RearScreenCarControlActivity#hideSystemUI} 一致：仅隐藏状态栏，
     * 保留导航栏/手势 inset，避免边缘滑动被系统当成「拉出系统栏」而非返回。
     */
    private void hideSystemUi() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.show(android.view.WindowInsets.Type.navigationBars());
                c.hide(android.view.WindowInsets.Type.statusBars());
                c.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onDestroy() {
        stopRearMigrationWatch();
        cancelMainPlaceholderTimeout();
        cancelVerifyDefaultDisplayAfterInflate();
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.removeCallbacks(autoFinishRunnable);
        }
        View container = findViewById(R.id.charging_container);
        if (container != null) {
            container.removeCallbacks(autoFinishRunnable);
        }
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {
            }
            displayManager = null;
        }
        try {
            unregisterReceiver(finishReceiver);
        } catch (Exception e) {
            LogHelper.w(TAG, "unregisterReceiver: " + e.getMessage());
        }
        final int displayIdBeforeDestroy =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? getCurrentDisplayIdSafe() : Display.DEFAULT_DISPLAY;
        super.onDestroy();

        if (this != currentInstance) {
            LogHelper.w(TAG, "旧实例 onDestroy，跳过协调器与恢复");
            return;
        }

        RearScreenWakeManager.getInstance().stopWakeService(
            getApplicationContext(), RearScreenChargingActivity.class);

        boolean shouldRestore = RearChargingAnimationCoordinator.endAnimation(
            RearChargingAnimationCoordinator.AnimationType.CHARGING);
        currentInstance = null;
        if (!shouldRestore) {
            LogHelper.d(TAG, "充电动画被打断结束，跳过恢复背屏");
            if (rearTaskId > 0) {
                RearAssistService.resumeMonitoringAfterCharging();
                RearSwitchKeeperService.resumeMonitoring();
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (displayIdBeforeDestroy != Display.DEFAULT_DISPLAY) {
                restoreRearAfterCharging();
            }
        }
    }

    private void restoreRearAfterCharging() {
        final int tid = rearTaskId;
        final boolean resumeAssist = tid > 0;
        new Thread(() -> {
            try {
                Thread.sleep(50);
                ITaskService ts = ChargingService.getTaskService();
                if (ts == null) {
                    LogHelper.w(TAG, "恢复背屏：TaskService 为空，尝试特权 shell");
                    if (tid > 0) {
                        String cmd = "service call activity_task 50 i32 " + tid + " i32 " + rearDisplayIdForRestore;
                        ChargingRestoreFallback.runPrivilegedShell(cmd);
                        ChargingRestoreFallback.runPrivilegedShell(cmd);
                    } else {
                        ChargingRestoreFallback.runPrivilegedShell(
                            "am start --display " + rearDisplayIdForRestore
                                + " -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity");
                    }
                    return;
                }
                if (tid > 0) {
                    restoreRearProjectedTask(ts, tid);
                } else {
                    restoreRearOfficialLauncher(ts);
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "恢复背屏失败: " + e.getMessage());
            } finally {
                if (resumeAssist) {
                    RearAssistService.resumeMonitoringAfterCharging();
                    RearProjectionProximityGate.resumeAfterCharging();
                    RearSwitchKeeperService.resumeMonitoring();
                }
            }
        }, "miroot-charging-restore").start();
    }

    private void restoreRearProjectedTask(ITaskService ts, int taskId) {
        int d = rearDisplayIdForRestore;
        String shellMove = "service call activity_task 50 i32 " + taskId + " i32 " + d;
        try {
            ChargingOfficialSubscreen.applyDisableBeforeChargingFlow(getApplicationContext(), ts);
            Thread.sleep(200);
            ts.moveTaskToDisplay(taskId, d);
            Thread.sleep(200);
            ts.moveTaskToDisplay(taskId, d);
            Thread.sleep(300);
            if (!ts.isTaskOnDisplay(taskId, d)) {
                LogHelper.w(TAG, "taskId=" + taskId + " 未在背屏，shell 再试并回退官方桌面");
                ChargingRestoreFallback.runPrivilegedShell(shellMove);
                ChargingRestoreFallback.runPrivilegedShell(shellMove);
                restoreRearOfficialLauncher(ts);
                return;
            }
            RearAssistService.sync(getApplicationContext());
            LogHelper.d(TAG, "投屏 task 已恢复，已请求 RearAssistService.sync");
        } catch (RemoteException e) {
            LogHelper.w(TAG, "恢复投屏 task 失败: " + e.getMessage());
            ChargingRestoreFallback.runPrivilegedShell(shellMove);
            try {
                restoreRearOfficialLauncher(ts);
            } catch (Exception e2) {
                LogHelper.w(TAG, "回退官方桌面失败: " + e2.getMessage());
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "恢复投屏 task 异常: " + e.getMessage());
            try {
                restoreRearOfficialLauncher(ts);
            } catch (Exception e2) {
                LogHelper.w(TAG, "回退官方桌面失败: " + e2.getMessage());
            }
        }
    }

    /**
     * 仅 {@code am start} 恢复官方副屏桌面，不先 {@code enableSubScreenLauncher}。
     */
    private void restoreRearOfficialLauncher(ITaskService ts) {
        String am = "am start --display " + rearDisplayIdForRestore
            + " -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity";
        try {
            if (ts != null) {
                ts.executeShellCommand(am);
            } else {
                ChargingRestoreFallback.runPrivilegedShell(am);
            }
            LogHelper.d(TAG, "已恢复官方副屏桌面");
        } catch (Exception e) {
            LogHelper.w(TAG, "恢复官方副屏桌面失败: " + e.getMessage());
            ChargingRestoreFallback.runPrivilegedShell(am);
        }
    }
}
