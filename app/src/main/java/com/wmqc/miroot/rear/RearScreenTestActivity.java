package com.wmqc.miroot.rear;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.ComponentActivity;

import com.wmqc.miroot.R;
import com.wmqc.miroot.RearDisplayInputHelper;
import com.wmqc.miroot.lyrics.LogHelper;

/**
 * 功能页入口：在背屏展示白底居中测试文案。迁屏逻辑与 {@link com.wmqc.miroot.charging.RearScreenChargingActivity} 一致。
 */
public class RearScreenTestActivity extends ComponentActivity {

    private static final String TAG = "RearScreenTest";
    private static final long REAR_MOVE_FALLBACK_MS = 10_000L;
    private static final int REAR_DISPLAY_STABLE_FRAMES = 3;

    private boolean testUiInflated;
    private boolean rearMigrationWatchActive;
    private int rearDisplayStableFrames;
    private final Choreographer.FrameCallback rearMigrationFrameCallback = this::onRearMigrationFrame;

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
                decor.post(RearScreenTestActivity.this::ensureTestUiOnRearDisplay);
            }
        }
    };

    private final Runnable rearMoveFallbackRunnable = () -> {
        if (!testUiInflated) {
            LogHelper.w(TAG, "超时未在背屏加载测试界面，finish");
            stopRearMigrationWatch();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            displayManager = getSystemService(DisplayManager.class);
            if (displayManager != null) {
                displayManager.registerDisplayListener(
                    displayListener,
                    new Handler(Looper.getMainLooper()));
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int displayId = getCurrentDisplayIdSafe();
            if (displayId == Display.DEFAULT_DISPLAY) {
                LogHelper.d(TAG, "主屏占位：等待迁往背屏");
                View decor = getWindow().getDecorView();
                decor.postDelayed(rearMoveFallbackRunnable, REAR_MOVE_FALLBACK_MS);
                decor.post(this::startRearMigrationWatch);
                return;
            }
            LogHelper.d(TAG, "非默认屏启动 displayId=" + displayId);
            inflateTestContentOnRear();
            return;
        }

        inflateTestContentOnRear();
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

    private boolean isOnNonDefaultDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        return getCurrentDisplayIdSafe() != Display.DEFAULT_DISPLAY;
    }

    private void cancelRearMigrationTimeout() {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.removeCallbacks(rearMoveFallbackRunnable);
        }
    }

    private void startRearMigrationWatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || testUiInflated) {
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
        if (!rearMigrationWatchActive || isFinishing() || testUiInflated) {
            rearMigrationWatchActive = false;
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
            rearMigrationWatchActive = false;
            return;
        }
        if (isOnNonDefaultDisplay()) {
            rearDisplayStableFrames++;
            if (rearDisplayStableFrames >= REAR_DISPLAY_STABLE_FRAMES) {
                ensureTestUiOnRearDisplay();
            }
        } else {
            rearDisplayStableFrames = 0;
        }
        if (testUiInflated || isFinishing()) {
            rearMigrationWatchActive = false;
            return;
        }
        if (getWindow() == null) {
            rearMigrationWatchActive = false;
            return;
        }
        Choreographer.getInstance().postFrameCallback(rearMigrationFrameCallback);
    }

    private void applyRearWindowFlags() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(lp);
        }
    }

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

    private void inflateTestContentOnRear() {
        if (testUiInflated) {
            return;
        }
        stopRearMigrationWatch();
        rearDisplayStableFrames = 0;
        testUiInflated = true;
        cancelRearMigrationTimeout();

        applyRearWindowFlags();
        getWindow().setFormat(PixelFormat.OPAQUE);
        getWindow().setBackgroundDrawableResource(android.R.color.white);
        hideSystemUi();
        setContentView(R.layout.activity_rear_screen_test);
    }

    private void ensureTestUiOnRearDisplay() {
        if (testUiInflated) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        if (!isOnNonDefaultDisplay()) {
            return;
        }
        LogHelper.d(TAG, "ensureTestUi：副屏 displayId=" + getCurrentDisplayIdSafe());
        inflateTestContentOnRear();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ensureTestUiOnRearDisplay();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (testUiInflated) {
                hideSystemUi();
            }
            ensureTestUiOnRearDisplay();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int did = getCurrentDisplayIdSafe();
            if (did != Display.DEFAULT_DISPLAY && !testUiInflated) {
                applyRearWindowFlags();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true);
                    setTurnScreenOn(true);
                }
            }
        }
        ensureTestUiOnRearDisplay();
    }

    @Override
    protected void onDestroy() {
        stopRearMigrationWatch();
        cancelRearMigrationTimeout();
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {
            }
            displayManager = null;
        }
        super.onDestroy();
    }
}
