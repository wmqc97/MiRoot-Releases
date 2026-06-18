package com.wmqc.miroot.lyrics;
import com.wmqc.miroot.display.MainDisplayUi;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Point;
import android.util.TypedValue;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.wmqc.miroot.BuildConfig;
import com.wmqc.miroot.charging.RearChargingAnimationCoordinator;
import com.wmqc.miroot.MainActivity;
import com.wmqc.miroot.R;
import com.wmqc.miroot.license.OfflineActivationRepository;
import com.wmqc.miroot.BottomSwipeExitHelper;
import com.wmqc.miroot.RearDisplayInputHelper;
import com.wmqc.miroot.service.MiRootNotificationListenerService;
import com.wmqc.miroot.rear.OfficialSubscreenMiRootProjectionSession;
import com.wmqc.miroot.rear.ProjectionOngoingNotifications;
import com.wmqc.miroot.rear.ProjectionOnlyNotificationHelper;
import com.wmqc.miroot.rear.RearAssistPrefs;
import com.wmqc.miroot.AppExecutors;
import com.wmqc.miroot.lyrics.RootTaskServiceConnector;
import com.wmqc.miroot.rear.RearMirootProjectionLifecycle;
import com.wmqc.miroot.rear.RearProjectionProximitySession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.Lifecycle;


/**
 * 背屏歌词显示Activity
 * 显示当前播放的音乐歌词，仅在背屏显示
 *
 * <p>渐进拆分建议（减薄本类、便于机型适配）：</p>
 * <ul>
 *   <li>显示与生命周期：displayId / 主屏占位超时 / onStop 宽限 / 窗口与沉浸式；</li>
 *   <li>手势与系统 UI：官方手势开关、底部滑动退出、歌词行点击；</li>
 *   <li>MediaSession：控制器绑定、回调、酷我策略与第三方拉词（酷我策略与回调已拆至 {@link KuwoCarLyricsPolicy}、{@link RearLyricsMediaSessionCallbacks}）；</li>
 *   <li>传感器与辅助：{@link com.wmqc.miroot.rear.RearProjectionProximitySession}、唤醒服务等。</li>
 * </ul>
 */
public class RearScreenLyricsActivity extends ComponentActivity {
    private static final String TAG = "RearScreenLyricsActivity";
    
    
    // 静态实例，用于外部调用applySettings()
    private static RearScreenLyricsActivity currentInstance;
    
    /**
     * 标记投屏的启动方式
     * true = 通过广播启动（不受自动投屏开关影响）
     * false = 通过自动投屏或按钮启动（受自动投屏开关影响）
     */
    private static boolean isBroadcastStarted = false;
    
    /**
     * 标记是否在主屏横屏模式（通过磁贴快捷键启动）
     */
    private boolean isMainScreenLandscapeMode = false;

    /** 首次成功创建投屏 UI 时所在 displayId（与 {@link com.wmqc.miroot.car.RearScreenCarControlActivity} 一致）：仅在实际迁离该屏时结束投屏，避免背屏双击等触发的短暂 onStop 误关投屏 */
    private int initialDisplayId = -1;

    /**
     * 非磁贴时主屏透明占位等待迁屏的最长时间；超时仍非背屏则销毁（需覆盖 am move-stack 的典型耗时）。
     */
    private static final long MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS = 3500L;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Handler mainScreenPolicyHandler = uiHandler;
    private Runnable mainScreenPlaceholderTimeoutRunnable;

    /**
     * 仍在背屏 display 1 但已 onStop（例如回到背屏桌面/其它前台）时，displayId 不会变，仅靠 movedToOtherDisplay 无法销毁。
     * 短时宽限后若仍未回到 STARTED，则视为已离开投屏界面并 finish。
     */
    private static final long REAR_STOP_EXIT_GRACE_MS = 800L;
    private Runnable rearStopExitGraceRunnable;
    private long rearStopExitGraceDeadlineElapsedMs = 0L;
    private long rearStopExitGraceRemainingMs = 0L;
    /** 双击息屏等 {@link Intent#ACTION_SCREEN_OFF} 期间暂停宽限倒计时，亮屏/回前台后再取消而非续跑 */
    private boolean rearStopExitGracePausedForScreenOff;
    
    /**
     * 获取当前Activity实例（用于实时更新设置）
     */
    public static RearScreenLyricsActivity getCurrentInstance() {
        return currentInstance;
    }
    
    /**
     * 检查投屏是否通过广播启动
     */
    public static boolean isBroadcastStarted() {
        return isBroadcastStarted;
    }
    
    /**
     * 设置投屏启动方式
     */
    public static void setBroadcastStarted(boolean broadcastStarted) {
        isBroadcastStarted = broadcastStarted;
    }

    /**
     * 通知栏结束投屏：由 {@link com.wmqc.miroot.rear.ProjectionNotificationStopReceiver} 触发，
     * 不拉起 Activity，仅在已位于背屏的实例上执行清理，避免主界面闪一下。
     */
    public static void finishProjectionFromNotificationTap(Context appContext) {
        RearScreenLyricsActivity a = getCurrentInstance();
        if (a == null) {
            bestEffortCleanupMusicProjectionFromNotification(appContext);
            return;
        }
        Runnable r = () -> finishProjectionFromNotificationTapOnUiThread(a);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    private static void finishProjectionFromNotificationTapOnUiThread(RearScreenLyricsActivity a) {
        if (a.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && a.isDestroyed()) return;
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (a.getDisplay() != null) {
                    displayId = a.getDisplay().getDisplayId();
                }
            } catch (Exception ignored) {
            }
        }
        if (displayId != 1) {
            LogHelper.d(TAG, "忽略通知结束投屏：当前 displayId=" + displayId);
            return;
        }
        LogHelper.d(TAG, "🛑 通知广播：在背屏结束音乐投屏");
        a.finishProjectionFromUser("notification-static-stop");
    }

    private static void bestEffortCleanupMusicProjectionFromNotification(Context appContext) {
        try {
            ProjectionOngoingNotifications.cancelAll(appContext);
            ProjectionOnlyNotificationHelper.cancelMusic(appContext);
            RearScreenWakeManager.getInstance().stopWakeService(appContext, RearScreenLyricsActivity.class);
            if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                appContext.stopService(new Intent(appContext, RearScreenWakeService.class));
            }
            MainActivity.sendMusicProjectionStateBroadcast(appContext, false);
            LogHelper.d(TAG, "无 Activity 实例：已从通知路径 best-effort 清理音乐投屏状态");
        } catch (Exception e) {
            LogHelper.e(TAG, "bestEffortCleanupMusicProjectionFromNotification failed", e);
        }
    }

    private int getDisplayIdSafe() {
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (getDisplay() != null) {
                    displayId = getDisplay().getDisplayId();
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "获取displayId失败: " + e.getMessage());
            }
        }
        return displayId;
    }

    private void cancelMainScreenPlaceholderTimeout() {
        if (mainScreenPlaceholderTimeoutRunnable != null) {
            mainScreenPolicyHandler.removeCallbacks(mainScreenPlaceholderTimeoutRunnable);
            mainScreenPlaceholderTimeoutRunnable = null;
        }
    }

    private void cancelRearStopExitGrace() {
        if (rearStopExitGraceRunnable != null) {
            mainScreenPolicyHandler.removeCallbacks(rearStopExitGraceRunnable);
            rearStopExitGraceRunnable = null;
        }
        rearStopExitGraceDeadlineElapsedMs = 0L;
        rearStopExitGraceRemainingMs = 0L;
        rearStopExitGracePausedForScreenOff = false;
    }

    /**
     * 背屏双击息屏：暂停 onPause/onStop 已安排的退出宽限，避免灭屏 800ms 内误销毁投屏。
     */
    private void pauseRearStopExitGraceForScreenOff() {
        if (rearStopExitGraceRunnable == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (rearStopExitGraceDeadlineElapsedMs > now) {
            rearStopExitGraceRemainingMs = rearStopExitGraceDeadlineElapsedMs - now;
        }
        mainScreenPolicyHandler.removeCallbacks(rearStopExitGraceRunnable);
        rearStopExitGraceRunnable = null;
        rearStopExitGraceDeadlineElapsedMs = 0L;
        rearStopExitGracePausedForScreenOff = true;
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "息屏：暂停投屏退出宽限，剩余 " + rearStopExitGraceRemainingMs + "ms（亮屏后取消）");
        }
    }

    /** 亮屏或回到 RESUMED：取消因息屏暂停的宽限；充电覆盖结束则按剩余时间续跑宽限。 */
    private void handleProjectionForegroundAfterStopGrace() {
        if (rearStopExitGracePausedForScreenOff) {
            rearStopExitGracePausedForScreenOff = false;
            cancelRearStopExitGrace();
            if (BuildConfig.DEBUG) {
                LogHelper.d(TAG, "投屏已恢复可见，取消息屏期间暂停的退出宽限");
            }
            return;
        }
        if (rearStopExitGraceRemainingMs > 0L && !isChargingOverlayActive()) {
            resumeRearStopExitGraceAfterChargingIfNeeded();
        } else {
            cancelRearStopExitGrace();
        }
    }

    /** 息屏后确保「投屏常亮」前台唤醒循环在跑（与功能页开关一致）。 */
    private void ensureProjectionWakeServiceAfterScreenOff() {
        if (!isProjectionKeepScreenOnEnabled()) {
            return;
        }
        if (getDisplayIdSafe() != 1 || isFinishing()) {
            return;
        }
        try {
            RearScreenWakeManager.getInstance().startWakeService(getApplicationContext(), RearScreenLyricsActivity.class);
            RearScreenWakeService.requestNotificationRefresh(this);
        } catch (Exception e) {
            LogHelper.w(TAG, "息屏后确保 RearScreenWakeService 失败", e);
        }
    }

    private boolean isChargingOverlayActive() {
        try {
            return RearChargingAnimationCoordinator.isChargingProtectionActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 供充电覆盖层结束前调用：强制把背屏歌词界面恢复为可见并刷新一帧，避免出现黑屏空窗。
     */
    public static void forceShowProjectionUiAfterChargingOverlay() {
        RearScreenLyricsActivity inst = currentInstance;
        if (inst == null) {
            return;
        }
        Runnable r = () -> inst.forceShowProjectionUiAfterChargingOverlayOnUiThread();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            inst.runOnUiThread(r);
        }
    }

    private void forceShowProjectionUiAfterChargingOverlayOnUiThread() {
        Log.d("CHARGING_FIX", "STEP 3: 恢复视图 VISIBLE + alpha=1 + 刷新布局");
        if (isFinishing()) {
            return;
        }
        View decor = (getWindow() != null) ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.setVisibility(View.VISIBLE);
            decor.setAlpha(1f);
            decor.requestLayout();
            decor.invalidate();
            decor.postInvalidateOnAnimation();
        }
        if (lyricsView != null) {
            lyricsView.setVisibility(View.VISIBLE);
            lyricsView.requestLayout();
            lyricsView.invalidate();
        }
        if (abyssalMirrorLyricsViewGroup != null) {
            abyssalMirrorLyricsViewGroup.setVisibility(View.VISIBLE);
            abyssalMirrorLyricsViewGroup.requestLayout();
            abyssalMirrorLyricsViewGroup.invalidate();
        }
        resumeLyricsAnimatorAfterForeground();
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "▶️ 强制恢复背屏歌词 UI 为可见并刷新（充电覆盖层结束前）");
        }
    }

    /**
     * 从 onPause / 充电覆盖层恢复后：若音乐仍在播放则恢复歌词位置推进，并立即对齐当前进度。
     */
    private void resumeLyricsAnimatorAfterForeground() {
        if (!hasLyricsView()) {
            return;
        }
        uiAnimationsCancelled = false;
        if (mediaController == null) {
            setupMediaController();
        }
        if (mediaController == null) {
            return;
        }
        android.media.session.PlaybackState state = mediaController.getPlaybackState();
        if (state == null) {
            return;
        }
        boolean isPlaying = state.getState() == android.media.session.PlaybackState.STATE_PLAYING;
        if (lyricsView != null) {
            lyricsView.setPlaybackActive(isPlaying);
        }
        if (lyricsAnimator == null) {
            return;
        }
        if (isPlaying) {
            long position = state.getPosition();
            long updateTime = state.getLastPositionUpdateTime();
            lyricsAnimator.calibratePosition(position, updateTime);
            lyricsAnimator.resume();
            if (lyricsView != null) {
                lyricsView.snapPlaybackPositionToTarget();
            }
        }
    }

    private void resumeRearStopExitGraceAfterChargingIfNeeded() {
        if (rearStopExitGraceRemainingMs <= 0L) {
            return;
        }
        if (rearStopExitGraceRunnable != null) {
            return;
        }
        if (isFinishing() || isChangingConfigurations() || isMainScreenLandscapeMode) {
            return;
        }
        if (!hasLyricsView()) {
            return;
        }
        if (getDisplayIdSafe() != 1) {
            return;
        }
        long delayMs = rearStopExitGraceRemainingMs;
        rearStopExitGraceRemainingMs = 0L;
        rearStopExitGraceRunnable = () -> {
            rearStopExitGraceRunnable = null;
            rearStopExitGraceDeadlineElapsedMs = 0L;
            if (isFinishing()) {
                return;
            }
            if (getLifecycle().getCurrentState() == Lifecycle.State.RESUMED) {
                return;
            }
            LogHelper.w(TAG, "充电覆盖恢复后：投屏宽限到期仍未回前台，销毁 Activity");
            finishProjectionFromUser("rear-stop-exit-grace-resumed");
        };
        rearStopExitGraceDeadlineElapsedMs = SystemClock.elapsedRealtime() + delayMs;
        mainScreenPolicyHandler.postDelayed(rearStopExitGraceRunnable, delayMs);
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "▶️ 充电覆盖结束：继续投屏退出宽限计时，剩余 " + delayMs + "ms");
        }
    }

    private void scheduleRearStopExitGraceIfNeeded(String reason) {
        if (rearStopExitGracePausedForScreenOff) {
            if (BuildConfig.DEBUG) {
                LogHelper.d(TAG, reason + "：息屏暂停宽限中，跳过重新安排退出宽限");
            }
            return;
        }
        cancelRearStopExitGrace();
        if (isFinishing() || isChangingConfigurations() || isMainScreenLandscapeMode) {
            return;
        }
        // 充电动画会短暂顶替歌词投屏为前台；对齐 3.4：此时不应触发 800ms 宽限自毁，
        // 否则歌词清理流程会恢复官方手势/Launcher，进而把充电动画也冲掉（表现为“两边都立刻销毁”）。
        try {
            if (isChargingOverlayActive()) {
                LogHelper.d(TAG, reason + "：检测到充电动画播放中，跳过背屏 onStop 宽限自毁");
                return;
            }
        } catch (Throwable ignored) {
        }
        if (!hasLyricsView()) {
            return;
        }
        if (getDisplayIdSafe() != 1) {
            return;
        }
        rearStopExitGraceRunnable = () -> {
            rearStopExitGraceRunnable = null;
            rearStopExitGraceDeadlineElapsedMs = 0L;
            rearStopExitGraceRemainingMs = 0L;
            if (isFinishing()) {
                return;
            }
            // 用 RESUMED 判断：部分机型回到背屏桌面只走 onPause、不走 onStop，仍为 STARTED 但已不是 RESUMED。
            if (getLifecycle().getCurrentState() == Lifecycle.State.RESUMED) {
                return;
            }
            LogHelper.w(TAG, reason + "：背屏投屏已不可见且宽限(" + REAR_STOP_EXIT_GRACE_MS + "ms)内未恢复前台，销毁 Activity");
            finishProjectionFromUser("rear-stop-exit-grace");
        };
        rearStopExitGraceDeadlineElapsedMs = SystemClock.elapsedRealtime() + REAR_STOP_EXIT_GRACE_MS;
        mainScreenPolicyHandler.postDelayed(rearStopExitGraceRunnable, REAR_STOP_EXIT_GRACE_MS);
    }

    private void scheduleMainScreenPlaceholderTimeout() {
        cancelMainScreenPlaceholderTimeout();
        if (isMainScreenLandscapeMode) {
            return;
        }
        mainScreenPlaceholderTimeoutRunnable = () -> {
            mainScreenPlaceholderTimeoutRunnable = null;
            if (isFinishing()) {
                return;
            }
            int d = getDisplayIdSafe();
            if (d == 1) {
                return;
            }
            if (isMainScreenLandscapeMode && d == 0) {
                return;
            }
            LogHelper.w(TAG, "主屏占位超时(" + MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS + "ms)仍未到背屏，销毁 Activity");
            finishIllegalProjectionSurface();
        };
        mainScreenPolicyHandler.postDelayed(mainScreenPlaceholderTimeoutRunnable, MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS);
    }

    /**
     * 歌词 UI 仅允许：背屏(display 1)，或磁贴指定的主屏横屏(display 0)。
     *
     * @return true 表示已发起销毁，调用方应中止后续 UI 逻辑
     */
    private boolean finishIfIllegalProjectionSurface(String reason) {
        if (isFinishing()) {
            return true;
        }
        int d = getDisplayIdSafe();
        boolean chargingRelated = isChargingOverlayActive();
        if (d == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            return false;
        }
        if (chargingRelated && hasLyricsView()) {
            LogHelper.d(TAG, reason + "：充电覆盖相关窗口期(displayId=" + d + ")，跳过非法屏幕销毁");
            return false;
        }
        if (hasLyricsView()) {
            LogHelper.w(TAG, reason + "：背屏歌词只能在背屏展示，当前 displayId=" + d + "，销毁");
            finishIllegalProjectionSurface();
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && d != 0 && d != 1) {
            LogHelper.w(TAG, reason + "：非常规显示屏(displayId=" + d + ")，销毁");
            finishIllegalProjectionSurface();
            return true;
        }
        return false;
    }

    private void finishIllegalProjectionSurface() {
        finishProjectionFromUser("illegal-surface");
    }

    /**
     * 主屏仅透明占位；背屏才允许创建/展示歌词 UI。主屏若已有 UI（迁屏失败或结束回落）立即销毁。
     *
     * @return true 表示已处理并应中止后续 UI 逻辑
     */
    private boolean enforceProjectionDisplayPolicy(String reason) {
        if (isFinishing() || projectionExitFlowStarted || finishRequestedByMiRoot) {
            return true;
        }
        int displayId = getDisplayIdSafe();
        int mainMode = RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                displayId, false, hasLyricsView());
        if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
            RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode);
            LogHelper.w(TAG, reason + "：主屏禁止展示背屏歌词 UI，结束投屏");
            finishProjectionFromUser(reason + "-main-display-with-ui");
            return true;
        }
        if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER) {
            RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode);
            if (!hasLyricsView()) {
                schedulePollRearLyricsUiInitAfterMove();
            }
            return true;
        }
        if (RearMirootProjectionLifecycle.shouldFinishOnMainDisplayDuringProjection(
                displayId, initialDisplayId, false, hasLyricsView())) {
            RearMirootProjectionLifecycle.hideWindowBeforeProjectionFinish(this);
            LogHelper.w(TAG, reason + "：背屏 UI 落回主屏，结束投屏");
            finishProjectionFromUser(reason + "-rear-ui-on-main");
            return true;
        }
        return false;
    }

    /** 旧版磁贴曾用本 Activity 主屏横屏；现统一跳转 {@link MainScreenMusicActivity}。 */
    private void redirectLegacyMainScreenLaunchAndFinish() {
        LogHelper.w(TAG, "旧版主屏横屏入口已废弃，跳转 MainScreenMusicActivity");
        try {
            Intent i = new Intent(this, MainScreenMusicActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            LogHelper.e(TAG, "跳转 MainScreenMusicActivity 失败", e);
        }
        finishProjectionTask();
    }

    /** 主屏占位迁背屏后轮询 displayId，落背屏即建 UI（与车控一致）。 */
    private void schedulePollRearLyricsUiInitAfterMove() {
        cancelRearLyricsUiInitPoll();
        if (lyricsView != null) {
            return;
        }
        rearUiInitPollAttempts = 0;
        rearUiInitPollRunnable = this::pollRearLyricsUiInitStep;
        uiHandler.post(rearUiInitPollRunnable);
    }

    private void cancelRearLyricsUiInitPoll() {
        if (rearUiInitPollRunnable != null) {
            uiHandler.removeCallbacks(rearUiInitPollRunnable);
        }
        rearUiInitPollRunnable = null;
        rearUiInitPollAttempts = 0;
    }

    private void pollRearLyricsUiInitStep() {
        rearUiInitPollRunnable = null;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (lyricsView != null) {
            return;
        }
        if (getDisplayIdSafe() == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
            initLyricsUiOnRearIfNeeded("poll");
            return;
        }
        rearUiInitPollAttempts++;
        if (rearUiInitPollAttempts < RearMirootProjectionLifecycle.REAR_UI_INIT_POLL_MAX_ATTEMPTS) {
            rearUiInitPollRunnable = this::pollRearLyricsUiInitStep;
            uiHandler.postDelayed(
                    rearUiInitPollRunnable,
                    RearMirootProjectionLifecycle.REAR_UI_INIT_POLL_INTERVAL_MS);
        } else {
            LogHelper.w(TAG, "迁屏轮询超时仍未到背屏 (attempts=" + rearUiInitPollAttempts + ")");
        }
    }

    /**
     * 背屏创建歌词 UI（onCreate 直启 / 迁屏轮询 / onResume / onWindowFocusChanged 共用）。
     *
     * @return true 表示本次完成了初始化
     */
    private boolean initLyricsUiOnRearIfNeeded(String reason) {
        if (lyricsView != null || isFinishing() || isMainScreenLandscapeMode) {
            return false;
        }
        if (getDisplayIdSafe() != RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            return false;
        }
        cancelMainScreenPlaceholderTimeout();
        cancelRearLyricsUiInitPoll();
        LogHelper.d(TAG, "🎯 背屏初始化歌词 UI (" + reason + ")");
        RearMirootProjectionLifecycle.restoreProjectionWindowVisible(this);
        currentInstance = this;
        if (initialDisplayId == -1) {
            initialDisplayId = RearMirootProjectionLifecycle.REAR_DISPLAY_ID;
        }
        RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
        setupWindow();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        getWindow().setAttributes(params);
        loadSettings();
        createUI();
        initLyricsAnimator();
        setupMediaController();
        updateMediaInfo();
        updatePlaybackState();
        registerActiveSessionsChangedListener();
        mediaSessionInitializedThisCycle = true;
        if (getDisplayIdSafe() == RearMirootProjectionLifecycle.REAR_DISPLAY_ID
                && hasLyricsView()
                && !isFinishing()) {
            startMusicProjectionWakeService();
            notifyMainActivityProjectionStarted();
            LogHelper.d(TAG, "✅ 投屏成功 (" + reason + ")");
        } else {
            stopMusicProjectionWakeService();
        }
        if (taskService == null) {
            bindTaskService();
        }
        if (screenReceiver == null) {
            registerScreenReceiver();
        }
        return true;
    }

    /**
     * 对齐充电动画：不压黑、不在 finish 前 restore；资源清理在 onDestroy。
     */
    private void finishProjectionFromUser(String reason) {
        if (projectionExitFlowStarted || isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
            return;
        }
        projectionExitFlowStarted = true;
        finishRequestedByMiRoot = true;
        LogHelper.d(TAG, "🚪 音乐投屏结束: " + reason);
        try {
            ProjectionOngoingNotifications.cancelAll(getApplicationContext());
            ProjectionOnlyNotificationHelper.cancelMusic(this);
        } catch (Exception ignored) {
        }
        if (getDisplayIdSafe() == 0) {
            RearMirootProjectionLifecycle.hideWindowBeforeProjectionFinish(this);
        }
        finish();
    }

    /** 当前 Activity 所在 Display 的 dp → px（背屏与主屏密度可能不同，勿用固定 px） */
    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            getResources().getDisplayMetrics());
    }

    private void styleMediaTransportButton(Button button, android.graphics.drawable.GradientDrawable bg) {
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(10));
        bg.setColor(0xFF4A4A4A);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        int padH = dp(4);
        int padV = dp(6);
        button.setPadding(padH, padV, padH, padV);
        button.setMinHeight(dp(40));
        button.setMinWidth(dp(40));
        button.setBackground(bg);
        button.setElevation(dp(2));
    }

    private void executeMediaSkip(boolean next, String source) {
        if (!checkNotificationListenerPermission()) {
            MainDisplayUi.showToast(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG);
            openNotificationListenerSettings();
            return;
        }
        if (mediaController == null) {
            setupMediaController();
            if (mediaController == null) {
                MainDisplayUi.showToast(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT);
                return;
            }
        }
        android.media.session.MediaController.TransportControls controls = mediaController.getTransportControls();
        if (controls == null) {
            return;
        }
        try {
            if (next) {
                controls.skipToNext();
            } else {
                controls.skipToPrevious();
            }
        } catch (Exception e) {
            LogHelper.e(TAG, source + "失败", e);
        }
    }

    private void ensureDisplayInfoCacheForCutout() {
        if (DisplayInfoCache.getInstance().isInitialized()) return;
        if (taskService == null) return;
        try {
            DisplayInfoCache.getInstance().initialize(taskService);
        } catch (Exception e) {
            LogHelper.w(TAG, "DisplayInfoCache 初始化失败（媒体条避让）: " + e.getMessage());
        }
    }

    /**
     * 背屏左侧需让出的距离：优先 dumpsys 解析的 {@link RearDisplayHelper.RearDisplayInfo#cutout}，
     * 其次 {@link android.view.Display#getCutout()} / {@link android.view.WindowInsets#getDisplayCutout()}。
     */
    private int computeMediaBarLeftInsetPx() {
        if (isMainScreenLandscapeMode) return 0;
        int left = 0;
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info != null && info.cutout != null && info.cutout.left > 0) {
                left = info.cutout.left;
            }
        } catch (Exception ignored) { }
        if (left <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (getDisplay() != null) {
                    android.view.DisplayCutout c = getDisplay().getCutout();
                    if (c != null) {
                        left = Math.max(left, c.getSafeInsetLeft());
                    }
                }
            } catch (Exception ignored) { }
        }
        if (left <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.view.Window w = getWindow();
                if (w != null) {
                    android.view.View decor = w.getDecorView();
                    if (decor != null) {
                        android.view.WindowInsets wi = decor.getRootWindowInsets();
                        if (wi != null) {
                            android.view.DisplayCutout c = wi.getDisplayCutout();
                            if (c != null) {
                                left = Math.max(left, c.getSafeInsetLeft());
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        return left;
    }

    /** 右侧安全区：优先 DisplayInfoCache，其次 DisplayCutout / WindowInsets。 */
    private int computeRightSafeInsetPx() {
        if (isMainScreenLandscapeMode) return 0;
        int right = 0;
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info != null && info.cutout != null && info.cutout.right > 0) {
                right = info.cutout.right;
            }
        } catch (Exception ignored) { }
        if (right <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (getDisplay() != null) {
                    android.view.DisplayCutout c = getDisplay().getCutout();
                    if (c != null) {
                        right = Math.max(right, c.getSafeInsetRight());
                    }
                }
            } catch (Exception ignored) { }
        }
        if (right <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.view.Window w = getWindow();
                if (w != null) {
                    android.view.View decor = w.getDecorView();
                    if (decor != null) {
                        android.view.WindowInsets wi = decor.getRootWindowInsets();
                        if (wi != null) {
                            android.view.DisplayCutout c = wi.getDisplayCutout();
                            if (c != null) {
                                right = Math.max(right, c.getSafeInsetRight());
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        return right;
    }

    /** 根据文本是否超出可见宽度切换：不超长居中，超长滚动。 */
    private void applySongTitleOverflowMode(int visibleWidth) {
        if (songTitleText == null) return;
        if (visibleWidth <= 0) return;
        try {
            CharSequence cs = songTitleText.getText();
            String text = cs == null ? "" : cs.toString();
            float textWidth = songTitleText.getPaint().measureText(text);
            boolean overflow = textWidth > (visibleWidth - dp(4));

            songTitleText.setSingleLine(true);
            if (overflow) {
                songTitleText.setGravity(android.view.Gravity.START | android.view.Gravity.BOTTOM);
                songTitleText.setHorizontallyScrolling(true);
                songTitleText.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
                songTitleText.setMarqueeRepeatLimit(-1);
                restartTextMarquee(songTitleText);
            } else {
                songTitleText.setGravity(android.view.Gravity.CENTER | android.view.Gravity.BOTTOM);
                songTitleText.setHorizontallyScrolling(false);
                songTitleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                songTitleText.setSelected(false);
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "applySongTitleOverflowMode 失败", e);
        }
    }

    /**
     * Android 系统 marquee 偶发卡住时，单纯 setSelected(true) 不一定会重新启动。
     * 这里用“先 false 再 post true”的方式强制重触发，同时要求处于单行+MARQUEE 模式。
     */
    private void restartTextMarquee(TextView tv) {
        if (tv == null) return;
        try {
            if (tv.getEllipsize() != android.text.TextUtils.TruncateAt.MARQUEE) {
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            }
            tv.setSingleLine(true);
            tv.setHorizontallyScrolling(true);
            tv.setMarqueeRepeatLimit(-1);
            // 关键：toggle selected，且把 true 延迟到布局稳定后
            tv.setSelected(false);
            tv.removeCallbacks(restartSongTitleMarqueeRunnable);
            restartSongTitleMarqueeTarget = tv;
            tv.postDelayed(restartSongTitleMarqueeRunnable, 80);
        } catch (Exception ignored) {
        }
    }

    // 复用 runnable，避免频繁分配
    private TextView restartSongTitleMarqueeTarget = null;
    private final Runnable restartSongTitleMarqueeRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (restartSongTitleMarqueeTarget != null) {
                    restartSongTitleMarqueeTarget.setSelected(true);
                }
            } catch (Exception ignored) {
            }
        }
    };

    /** 歌名右侧安全内边距：15px + 跑马灯线宽（避免被跑马灯遮挡）。 */
    private int songTitleRightPaddingPx() {
        int base = dp(15);
        if (!marqueeLightEnabled) {
            return base;
        }
        return base + (int) Math.ceil(Math.max(0f, marqueeLightSize));
    }

    private void applySongTitleRightPadding() {
        if (songTitleText == null) return;
        try {
            int l = songTitleText.getPaddingLeft();
            int t = songTitleText.getPaddingTop();
            int b = songTitleText.getPaddingBottom();
            int r = songTitleRightPaddingPx();
            if (songTitleText.getPaddingRight() != r) {
                songTitleText.setPadding(l, t, r, b);
            }
        } catch (Exception ignored) {
        }
    }

    /** 歌名/来源显示区：背屏左侧固定留白 {@link #REAR_LYRICS_LEFT_CLEAR_PX}；主屏对称边距。 */
    private void applySongTitleSafeInsets() {
        if (songTitleText == null && hookSourceStatusText == null) return;
        if (isFinishing()) return;
        try {
            final int topMargin = (int) (marqueeLightSize + dp(2));
            final int pmWidth = getResources().getDisplayMetrics().widthPixels;
            final int leftMargin;
            final int rightMargin;
            if (isMainScreenLandscapeMode) {
                leftMargin = dp(20);
                rightMargin = dp(20);
            } else {
                leftMargin = REAR_LYRICS_LEFT_CLEAR_PX;
                rightMargin = 0;
            }
            final int visibleWidth = Math.max(0, pmWidth - leftMargin - rightMargin);

            if (songTitleText != null) {
                LinearLayout.LayoutParams lp = songTitleLayoutParams;
                if (lp == null) {
                    ViewGroup.LayoutParams raw = songTitleText.getLayoutParams();
                    if (raw instanceof LinearLayout.LayoutParams) {
                        lp = (LinearLayout.LayoutParams) raw;
                    } else {
                        lp = new LinearLayout.LayoutParams(
                            visibleWidth,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                    }
                }
                lp.width = visibleWidth;
                lp.gravity = android.view.Gravity.START;
                lp.setMargins(leftMargin, topMargin, rightMargin, 0);
                songTitleLayoutParams = lp;
                songTitleText.setLayoutParams(lp);
                applySongTitleRightPadding();
                if (visibleWidth > dp(120)) {
                    songTitleText.setMaxWidth(visibleWidth);
                }
                applySongTitleOverflowMode(visibleWidth);
            }
            if (gestureTitleLayer != null) {
                android.widget.FrameLayout.LayoutParams touchLp = gestureTitleLayerLayoutParams;
                final int gestureExtraTop = dp(GESTURE_TITLE_LAYER_EXTRA_TOP_DP);
                int titleTouchBaseHeight = dp(GESTURE_TITLE_LAYER_BASE_HEIGHT_DP);
                if (songTitleText != null) {
                    int measured = songTitleText.getMeasuredHeight();
                    int current = songTitleText.getHeight();
                    int raw = Math.max(measured, current);
                    if (raw > 0) {
                        titleTouchBaseHeight = raw;
                    }
                }
                final int gestureExtraBottom = dp(GESTURE_TITLE_LAYER_EXTRA_BOTTOM_DP);
                final int gestureHeight = titleTouchBaseHeight + gestureExtraTop + gestureExtraBottom;
                final int gestureTopMargin = Math.max(0, topMargin - gestureExtraTop);
                if (touchLp == null) {
                    touchLp = new android.widget.FrameLayout.LayoutParams(
                        visibleWidth,
                        gestureHeight
                    );
                }
                touchLp.width = visibleWidth;
                touchLp.height = gestureHeight;
                touchLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                touchLp.setMargins(leftMargin, gestureTopMargin, rightMargin, 0);
                gestureTitleLayerLayoutParams = touchLp;
                gestureTitleLayer.setLayoutParams(touchLp);
            }

            if (hookSourceStatusText != null) {
                LinearLayout.LayoutParams hookLp = hookSourceStatusLayoutParams;
                if (hookLp == null) {
                    ViewGroup.LayoutParams raw = hookSourceStatusText.getLayoutParams();
                    if (raw instanceof LinearLayout.LayoutParams) {
                        hookLp = (LinearLayout.LayoutParams) raw;
                    } else {
                        hookLp = new LinearLayout.LayoutParams(
                            visibleWidth,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                    }
                }
                hookLp.width = visibleWidth;
                hookLp.gravity = android.view.Gravity.START;
                hookLp.setMargins(leftMargin, 0, rightMargin, 0);
                hookSourceStatusLayoutParams = hookLp;
                hookSourceStatusText.setLayoutParams(hookLp);
                hookSourceStatusText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                if (visibleWidth > dp(120)) {
                    hookSourceStatusText.setMaxWidth(visibleWidth);
                }
            }

            LogHelper.d(TAG, "✅ 歌名安全区已更新: left=" + leftMargin + "px, right=" + rightMargin
                + "px, pmWidth=" + pmWidth
                + "px, visibleWidth=" + Math.max(0, visibleWidth) + "px");
        } catch (Exception e) {
            LogHelper.w(TAG, "applySongTitleSafeInsets 失败", e);
        }
    }

    /** 三键控制条：背屏左侧与歌名一致的固定留白；主屏对称边距。 */
    private void applyMediaButtonBarInsets() {
        if (buttonLayout == null || mediaButtonBarLayoutParams == null) return;
        if (isFinishing()) return;
        try {
            final int innerPadH = dp(18);
            final int innerPadV = dp(10);
            final int marginBottom = Math.max(0, dp(28) - 28);
            final int pmWidth = getResources().getDisplayMetrics().widthPixels;
            final int leftMargin;
            final int rightMargin;
            if (isMainScreenLandscapeMode) {
                leftMargin = dp(20);
                rightMargin = dp(20);
            } else {
                leftMargin = REAR_LYRICS_LEFT_CLEAR_PX;
                rightMargin = 0;
            }
            final int visibleWidth = Math.max(0, pmWidth - leftMargin - rightMargin);

            buttonLayout.setPadding(innerPadH, innerPadV, innerPadH, innerPadV);
            mediaButtonBarLayoutParams.setMargins(leftMargin, 0, rightMargin, marginBottom);
            buttonLayout.setLayoutParams(mediaButtonBarLayoutParams);
            LogHelper.d(TAG, "媒体控制条已更新: left=" + leftMargin + "px, right=" + rightMargin
                + "px, pmWidth=" + pmWidth + "px, visibleWidth=" + visibleWidth + "px, bottom=" + marginBottom + "px");
            applySongTitleSafeInsets();
        } catch (Exception e) {
            LogHelper.w(TAG, "applyMediaButtonBarInsets 失败", e);
        }
    }

    /**
     * 固定圆角仅用于背屏（display 1）：广播启动或锁屏竞态时 insets 可能仍是主屏值，用 {@link ProjectionHelper#REAR_PROJECTION_FIXED_CORNER_RADIUS_PX} 稳定四角。
     * 主屏打开（含横屏歌词、display 0）始终返回 false，跑马灯/深渊镜通过系统参数检测圆角。
     */
    private boolean shouldUseFixedRearProjectionCornerRadius() {
        if (isMainScreenLandscapeMode) return false;
        if (getDisplayIdSafe() != 1) return false;

        if (isBroadcastStarted()) return true;

        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }
    
    private TextView songTitleText;
    private TextView artistText;
    private TextView hookSourceStatusText;
    /** 标题区布局参数缓存：用于按安全区与跑马灯宽度实时刷新边距。 */
    private LinearLayout.LayoutParams songTitleLayoutParams;
    /** 标题手势层：放在最上层，避免被边框跑马灯覆盖触摸。 */
    private android.widget.FrameLayout gestureTitleLayer;
    private android.widget.FrameLayout.LayoutParams gestureTitleLayerLayoutParams;
    private static final int GESTURE_TITLE_LAYER_BASE_HEIGHT_DP = 64;
    private static final int GESTURE_TITLE_LAYER_EXTRA_TOP_DP = 32;
    private static final int GESTURE_TITLE_LAYER_EXTRA_BOTTOM_DP = 10;
    /** 调试来源文本布局参数缓存：用于按安全区实时刷新边距。 */
    private LinearLayout.LayoutParams hookSourceStatusLayoutParams;
    private ModernLyricsView lyricsView;
    private ImageView albumArtBackgroundView;

    private android.widget.FrameLayout mainFrameLayout;
    private LinearLayout contentRootLayout;

    private AbyssalMirrorLyricsView abyssalMirrorLyricsView; // 深渊镜歌词视图（旧版，3D旋转效果）
    private AbyssalMirrorLyricsViewGroup abyssalMirrorLyricsViewGroup; // 深渊镜歌词视图（新版，多层边框效果）
    private Button prevButton;
    private Button playPauseButton;
    private Button nextButton;
    
    private MediaController mediaController;
    private MediaController.Callback mediaControllerCallback;
    /** 酷我车载：MediaBrowser 直连 KwMediaSessionService */
    private KuwoCarMediaSessionHelper kuwoCarMediaSessionHelper;
    /**
     * 酷我车载：LYRIC_FULL / LYRIC_PROGRESS 广播桥（参考酷我移植参考文档.md §9）。
     * 优先级：当广播到达时立即覆盖现有歌词；离开时不影响其它来源（MediaSession / 网络 / SuperLyric）。
     */
    private KuwoBroadcastLyricBridge kuwoBroadcastLyricBridge;
    /** 当前是否使用酷我 MediaBrowser 会话（用于 extras 歌词与 loadLyrics 分支） */
    private boolean kuwoCarLyricsSessionActive;
    /** 上次成功绑定的播放器包名；用于检测跨应用切换并重置歌词状态 */
    private String lastBoundMediaPackage = "";

    /** 活跃媒体会话列表变化时重新绑定，解决切换播放器后仍附着旧 [MediaController] 导致元数据/歌词不更新 */
    private boolean activeSessionsListenerRegistered;
    private MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener;
    private final Runnable activeSessionsChangedDebouncedRunnable = () -> {
        if (isFinishing()) {
            return;
        }
        if (lyricsView == null && !isMainScreenLandscapeMode) {
            return;
        }
        LogHelper.d(TAG, "🔁 活跃媒体会话列表变化，重新绑定 MediaController");
        refreshMediaControllerBinding();
    };

    private static final long MEDIA_CONTROLLER_REFRESH_CHECK_THROTTLE_MS = 500L;
    private long lastMediaControllerRefreshCheckMs = 0L;

    // 「歌名跳動」防護：部分播放器在開啟車載藍牙歌詞時會把當前歌詞行塞進 METADATA_KEY_TITLE
    // 這裡用「曲目簽名」鎖定顯示用的 title/artist，避免同一首歌期間反覆刷新造成跳動
    private String stableTrackSignature = "";
    private String stableTitle = "";
    private String stableArtist = "";
    private long stableDurationMs = 0L;
    private long lastStableUpdateMs = 0L;
    private static final long STABLE_TITLE_MIN_UPDATE_INTERVAL_MS = 1500; // 同曲目短時間內 title 變更視為噪音
    private static final long NOTIFICATION_SCAN_THROTTLE_MS = 2000L;
    private String lastNotificationLookupPkg = "";
    private long lastNotificationLookupAtMs = 0L;
    private TitleArtist lastNotificationLookupResult = null;

    // 歌词系统
    private LyricsAnimator lyricsAnimator;
    private List<EnhancedLRCParser.EnhancedLyricLine> enhancedLyricLines = new ArrayList<>();
    // 歌词加载防抖：避免多线程/多回调导致“暂无歌词/有歌词”反复覆盖
    private int lyricsRequestSeq = 0;
    private String currentTrackKey = "";
    // loadLyrics 防抖：同曲目不因 metadata 頻繁變化而重載，避免覆蓋 API 歌詞、請求亂序導致「無歌詞」
    private String lastLoadLyricsTrackKey = "";
    private long lastLoadLyricsTime = 0L;
    private static final long LOAD_LYRICS_DEBOUNCE_MS = 1200;
    /** 同一曲目仅允许第三方 API 拉取一次（切歌后重置）。 */
    private String apiAttemptedTrackKey = "";
    /** 最近一次真正应用到歌词视图的曲目键。 */
    private String lastAppliedLyricsTrackKey = "";
    /** 最近一次应用到歌词视图的数据指纹，避免同内容重复 setLyricsToView。 */
    private String lastAppliedLyricsFingerprint = "";
    /** 深渊镜按行刷新：仅在行号变化时更新文本，避免高频重绘。 */
    private int lastAbyssalRenderedLineIndex = -1;
    /** SuperLyric 单句模式复用对象，减少高频创建导致的 sticky GC。 */
    private final EnhancedLRCParser.EnhancedLyricLine reusableSuperLyricLine =
        new EnhancedLRCParser.EnhancedLyricLine(0L, "");
    private final ArrayList<EnhancedLRCParser.WordTimestamp> reusableSuperLyricWords = new ArrayList<>();
    private final List<EnhancedLRCParser.EnhancedLyricLine> reusableSingleLineLyrics = new ArrayList<>();
    // 第三方 API 防重：同曲目请求进行中时不重复发起
    private String inflightApiTrackKey = "";
    /** SUPER_LYRIC_ONLY：SuperLyric 逐句回调的实时刷新（非完整 LRC）。 */
    private SuperLyricApi.RealtimeListener superLyricRealtimeListener;
    /** SuperLyric 单句平滑：同一句重复回调时沿用已锚定时间，避免逐字进度抖动/跳变。 */
    private String lastSuperLyricAppliedTextNormalized = "";
    private long lastSuperLyricAppliedLineTimeMs = -1L;
    /** 歌词渲染侧最近一次位置（由 LyricsAnimator 回调更新），用于 SuperLyric 单句时间锚定。 */
    private volatile long latestLyricsRenderPositionMs = 0L;
    /**
     * 播放器进度明显落后于缓存（多为切歌后回到新曲开头）时，锚定行以播放器为准，避免仍按上首尾声居中。
     */
    private static final long TRACK_CHANGE_CACHED_POSITION_GAP_MS = 4_000L;
    /** 切歌后短期内只信 MediaSession 进度，避免用上首残留缓存导致行号/滚动来回跳。 */
    private static final long TRACK_CHANGE_PLAYBACK_TRUST_MS = 3_500L;
    private long lyricsTrackSettledAtMs = 0L;
    /** SuperLyric 单句时间戳允许落后当前进度的最大阈值，超出则判定为跨曲/异常数据。 */
    private static final long SUPER_LYRIC_STALE_BACKWARD_TOLERANCE_MS = 3500L;
    /** 「仅切歌触发」的歌词加载门控：记录最近一次触发 loadLyrics 的曲目签名。 */
    private String lastLyricsLoadTrackSignature = "";
    /** 酷我 AUDIO_LYRIC 去重：同曲同 payload 不重复解析/设置。 */
    private String lastKuwoAudioLyricTrackKey = "";
    private String lastKuwoAudioLyricPayload = "";
    /** 酷我广播 LYRIC_FULL 已下发带逐字时间戳的歌词，阻止后续 AUDIO_LYRIC 无逐字覆盖。 */
    private boolean kuwoBroadcastWordTimestampsApplied = false;
    /** 酷我：AUDIO_LYRIC 等待超时后再走网络/智能兜底（避免与专用解析并行抢结果）。 */
    private Runnable pendingKuwoNativeFallbackRunnable;
    private boolean kuwoPostNativeFallbackScheduled = false;
    /** 仅 {@link #beginKuwoLyricsFallbackAfterNativeFailed} 允许在酷我专用阶段之后发起第三方拉词。 */
    private boolean kuwoAllowThirdPartyFallback = false;
    /** 仍无歌词时再显示「暂无」，避免切歌/异步未返回时闪一下暂无（第三方 API 已结束后的短防抖） */
    private static final long NO_LYRICS_UI_DELAY_MS = 2200L;
    /**
     * 汽水·智能切换：网络 API 优先等待时长；超时仍未返回完整歌词才启用 SuperLyric 单行兜底。
     */
    private static final long QISHUI_NETWORK_API_PRIORITY_DELAY_MS = 2800L;
    /**
     * 酷我车载等仅依赖会话 extras 推送歌词、不走 API 时：新歌 AUDIO_LYRIC 可能晚于元数据很久，
     * 若沿用 {@link #NO_LYRICS_UI_DELAY_MS} 会在歌词仍在路上时误显示「暂无歌词」。
     */
    private static final long NO_LYRICS_WAIT_SESSION_EXTRAS_MS = 30_000L;
    private Runnable pendingNoLyricsRunnable;
    private Runnable finishAfterStopNotificationRunnable;
    private Runnable illegalSurfaceFinishRunnable;
    private Runnable taskServiceRecheckRunnable;
    private Runnable taskServiceRebindRunnable;
    private Runnable screenOffWakeRetryRunnable;
    private Runnable legacySystemUiExitConfirmRunnable;
    private Runnable systemUiExitConfirmRunnable;
    private Runnable deferredCreateUiRunnable;
    private Runnable notifyStopRetryRunnable;
    private Runnable restoreLauncherRetryRunnable;
    private boolean isInForeground = false;
    private boolean uiAnimationsCancelled = false;
    private boolean projectionExitFlowStarted = false;
    private boolean projectionCleanupDone = false;
    /** 标记本轮 onCreate（或迁屏后首次 initLyricsUiOnRearIfNeeded）已完成 MediaSession 初始化，
     * 防止紧随的 onResume 重复调用 setupMediaController/updateMediaInfo 导致异步歌词请求序号失效。 */
    private boolean mediaSessionInitializedThisCycle = false;
    /** 对齐充电动画：MiRoot 主动结束，onDestroy 后再 restore 官方背屏。 */
    private boolean finishRequestedByMiRoot = false;
    private Runnable rearUiInitPollRunnable;
    private int rearUiInitPollAttempts;
    private boolean projectionExitUiLocked = false;
    private static final long EXIT_FADE_DURATION_MS = 200L;
    private static final long EXIT_HIDE_WAIT_ONE_FRAME_MS = 16L;
    // 限制背屏目标帧率，降低 DequeueBuffer timeout 风险
    private static final float REAR_RENDER_TARGET_FPS = 30f;
    private static final float REAR_RENDER_TARGET_FPS_SHUFFLE = 24f;
    
    // 播放位置同步
    private android.os.Handler positionSyncHandler;
    private Runnable positionSyncRunnable;
    
    // 播放状态缓存（用于防抖动，避免频繁触发相同状态）
    private Boolean lastPlayingState = null;
    private long lastStateChangeTime = 0;
    private static final long STATE_CHANGE_DEBOUNCE_MS = 200; // 状态变化防抖动时间
    private Boolean pendingPlaybackState = null;
    private Runnable pendingPlaybackStateRunnable;
    
    // 锁屏监听（解锁后恢复显示）
    private android.content.BroadcastReceiver screenReceiver;

    // 旧外部 Hook 歌词通道已停用

    // 歌词来源优先级（酷我专用解析 > 网络 API > SuperLyricApi 3.4 兜底）
    private enum LyricsSource {
        NONE,
        KUWO_CAR,
        NETWORK_API,
        SUPER_LYRIC_FALLBACK
    }

    private enum SingleLineLyricsOrigin {
        MEDIA_CONTROLLER,
        NOTIFICATION,
        HOOK
    }

    private LyricsSource currentLyricsSource = LyricsSource.NONE;
    private String currentLyricsSourcePkg = "";
    private boolean superLyricFallbackModeActive = false;
    /** 汽水·智能切换：网络 API 拉取中（优先）；超时前不展示 SuperLyric 单行。 */
    private boolean qishuiAwaitingNetworkLyrics = false;
    private Runnable pendingQishuiSuperLyricPreviewRunnable;
    /** 当前 SuperLyric 单行兜底是否含逐字时间轴（是否启用逐字渲染由 wordByWord 开关决定）。 */
    private boolean superLyricActiveLineHasWords = false;
    /** 智能切换：上次写入逐字融合的行索引，用于同句回调时跳过整表 setLyrics。 */
    private int lastWordFusionLineIndex = -1;
    /** 模块已推送逐字但多行结构未就绪时暂存，网络/整曲 LRC 到达后立即补融合。 */
    private SuperLyricApi.SuperLyricFallbackPayload cachedWordFusionPayload;
    private final ExecutorService superLyricWordFusionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SuperLyricWordFusion");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final AtomicInteger wordFusionGeneration = new AtomicInteger(0);
    /** 最近一次命中网络歌词的 API（kugou / qsgc 等），供 Debug「当前歌词来源」展示。 */
    private String lastNetworkLyricsProvider = "";
    private LyricsRuntimeSource lastNetworkLyricsRuntimeSource = LyricsRuntimeSource.NETWORK;
    /** Debug 来源条：最近一次写入的通道与附注（智能切换刷新逐字段时复用）。 */
    private LyricsRuntimeSource hookLastRuntimeSource = LyricsRuntimeSource.IDLE;
    private String hookLastRuntimeDetail = "";
    private final ExecutorService lyricsParseExecutor = Executors.newSingleThreadExecutor();

    /**
     * SuperLyric 实时歌词是否允许在「非前台」阶段继续运行。
     * <p>
     * 现实场景：锁屏/息屏时 Activity 可能触发 onPause/onStop，但投屏界面仍在背屏显示（FLAG_SHOW_WHEN_LOCKED），
     * 若按 isInForeground 直接停掉实时回调，会导致锁屏期间歌词不刷新，解锁后出现「跟不上/跳行」。
     */
    private boolean shouldRunSuperLyricRealtimeWhilePaused() {
        if (isFinishing() || isDestroyed()) return false;
        if (isNetworkOnlyLyricsSourceMode()) return false;
        if (isKuwoAwaitingNativeLyrics()) return false;
        // 没有 UI 时不跑（占位/迁屏阶段）
        if (!hasLyricsView() && !isMainScreenLandscapeMode) return false;
        int displayId = getDisplayIdSafe();
        // 背屏投屏：锁屏时仍应继续刷新
        if (displayId == 1) return true;
        // 主屏横屏投屏：允许继续刷新（某些机型锁屏会 pause 但画面仍可见）
        return isMainScreenLandscapeMode && displayId == 0;
    }

    /** 酷我车载：网络歌词 / 网络API·逐字融合 / 仅 SuperLyric 均优先 AUDIO_LYRIC 专用解析。 */
    private boolean shouldUseKuwoNativeLyricsPath(String packageName) {
        if (kuwoCarLyricsSessionActive) {
            return true;
        }
        return packageName != null
            && KuwoCarMediaSessionHelper.KUWO_PACKAGE.equals(packageName.trim());
    }

    /** 专用解析进行中：尚未拿到逐行歌词（含 MediaBrowser 连接前按包名识别的窗口）。 */
    private boolean isKuwoAwaitingNativeLyrics() {
        if (currentLyricsSource != LyricsSource.KUWO_CAR) {
            return false;
        }
        if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
            return false;
        }
        return shouldUseKuwoNativeLyricsPath(resolveCurrentLyricsPackageName());
    }

    private void markKuwoAudioLyricApplied() {
        cancelPendingKuwoNativeFallbackWait();
        kuwoPostNativeFallbackScheduled = false;
        kuwoAllowThirdPartyFallback = false;
        currentLyricsSource = LyricsSource.KUWO_CAR;
        updateHookSourceStatusText(LyricsRuntimeSource.KUWO_AUDIO_LYRIC);
    }

    /**
     * 检查当前歌词是否包含来自 LYRIC_FULL 广播的逐字时间戳。
     * 用于防止 AUDIO_LYRIC（仅行级）覆盖更优的广播逐字数据。
     */
    private boolean hasBroadcastWordTimestamps() {
        if (enhancedLyricLines == null || enhancedLyricLines.isEmpty()) {
            return false;
        }
        for (EnhancedLRCParser.EnhancedLyricLine line : enhancedLyricLines) {
            if (line != null && line.wordTimestamps != null && !line.wordTimestamps.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void cancelPendingKuwoNativeFallbackWait() {
        if (pendingKuwoNativeFallbackRunnable != null) {
            uiHandler.removeCallbacks(pendingKuwoNativeFallbackRunnable);
            pendingKuwoNativeFallbackRunnable = null;
        }
    }

    /**
     * 酷我车载：先等 {@link KuwoAudioLyricParser} 推送；超时仍无词再按来源模式兜底（与来源设置无关，均走此链路）。
     */
    private void scheduleKuwoNativeWaitThenFallback(int requestSeq, String trackKey, String title, String artist) {
        cancelPendingKuwoNativeFallbackWait();
        if (!shouldUseKuwoNativeLyricsPath(resolveCurrentLyricsPackageName()) || title == null || title.isEmpty()) {
            return;
        }
        kuwoPostNativeFallbackScheduled = true;
        final String finalTitle = title;
        final String finalArtist = artist != null ? artist : "";
        pendingKuwoNativeFallbackRunnable = () -> {
            pendingKuwoNativeFallbackRunnable = null;
            if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                return;
            }
            if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
                kuwoPostNativeFallbackScheduled = false;
                return;
            }
            beginKuwoLyricsFallbackAfterNativeFailed("等待AUDIO_LYRIC超时", requestSeq, trackKey, finalTitle, finalArtist);
        };
        uiHandler.postDelayed(pendingKuwoNativeFallbackRunnable, NO_LYRICS_WAIT_SESSION_EXTRAS_MS);
    }

    /**
     * 酷我专用解析失败后按来源模式兜底：网络歌词→仅网络 API；网络API·逐字融合→网络后 SuperLyric；仅 SuperLyric→SuperLyric。
     */
    /**
     * 酷我 extras 无有效逐行歌词时，在专用解析确认失败后走后续兜底。
     * <p>
     * 酷我车机版作为播放源时：仅使用酷我自身数据路径（AUDIO_LYRIC + LYRIC_FULL 广播），
     * 不调用第三方 API（酷狗/SuperLyric）兜底，确保逐字数据来源统一。
     */
    private void beginKuwoLyricsFallbackAfterNativeFailed(String reason,
                                                          int requestSeq,
                                                          String trackKey,
                                                          String title,
                                                          String artist) {
        cancelPendingKuwoNativeFallbackWait();
        if (currentLyricsSource != LyricsSource.KUWO_CAR) {
            return;
        }
        if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
            kuwoPostNativeFallbackScheduled = false;
            return;
        }
        if (title == null || title.isEmpty() || "未知歌曲".contentEquals(title) || isLikelyLyricLine(title)) {
            LogHelper.d(TAG, "酷我兜底跳过（无有效曲名）: " + reason);
            kuwoPostNativeFallbackScheduled = false;
            return;
        }
        if (trackKey != null && !trackKey.isEmpty() && trackKey.equals(inflightApiTrackKey)) {
            LogHelper.d(TAG, "酷我兜底防重：同曲目网络请求进行中");
            return;
        }
        kuwoPostNativeFallbackScheduled = false;

        // 酷我车机版：仅使用自身数据源，不调用第三方 API 兜底。
        // AUDIO_LYRIC（MediaSession extras）和 LYRIC_FULL（广播 words_json）均已尝试，
        // 若仍无歌词则等待切歌后重新获取。
        LogHelper.d(TAG, "酷我 AUDIO_LYRIC + 广播均未命中（" + reason
            + "），不调用第三方兜底，等待下次切歌重试");
        updateHookSourceStatusText(LyricsRuntimeSource.KUWO_AUDIO_LYRIC, "无歌词·等待切歌");
        scheduleNoLyricsIfStillEmpty(requestSeq, trackKey, null, null);
    }

    private void beginLyricsAcquisitionForTrack(String pkg, String trackKey, int requestSeq, String title, String artist) {
        final String pkgSafe = pkg != null ? pkg : "";
        final boolean sameSongWithLyrics = enhancedLyricLines != null && !enhancedLyricLines.isEmpty()
            && isSameSongTrackKey(trackKey, currentTrackKey, title, artist);
        final boolean apiAlreadyTriedForSong = sameSongWithLyrics
            && !TextUtils.isEmpty(trackKey)
            && (trackKey.equals(apiAttemptedTrackKey) || trackKey.equals(inflightApiTrackKey));

        if (apiAlreadyTriedForSong) {
            if (BuildConfig.DEBUG) {
                LogHelper.d(TAG, "🛡️ 同曲已有歌词且网络 API 已拉取/进行中，跳过重复 acquisition: " + trackKey);
            }
            if (isMixedLyricsSourceMode() || isSuperLyricOnlySourceMode()) {
                SuperLyricApi.ensureReceiverRegistered();
                startSuperLyricRealtimeTicker();
            }
            return;
        }

        currentLyricsSource = LyricsSource.NONE;
        currentLyricsSourcePkg = pkgSafe;
        superLyricFallbackModeActive = false;
        cancelPendingQishuiSuperLyricPreviewWait();
        qishuiAwaitingNetworkLyrics = false;
        superLyricActiveLineHasWords = false;
        lastWordFusionLineIndex = -1;
        cachedWordFusionPayload = null;
        wordFusionGeneration.incrementAndGet();
        lastNetworkLyricsProvider = "";
        lastNetworkLyricsRuntimeSource = LyricsRuntimeSource.NETWORK;
        updateHookSourceStatusText(LyricsRuntimeSource.ACQUIRING);

        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "🎛️ LyricsSource begin: pkg=" + currentLyricsSourcePkg
                + " trackKey=" + trackKey);
        }

        // 切歌立即清空旧歌词，避免跨曲串词（同曲仅补全时长/mediaId 时不清空）
        if (!sameSongWithLyrics) {
            lyricsTrackSettledAtMs = SystemClock.uptimeMillis();
            syncLyricsRenderPositionCache(0L);
            apiAttemptedTrackKey = "";
            try {
                enhancedLyricLines = new ArrayList<>();
                kuwoBroadcastWordTimestampsApplied = false;
                lastAbyssalRenderedLineIndex = -1;
                lastAppliedLyricsTrackKey = "";
                lastAppliedLyricsFingerprint = "";
                lastSuperLyricAppliedTextNormalized = "";
                lastSuperLyricAppliedLineTimeMs = -1L;
                // V3.17+: 先清空歌词视图再置 trackLoading，避免 onDraw 间隙残留旧歌词
                setLyricsToView(null);
                if (lyricsView != null) {
                    lyricsView.setTrackLoading(true);
                    lyricsView.clearTokenizationCache();
                }
                LyricsWordTokenizer.clearCaches();
            } catch (Exception ignored) {}
        } else if (lyricsView != null) {
            lyricsView.setTrackLoading(false);
        }

        if (shouldUseKuwoNativeLyricsPath(pkg)) {
            currentLyricsSource = LyricsSource.KUWO_CAR;
            currentLyricsSourcePkg = pkg != null ? pkg : KuwoCarMediaSessionHelper.KUWO_PACKAGE;
            updateHookSourceStatusText(LyricsRuntimeSource.KUWO_PENDING);
            stopSuperLyricRealtimeTicker();
            // 所有来源模式：仅 AUDIO_LYRIC；失败后再按设置兜底（网络 / SuperLyric）
            if (title != null && !title.isEmpty()) {
                scheduleKuwoNativeWaitThenFallback(requestSeq, trackKey, title, artist);
            }
            tryApplyKuwoLyricsFromCurrentExtras();
            return;
        }
        if (!shouldUseNetworkApiSource()) {
            if (!SuperLyricApi.isServiceAvailable()) {
                updateHookSourceStatusText(LyricsRuntimeSource.SUPER_LYRIC, "模块未就绪");
                scheduleNoLyricsIfStillEmpty(requestSeq, trackKey, LyricsRuntimeSource.SUPER_LYRIC, "服务不可用");
                return;
            }
            updateHookSourceStatusText(LyricsRuntimeSource.SUPER_LYRIC, "等待模块推送");
            // 与 SuperLyricApi ModuleDemo 一致：先 registerReceiver，再靠 onLyric 实时驱动。
            SuperLyricApi.ensureReceiverRegistered();
            startSuperLyricRealtimeTicker();
            fetchLyricsFromSuperLyricApi(title, artist, requestSeq, trackKey);
            return;
        }
        if (isMixedLyricsSourceMode()) {
            // MIXED：网络 API 优先；汽水 qsgc 超时未返回才 SuperLyric 单行，完整歌词到达后逐字融合。
            SuperLyricApi.ensureReceiverRegistered();
            startSuperLyricRealtimeTicker();
            if (MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending(pkgSafe, true)) {
                qishuiAwaitingNetworkLyrics = true;
                scheduleQishuiNetworkWaitThenSuperLyricPreview(
                    requestSeq, trackKey, title, artist, pkgSafe);
            }
        } else {
            stopSuperLyricRealtimeTicker();
        }
        String networkPkg = resolveCurrentLyricsPackageName();
        MusicPlayerLyricsPolicy.PrimaryStrategy networkStrategy =
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy(networkPkg);
        updateHookSourceStatusText(
            LyricsRuntimeSource.NETWORK,
            "拉取中·" + MusicPlayerLyricsPolicy.strategyDisplayLabel(networkStrategy)
        );
        fetchLyricsFromThirdPartyApi(title, artist, "网络API优先获取", requestSeq, trackKey);
    }

    private void cancelPendingQishuiSuperLyricPreviewWait() {
        if (pendingQishuiSuperLyricPreviewRunnable != null) {
            uiHandler.removeCallbacks(pendingQishuiSuperLyricPreviewRunnable);
            pendingQishuiSuperLyricPreviewRunnable = null;
        }
    }

    /**
     * 汽水 qsgc 偏慢：先等网络 API；超时仍无完整歌词再 SuperLyric 单行占位。
     */
    private void scheduleQishuiNetworkWaitThenSuperLyricPreview(int requestSeq,
                                                                String trackKey,
                                                                String title,
                                                                String artist,
                                                                String pkg) {
        cancelPendingQishuiSuperLyricPreviewWait();
        if (!isMixedLyricsSourceMode()
            || !MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending(pkg, true)) {
            return;
        }
        final String titleSafe = title != null ? title : "";
        final String artistSafe = artist != null ? artist : "";
        final String pkgSafe = pkg != null ? pkg : "";
        pendingQishuiSuperLyricPreviewRunnable = () -> {
            pendingQishuiSuperLyricPreviewRunnable = null;
            if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                return;
            }
            if (!qishuiAwaitingNetworkLyrics) {
                return;
            }
            if (hasMixedModeNetworkLyricsStructure()) {
                return;
            }
            if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty() && !superLyricFallbackModeActive) {
                return;
            }
            LogHelper.d(TAG, "汽水：网络 API 等待超时，启用 SuperLyric 单行兜底");
            bootstrapQishuiSuperLyricPreview(requestSeq, trackKey, titleSafe, artistSafe, pkgSafe);
        };
        uiHandler.postDelayed(
            pendingQishuiSuperLyricPreviewRunnable,
            QISHUI_NETWORK_API_PRIORITY_DELAY_MS
        );
    }

    /**
     * 网络 API 超时后：SuperLyric 单行占位（仍等待 qsgc 完整歌词）。
     */
    private void bootstrapQishuiSuperLyricPreview(int requestSeq,
                                                  String trackKey,
                                                  String title,
                                                  String artist,
                                                  String pkg) {
        if (!isMixedLyricsSourceMode()
            || !MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending(pkg, true)) {
            return;
        }
        final String titleSafe = title != null ? title : "";
        final String artistSafe = artist != null ? artist : "";
        final String pkgSafe = pkg != null ? pkg : "";
        SuperLyricApi.SuperLyricFallbackPayload cached =
            SuperLyricApi.peekCachedFallbackPayload(titleSafe, artistSafe, pkgSafe);
        if (cached != null && cached.text != null && !cached.text.trim().isEmpty()) {
            uiHandler.post(() -> {
                if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                    return;
                }
                if (applySuperLyricFallbackPayload(cached, pkgSafe, requestSeq, trackKey)) {
                    updateHookSourceStatusText(LyricsRuntimeSource.SUPER_LYRIC, "单句·等待汽水API");
                }
            });
            return;
        }
        AppExecutors.runInBackground(() -> {
            try {
                SuperLyricApi.SuperLyricFallbackPayload payload =
                    SuperLyricApi.fetchFallbackPayload(titleSafe, artistSafe, pkgSafe);
                if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                    return;
                }
                if (payload == null || payload.text == null || payload.text.trim().isEmpty()) {
                    return;
                }
                runOnUiThread(() -> {
                    if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                        return;
                    }
                    if (applySuperLyricFallbackPayload(payload, pkgSafe, requestSeq, trackKey)) {
                        updateHookSourceStatusText(LyricsRuntimeSource.SUPER_LYRIC, "单句·等待汽水API");
                    }
                });
            } catch (Exception e) {
                LogHelper.w(TAG, "汽水单句预加载失败: " + e.getMessage());
            }
        });
    }

    private void startSuperLyricRealtimeTicker() {
        // NETWORK_ONLY：严格不接入 SuperLyric。
        if (isNetworkOnlyLyricsSourceMode()) {
            return;
        }
        if (isKuwoAwaitingNativeLyrics()) {
            return;
        }
        SuperLyricApi.ensureReceiverRegistered();
        if (superLyricRealtimeListener != null) {
            if (!shouldUseNetworkApiSource()) {
                uiHandler.post(this::tryFlushCachedSuperLyricFallback);
                uiHandler.post(this::tryFlushCachedSuperLyricWordFusion);
            } else {
                uiHandler.post(this::tryFlushCachedSuperLyricWordFusion);
            }
            return;
        }
        // 以回调驱动为主：只要 SuperLyric 推送，就立刻更新当前句（避免轮询导致耗电）。
        superLyricRealtimeListener = (publisher, title, artist, text, payload, atMs) -> {
            if (!shouldRunSuperLyricRealtimeWhilePaused()) return;
            if (payload == null || payload.text == null) return;
            final String t = payload.text.trim();
            if (t.isEmpty()) return;
            if (!isSuperLyricRealtimeTrackMatched(title, artist)) {
                if (BuildConfig.DEBUG) {
                    LogHelper.d(TAG, "⏭ 忽略跨曲 SuperLyric 回调: cbTitle="
                        + safeShort(title) + ", cbArtist=" + safeShort(artist)
                        + ", stableTitle=" + safeShort(stableTitle)
                        + ", stableArtist=" + safeShort(stableArtist));
                }
                return;
            }
            final int seq = lyricsRequestSeq;
            final String tk = currentTrackKey != null ? currentTrackKey : "";
            final String sourcePkg = resolveCurrentLyricsPackageName();
            uiHandler.postAtFrontOfQueue(() -> {
                if (BuildConfig.DEBUG) {
                    LogHelper.d(TAG, "🎤 SuperLyric(回调) recv: publisher=" + (publisher != null ? publisher : "")
                        + ", pos=" + getCurrentPlaybackPositionSafe()
                        + "ms, lineStart=" + payload.lineStartMs
                        + "ms, text=" + safeShort(t));
                }
                boolean applied = false;
                // SUPER_LYRIC_ONLY：歌词与逐字仅来自模块；多行整曲时优先逐字融合，否则单句兜底。
                if (!shouldUseNetworkApiSource()) {
                    if (payload.hasValidWords() && canUseSuperLyricWordFusion()) {
                        applied = applySuperLyricWordFusionPayload(payload, sourcePkg, seq, tk);
                    }
                    if (!applied) {
                        applied = applySuperLyricFallbackPayload(payload, sourcePkg, seq, tk);
                    }
                } else if (isMixedLyricsSourceMode()) {
                    applied = dispatchMixedModeSuperLyricPayload(payload, sourcePkg, seq, tk);
                } else {
                    boolean hasStructuredLyrics = enhancedLyricLines != null && !enhancedLyricLines.isEmpty();
                    boolean canFuseWordTimestamps = supportsWordByWordLyricsFeatures()
                        && currentLyricsSource == LyricsSource.NETWORK_API
                        && hasStructuredLyrics
                        && !superLyricFallbackModeActive;
                    if (canFuseWordTimestamps) {
                        applied = applySuperLyricWordFusionPayload(payload, sourcePkg, seq, tk);
                    } else {
                        applied = applySuperLyricFallbackPayload(payload, sourcePkg, seq, tk);
                    }
                }
                // 纯回调驱动：每次有效 payload 都交给渲染链路处理，不做文本去重拦截。
            });
        };
        SuperLyricApi.addRealtimeListener(superLyricRealtimeListener);
        if (!shouldUseNetworkApiSource()) {
            uiHandler.post(this::tryFlushCachedSuperLyricFallback);
            uiHandler.post(this::tryFlushCachedSuperLyricWordFusion);
        } else {
            uiHandler.post(this::tryFlushCachedSuperLyricWordFusion);
        }
        // 纯回调驱动刷新：不再使用定时轮询。
    }

    /**
     * 仅 SuperLyric / 智能切换占位：用 Binder 最新缓存立即显示，避免只等 fetch 超时。
     */
    private void tryFlushCachedSuperLyricFallback() {
        if (isNetworkOnlyLyricsSourceMode()) {
            return;
        }
        if (isMixedLyricsSourceMode() && hasMixedModeNetworkLyricsStructure()) {
            return;
        }
        if (qishuiAwaitingNetworkLyrics && !superLyricFallbackModeActive) {
            return;
        }
        SuperLyricApi.SuperLyricFallbackPayload payload = null;
        String pkg = resolveCurrentLyricsPackageName();
        String title = stableTitle != null ? stableTitle : "";
        String artist = stableArtist != null ? stableArtist : "";
        if (!TextUtils.isEmpty(title)) {
            payload = SuperLyricApi.peekCachedFallbackPayload(title, artist, pkg);
        }
        if (payload == null || payload.text == null || payload.text.trim().isEmpty()) {
            return;
        }
        if (lyricsView != null && lyricsView.isTrackLoading() && isMixedLyricsSourceMode()) {
            return;
        }
        int seq = lyricsRequestSeq;
        String tk = currentTrackKey != null ? currentTrackKey : "";
        applySuperLyricFallbackPayload(payload, pkg, seq, tk);
    }

    /** onResume / 背屏 UI 就绪后恢复 ModuleDemo 式接收链路。 */
    private void resumeSuperLyricLyricsPipeline() {
        if (isNetworkOnlyLyricsSourceMode() || isKuwoAwaitingNativeLyrics()) {
            return;
        }
        SuperLyricApi.ensureReceiverRegistered();
        if (isMixedLyricsSourceMode()) {
            startSuperLyricRealtimeTicker();
            return;
        }
        if (!shouldUseNetworkApiSource()) {
            startSuperLyricRealtimeTicker();
        }
    }

    private void stopSuperLyricRealtimeTicker() {
        try {
        } catch (Exception ignored) {
        }
        if (superLyricRealtimeListener != null) {
            try {
                SuperLyricApi.removeRealtimeListener(superLyricRealtimeListener);
            } catch (Exception ignored) {
            }
            superLyricRealtimeListener = null;
        }
        lastSuperLyricAppliedTextNormalized = "";
        lastSuperLyricAppliedLineTimeMs = -1L;
        cachedWordFusionPayload = null;
        wordFusionGeneration.incrementAndGet();
    }

    private void cacheSuperLyricWordFusionPayload(SuperLyricApi.SuperLyricFallbackPayload payload) {
        if (payload == null || !payload.hasValidWords()) {
            return;
        }
        cachedWordFusionPayload = payload;
    }

    /**
     * 多行结构刚就绪时，用已缓存的 SuperLyric 逐字立即补融合（无需等待下一次 Binder 推送）。
     */
    private void tryFlushCachedSuperLyricWordFusion() {
        if (!canUseSuperLyricWordFusion()) {
            return;
        }
        SuperLyricApi.SuperLyricFallbackPayload payload = cachedWordFusionPayload;
        if (payload == null || !payload.hasValidWords()) {
            String pkg = resolveCurrentLyricsPackageName();
            payload = SuperLyricApi.peekCachedFallbackPayload(stableTitle, stableArtist, pkg);
            if (payload != null && payload.hasValidWords()) {
                cachedWordFusionPayload = payload;
            }
        }
        if (payload == null || !payload.hasValidWords()) {
            return;
        }
        int seq = lyricsRequestSeq;
        String tk = currentTrackKey != null ? currentTrackKey : "";
        applySuperLyricWordFusionPayload(payload, resolveCurrentLyricsPackageName(), seq, tk);
    }

    private boolean shouldAcceptApiResultForTrack(String pkg) {
        if (isSuperLyricOnlySourceMode()) {
            return false;
        }
        // 当前由 SuperLyric 兜底时，若 API 命中应允许覆盖以恢复完整 LRC。
        if (currentLyricsSource == LyricsSource.SUPER_LYRIC_FALLBACK) return true;
        // 酷我专用通道已有歌词时，不让网络结果覆盖；兜底阶段（kuwoAllowThirdPartyFallback）允许
        if (currentLyricsSource == LyricsSource.KUWO_CAR) {
            if (kuwoAllowThirdPartyFallback) {
                return true;
            }
            return enhancedLyricLines == null || enhancedLyricLines.isEmpty();
        }
        // 若 API 来源包名与当前不一致，也不要覆盖
        if (pkg != null && !pkg.isEmpty() && currentLyricsSourcePkg != null && !currentLyricsSourcePkg.isEmpty()) {
            return pkg.equalsIgnoreCase(currentLyricsSourcePkg);
        }
        return true;
    }

    private static LyricsRuntimeSource resolveNetworkRuntimeSource(String provider) {
        if (TextUtils.isEmpty(provider)) {
            return LyricsRuntimeSource.NETWORK;
        }
        if ("kugou".equalsIgnoreCase(provider)) {
            return LyricsRuntimeSource.NETWORK_KUGOU;
        }
        if ("qsgc".equalsIgnoreCase(provider)) {
            return LyricsRuntimeSource.NETWORK_QSGC;
        }
        if ("lrclib".equalsIgnoreCase(provider)) {
            return LyricsRuntimeSource.NETWORK_LRCLIB;
        }
        if ("lyrics.ovh".equalsIgnoreCase(provider)) {
            return LyricsRuntimeSource.NETWORK_LYRICS_OVH;
        }
        return LyricsRuntimeSource.NETWORK;
    }

    private static String normalizeLyricsSourceMode(String raw) {
        if (VALUE_LYRICS_SOURCE_NETWORK_ONLY.equalsIgnoreCase(raw)) {
            return VALUE_LYRICS_SOURCE_NETWORK_ONLY;
        }
        if (VALUE_LYRICS_SOURCE_SUPER_ONLY.equalsIgnoreCase(raw)) {
            return VALUE_LYRICS_SOURCE_SUPER_ONLY;
        }
        return VALUE_LYRICS_SOURCE_MIXED;
    }

    private boolean shouldUseNetworkApiSource() {
        return !VALUE_LYRICS_SOURCE_SUPER_ONLY.equalsIgnoreCase(lyricsSourceMode);
    }

    private boolean isMixedLyricsSourceMode() {
        return VALUE_LYRICS_SOURCE_MIXED.equalsIgnoreCase(lyricsSourceMode);
    }

    private boolean isNetworkOnlyLyricsSourceMode() {
        return VALUE_LYRICS_SOURCE_NETWORK_ONLY.equalsIgnoreCase(lyricsSourceMode);
    }

    private boolean isSuperLyricOnlySourceMode() {
        return VALUE_LYRICS_SOURCE_SUPER_ONLY.equalsIgnoreCase(lyricsSourceMode);
    }

    private boolean shouldUseSuperLyricFallback() {
        return !VALUE_LYRICS_SOURCE_NETWORK_ONLY.equalsIgnoreCase(lyricsSourceMode);
    }

    /**
     * 逐行歌词模式才启用逐字显示与 SuperLyric 逐字融合；分词 / 深渊镜 / 省电下关闭。
     */
    private boolean supportsWordByWordLyricsFeatures() {
        return !powerSavingModeEnabled && !shuffleSplitEffectEnabled && !abyssalMirrorEnabled;
    }

    /**
     * 智能切换：是否已有网络 API 歌词结构（≥1 行即可逐字融合，与 1.8.4 参考版一致）。
     * 汽水等待 qsgc 期间走 {@link #superLyricFallbackModeActive} 单句模块，不算网络结构。
     */
    private boolean hasMixedModeNetworkLyricsStructure() {
        if (!isMixedLyricsSourceMode()) {
            return false;
        }
        if (isKuwoAwaitingNativeLyrics() && !kuwoAllowThirdPartyFallback) {
            return false;
        }
        return currentLyricsSource == LyricsSource.NETWORK_API
            && enhancedLyricLines != null
            && !enhancedLyricLines.isEmpty()
            && !superLyricFallbackModeActive;
    }

    /**
     * 智能切换 SuperLyric 回调分发（参考 1.8.4）：无网络歌词 → 模块单句+逐字；有网络 → 仅融合逐字，不改行文本。
     */
    private boolean dispatchMixedModeSuperLyricPayload(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                       String sourcePkg,
                                                       int requestSeq,
                                                       String trackKey) {
        // V3.17+: 先校验是否为当前曲目，再缓存逐字，防止切歌后旧回调缓存的数据污染新歌
        if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
            return false;
        }
        if (payload != null && payload.hasValidWords()) {
            cacheSuperLyricWordFusionPayload(payload);
        }
        if (qishuiAwaitingNetworkLyrics
            && MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending(sourcePkg, true)) {
            if (superLyricFallbackModeActive) {
                return applySuperLyricFallbackPayload(payload, sourcePkg, requestSeq, trackKey);
            }
            return false;
        }
        boolean hasNetworkStructure = hasMixedModeNetworkLyricsStructure();
        if (hasNetworkStructure && payload != null && payload.hasValidWords()) {
            if (applyMixedModeWordFusionPayload(payload, sourcePkg, requestSeq, trackKey)) {
                return true;
            }
            long playbackPosition = resolveLyricsAnchorPositionMs();
            if (tryApplyStickyWordFusionOnPlaybackLine(
                payload, sourcePkg, requestSeq, trackKey, playbackPosition)) {
                return true;
            }
            if (BuildConfig.DEBUG) {
                LogHelper.d(TAG, "⏭ 智能切换·网络在但未融合: text=" + safeShort(payload.text));
            }
            return false;
        }
        if (hasNetworkStructure) {
            return false;
        }
        return applySuperLyricFallbackPayload(payload, sourcePkg, requestSeq, trackKey);
    }

    /** 仅 SuperLyric 模式：模块整曲 LRC 已就绪，可用实时推送逐字对齐到各行。 */
    private boolean hasSuperLyricModuleMultiLineStructure() {
        if (!isSuperLyricOnlySourceMode()) {
            return false;
        }
        if (superLyricFallbackModeActive) {
            return false;
        }
        return enhancedLyricLines != null && enhancedLyricLines.size() >= 2;
    }

    /** 当前显示模式与来源策略均允许 SuperLyric 逐字融合。 */
    private boolean canUseSuperLyricWordFusion() {
        if (!supportsWordByWordLyricsFeatures()) {
            return false;
        }
        if (isMixedLyricsSourceMode()) {
            return hasMixedModeNetworkLyricsStructure();
        }
        if (isSuperLyricOnlySourceMode()) {
            return hasSuperLyricModuleMultiLineStructure();
        }
        return false;
    }

    private void markStructuredLyricsForWordFusion(String sourcePkg,
                                                   LyricsRuntimeSource runtimeSource,
                                                   String networkProvider) {
        superLyricFallbackModeActive = false;
        currentLyricsSource = LyricsSource.NETWORK_API;
        currentLyricsSourcePkg = sourcePkg != null ? sourcePkg : "";
        if (runtimeSource != null) {
            lastNetworkLyricsRuntimeSource = runtimeSource;
        }
        if (networkProvider != null) {
            lastNetworkLyricsProvider = networkProvider;
        }
    }

    /** 仅 SuperLyric 模式：整曲 LRC 来自模块，逐字仅由 Binder 推送写入。 */
    private void markStructuredLyricsFromSuperLyricModule(String sourcePkg) {
        superLyricFallbackModeActive = false;
        currentLyricsSource = LyricsSource.SUPER_LYRIC_FALLBACK;
        currentLyricsSourcePkg = sourcePkg != null ? sourcePkg : "";
        lastNetworkLyricsRuntimeSource = LyricsRuntimeSource.SUPER_LYRIC;
        lastNetworkLyricsProvider = "";
    }

    /**
     * 结构化多行歌词入库前整理逐字轴。
     *
     * @param clearWordTimestamps true：整曲来自 SuperLyric 模块、逐字由 Binder 推送（仅 SuperLyric / 智能切换占位）；
     *                          false：来自网络 API / 通知 LRC，保留解析出的逐字时间戳（与 {@linkplain #isNetworkOnlyLyricsSourceMode 网络 API} 一致）。
     */
    private void prepareStructuredLyricLines(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                             boolean clearWordTimestamps) {
        if (lines == null) {
            return;
        }
        for (EnhancedLRCParser.EnhancedLyricLine line : lines) {
            if (line == null) {
                continue;
            }
            line.moduleWordTimeline = false;
            if (clearWordTimestamps && line.wordTimestamps != null) {
                line.wordTimestamps.clear();
            }
        }
    }

    /** 与 {@link #prepareStructuredLyricLines(List, boolean)} 配套：网络 API 多行保留 LRC 内嵌逐字轴。 */
    private void prepareNetworkApiLyricLines(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        // 智能切换：保留 LRC 内嵌逐字轴作为模块就绪前的逐字数据，
        // 但需对其做等分拉伸，避免 LRC 每字时长过短导致逐字高亮一下跑完。
        prepareStructuredLyricLines(lines, false);
        normalizeLrcWordTimestamps(lines);
        sanitizeNetworkLyricLines(lines);
    }

    /**
     * 将 LRC 逐字时间戳等分拉伸到行时间区间内。LRC 的 {@code <start,end>word} 每字时长
     * 通常仅 50-200ms，直接使用会导致逐字进度在 1 秒内跑完。此函数将行可用时长均匀分配
     * 到每个字，使逐字进度与实际演唱时间匹配。
     */
    private static void normalizeLrcWordTimestamps(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            EnhancedLRCParser.EnhancedLyricLine line = lines.get(i);
            if (line == null || line.wordTimestamps == null || line.wordTimestamps.size() < 2) {
                continue;
            }
            // 行可用时长：当前行到下一行的时间间隔
            long lineStart = Math.max(0L, line.time);
            long lineEnd = lineStart + 5000L; // 默认 5 秒
            if (i + 1 < lines.size()) {
                EnhancedLRCParser.EnhancedLyricLine next = lines.get(i + 1);
                if (next != null && next.time > lineStart) {
                    lineEnd = next.time;
                }
            }
            long lineDuration = Math.max(1000L, lineEnd - lineStart); // 至少 1 秒
            int wordCount = line.wordTimestamps.size();
            long perWordDuration = lineDuration / wordCount;

            long currentStart = lineStart;
            for (int j = 0; j < wordCount; j++) {
                EnhancedLRCParser.WordTimestamp word = line.wordTimestamps.get(j);
                if (word == null) continue;
                word.startTime = currentStart;
                // 最后一个字使用剩余全部时长
                if (j == wordCount - 1) {
                    long remaining = lineStart + lineDuration - currentStart;
                    word.endTime = currentStart + Math.max(perWordDuration, remaining);
                } else {
                    word.endTime = currentStart + perWordDuration;
                }
                currentStart = word.endTime;
            }
        }
    }

    /**
     * 网络 LRC 入库前补齐空字段、保证行时间与索引安全，避免末句错位或 UI 异常。
     */
    private static void sanitizeNetworkLyricLines(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i) == null) {
                lines.remove(i);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        long prevTime = -1L;
        for (int i = 0; i < lines.size(); i++) {
            EnhancedLRCParser.EnhancedLyricLine line = lines.get(i);
            if (line.text == null) {
                line.text = "";
            } else {
                line.text = line.text.trim();
            }
            if (line.time < 0L) {
                line.time = 0L;
            }
            if (line.time < prevTime) {
                line.time = prevTime;
            }
            prevTime = line.time;
            if (line.wordTimestamps == null) {
                line.wordTimestamps = new ArrayList<>();
            }
            line.moduleWordTimeline = false;
        }
        EnhancedLRCParser.EnhancedLyricLine last = lines.get(lines.size() - 1);
        if (last != null && last.time < 0L) {
            last.time = 0L;
        }
    }

    /** 0 ≤ index < size，否则 -1。 */
    private static int clampLyricLineIndex(List<EnhancedLRCParser.EnhancedLyricLine> lines, int index) {
        if (lines == null || lines.isEmpty() || index < 0 || index >= lines.size()) {
            return -1;
        }
        return index;
    }

    /** SuperLyric 整曲结构：清空逐字，等待模块推送（与仅 SuperLyric 整曲一致）。 */
    private void prepareSuperLyricModuleLyricLines(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        prepareStructuredLyricLines(lines, true);
    }

    /**
     * 仅 SuperLyric 多行逐字：对齐到网络/模块行文本的整曲绝对毫秒轴（与单句 {@link #applySuperLyricFallbackPayload} 中 alignToLineTime 同族，经 map 挂到行文本）。
     */
    private static ArrayList<EnhancedLRCParser.WordTimestamp> buildSuperLyricModuleLineWordTimestamps(
        long lineTimeMs,
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> moduleWords,
        long nextLineTimeMs,
        long moduleLineStartMs
    ) {
        return SuperLyricWordTimestamps.alignAndMapToLineText(
            lineTimeMs, lineText, moduleWords, nextLineTimeMs, moduleLineStartMs);
    }

    /**
     * 当前播放行是否已写入 SuperLyric 模块融合逐字（非 LRC 内嵌轴）。
     * 智能切换入库时已清 LRC 内嵌轴，故当前行 {@code wordTimestamps} 非空即视为模块融合。
     * 勿用 {@code lastWordFusionLineIndex} 比对：行边界进度抖动或融合行略滞后时会误判为「时间戳」导致 Debug 来源条来回跳。
     */
    private boolean currentLineHasSuperLyricWordFusion() {
        if (enhancedLyricLines == null || enhancedLyricLines.isEmpty()) {
            return false;
        }
        long position = resolveLyricsAnchorPositionMs();
        int index = findCurrentLineIndexForAbyssal(enhancedLyricLines, position);
        if (index < 0 || index >= enhancedLyricLines.size()) {
            return false;
        }
        EnhancedLRCParser.EnhancedLyricLine line = enhancedLyricLines.get(index);
        return line != null
            && line.wordTimestamps != null
            && !line.wordTimestamps.isEmpty();
    }
    
    // 按钮显示/隐藏控制
    private LinearLayout buttonLayout;
    /** 底部媒体键条在根 FrameLayout 上的 LayoutParams，便于按 cutout 刷新边距 */
    private android.widget.FrameLayout.LayoutParams mediaButtonBarLayoutParams;
    private boolean buttonsVisible = false; // 初始状态为隐藏，点击歌曲名称才显示
    
    // 跑马灯视图
    private MarqueeLightView marqueeLightView;
    
    // 背屏常亮开关状态变化监听
    private SharedPreferences.OnSharedPreferenceChangeListener keepScreenOnPreferenceListener;

    /** 投屏期间背屏遮盖检测（无单独前台通知） */
    private RearProjectionProximitySession projectionProximitySession;
    private SharedPreferences.OnSharedPreferenceChangeListener rearAssistProximityPrefsListener;

    // 主屏横屏模式：系统UI可见性变化监听（退出全屏时销毁）
    private View.OnSystemUiVisibilityChangeListener systemUIVisibilityChangeListener;
    private Handler systemUICheckHandler;
    private Runnable systemUICheckRunnable;

    private static void setTextIfChanged(TextView view, String text) {
        if (view == null) return;
        if (TextUtils.equals(view.getText(), text)) return;
        view.setText(text);
    }

    private void updateHookSourceStatusText(LyricsRuntimeSource source) {
        updateHookSourceStatusText(source, null);
    }

    /**
     * Debug：标题下「来源」按歌词来源模式格式化。
     * <ul>
     *   <li>网络 API：网络API · 优先级 · 当前歌词来源</li>
     *   <li>SuperLyric：仅 SuperLyric</li>
     *   <li>智能切换：智能 · 优先级 · 当前歌词来源 · 逐字信息来源（逐字融合 / 时间戳）</li>
     * </ul>
     */
    private void updateHookSourceStatusText(LyricsRuntimeSource source, String detail) {
        if (!BuildConfig.DEBUG) return;
        if (hookSourceStatusText == null) return;
        hookLastRuntimeSource = source != null ? source : LyricsRuntimeSource.IDLE;
        hookLastRuntimeDetail = detail != null ? detail.trim() : "";
        refreshHookSourceStatusDisplay();
    }

    /** 智能切换：逐字融合提交后刷新「逐字信息来源」段。 */
    private void updateHookSourceStatusWordFusion() {
        if (!BuildConfig.DEBUG) return;
        refreshHookSourceStatusDisplay();
    }

    private void refreshHookSourceStatusDisplay() {
        if (!BuildConfig.DEBUG || hookSourceStatusText == null) return;
        if (isSuperLyricOnlySourceMode()) {
            setTextIfChanged(hookSourceStatusText, "来源：SuperLyric");
            return;
        }
        final String priority = resolveHookPriorityLabel(hookLastRuntimeDetail);
        final String channel = resolveHookActiveLyricsChannelLabel(
            hookLastRuntimeSource, hookLastRuntimeDetail);
        if (isNetworkOnlyLyricsSourceMode()) {
            setTextIfChanged(hookSourceStatusText,
                "来源：网络API · " + priority + " · " + channel);
            return;
        }
        if (isMixedLyricsSourceMode()) {
            final String wordTimelineSource = currentLineHasSuperLyricWordFusion()
                ? "逐字融合"
                : "时间戳";
            setTextIfChanged(hookSourceStatusText,
                "来源：智能 · " + priority + " · " + channel + " · " + wordTimelineSource);
        }
    }

    private String resolveHookPriorityLabel(String detail) {
        if (detail != null && !detail.isEmpty()) {
            if (detail.endsWith("汽水优先")) {
                return "汽水优先";
            }
            if (detail.endsWith("酷狗优先")) {
                return "酷狗优先";
            }
            if (detail.startsWith("兜底·")) {
                return detail.substring("兜底·".length()).trim();
            }
            if (detail.startsWith("拉取中·")) {
                return detail.substring("拉取中·".length()).trim();
            }
        }
        final String pkg = resolveCurrentLyricsPackageName();
        return MusicPlayerLyricsPolicy.strategyDisplayLabel(
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy(pkg));
    }

    private String resolveHookActiveLyricsChannelLabel(LyricsRuntimeSource source, String detail) {
        if (source == null) {
            return "—";
        }
        switch (source) {
            case IDLE:
                return "待机";
            case ACQUIRING:
                return "获取中";
            case KUWO_PENDING:
                return "等待 AUDIO_LYRIC";
            case KUWO_AUDIO_LYRIC:
                return "AUDIO_LYRIC";
            case SUPER_LYRIC:
                if (detail != null && !detail.isEmpty() && !isHookStrategyDetail(detail)) {
                    return detail;
                }
                return "SuperLyric";
            case NETWORK:
            case NETWORK_KUGOU:
            case NETWORK_QSGC:
            case NETWORK_LRCLIB:
            case NETWORK_LYRICS_OVH:
                String api = LyricsRuntimeSource.shortApiLabel(lastNetworkLyricsProvider);
                if (api == null || api.isEmpty() || "网络API".equals(api)) {
                    api = LyricsRuntimeSource.shortApiLabel(source);
                }
                return api;
            default:
                return source.getDisplayLabel();
        }
    }

    private static boolean isHookStrategyDetail(String detail) {
        final String d = detail != null ? detail.trim() : "";
        return "汽水优先".equals(d)
            || "酷狗优先".equals(d)
            || d.startsWith("兜底·")
            || d.startsWith("拉取中·");
    }

    private static void setVisibilityIfChanged(View view, int visibility) {
        if (view == null) return;
        if (view.getVisibility() == visibility) return;
        view.setVisibility(visibility);
    }

    private static void setAlphaIfChanged(View view, float alpha) {
        if (view == null) return;
        if (Math.abs(view.getAlpha() - alpha) < 0.0001f) return;
        view.setAlpha(alpha);
    }

    private void applyRenderFrameRateBudget(View view) {
        if (view == null) {
            return;
        }
        final float targetFps = (shuffleSplitEffectEnabled || powerSavingModeEnabled)
                ? REAR_RENDER_TARGET_FPS_SHUFFLE
                : REAR_RENDER_TARGET_FPS;
        try {
            java.lang.reflect.Method m = View.class.getMethod(
                    "setFrameRate",
                    float.class,
                    int.class,
                    int.class
            );
            m.invoke(
                    view,
                    targetFps,
                    android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            );
        } catch (Throwable ignored) {
        }
    }
    
    // TaskService相关（用于屏蔽/恢复官方手势服务），改为通过 RootTaskService 绑定
    private ITaskService taskService;
    private boolean isOfficialGestureDisabled = false; // 标记是否已屏蔽官方手势服务

    /** 屏底窄条内左右滑结束投屏：统一手势处理器 */
    private BottomSwipeExitHelper.Handler bottomSwipeHandler = null;
    private boolean lyricsBackPressedCallbackRegistered;

    // TaskService连接回调
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            LogHelper.d(TAG, "✅ TaskService已连接（用于屏蔽官方手势服务，RootTaskService）");
            checkAndDisableOfficialGesture();
            RearMirootProjectionLifecycle.reinforceOfficialSubscreenDisabled(getApplicationContext());

            uiHandler.post(() -> {
                if (!isFinishing()) {
                    applyMediaButtonBarInsets();
                }
            });
            
            // 布局稳定后再应用一次媒体条 inset（与 TaskService 无关）
            if (taskServiceRecheckRunnable != null) {
                uiHandler.removeCallbacks(taskServiceRecheckRunnable);
            }
            taskServiceRecheckRunnable = () -> {
                if (taskService != null && !isFinishing()) {
                    applyMediaButtonBarInsets();
                }
            };
            uiHandler.postDelayed(taskServiceRecheckRunnable, 500);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            LogHelper.w(TAG, "⚠️ TaskService断开连接");
            taskService = null;
            
            // 断开后尝试重连，但不再因此结束投屏
            if (taskServiceRebindRunnable != null) {
                uiHandler.removeCallbacks(taskServiceRebindRunnable);
            }
            taskServiceRebindRunnable = () -> {
                LogHelper.d(TAG, "🔄 尝试重新绑定TaskService（RootTaskService）...");
                bindTaskService();
            };
            uiHandler.postDelayed(taskServiceRebindRunnable, 1000);
        }
    };
    
    // 设置相关
    private static final String PREFS_NAME = "LyricsSettings";
    private static final String KEY_TEXT_SIZE = "textSize";
    private static final String KEY_BACKGROUND_TEXTURE_SIZE = "backgroundTextureSize";
    private static final String KEY_NORMAL_LYRICS_ALPHA = "normalLyricsAlpha";
    private static final String KEY_BACKGROUND_TEXTURE_ALPHA = "backgroundTextureAlpha";
    private static final String KEY_WORD_BY_WORD = "wordByWord";
    private static final String KEY_SHUFFLE_SPLIT_EFFECT = "shuffleSplitEffect";
    private static final String KEY_SHUFFLE_SPLIT_MULTICOLOR = "shuffleSplitMulticolor";
    private static final String KEY_SHUFFLE_SPLIT_MODE = "shuffleSplitMode";
    private static final String KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE = "shuffleSplitOnlyCurrentLine";
    private static final String KEY_SHUFFLE_SPLIT_SCALE_VARIANCE = "shuffleSplitScaleVariance";
    private static final String KEY_SHUFFLE_SPLIT_PERFORMANCE_GUARD = "shuffleSplitPerformanceGuard";
    private static final String KEY_MARQUEE_LIGHT = "marqueeLight";
    private static final String KEY_NEON_DISPLAY = "neonDisplay";
    /** 旧版「歌词霓虹」键，仅用于迁移读取。 */
    private static final String KEY_LYRICS_NEON_GLOW_LEGACY = "lyricsNeonGlow";
    private static final String KEY_NEON_BORDER = "neonBorder";
    private static final String KEY_MARQUEE_LIGHT_SIZE = "marqueeLightSize";
    private static final String KEY_MARQUEE_LIGHT_DURATION_MS = "marqueeLightDurationMs";
    private static final String KEY_GESTURE_CONTROL = "gestureControl";
    private static final String KEY_POWER_SAVING_MODE = "powerSavingMode";
    private static final String KEY_BORDER_PERFORMANCE_GUARD = "borderPerformanceGuard";
    private static final String KEY_BORDER_LIGHTWEIGHT_MODE = "borderLightweightMode";
    private static final String KEY_BACKGROUND_TEXTURE = "backgroundTexture";
    private static final String KEY_ALBUM_ART_BACKGROUND = "albumArtBackground";
    private static final String KEY_ALBUM_ART_ALPHA_PERCENT = "albumArtAlphaPercent";
    private static final String KEY_ALBUM_ART_BLUR_RADIUS = "albumArtBlurRadius";
    private static final String KEY_AUTO_PROJECTION = "autoProjection";
    private static final String KEY_BREATHING_ENABLED = "breathingEnabled";
    private static final String KEY_BREATHING_BPM = "breathingBpm";
    private static final String KEY_BREATHING_SCALE_VARIANCE = "breathingScaleVariance";
    private static final String KEY_BREATHING_DISPLACEMENT_STRENGTH = "breathingDisplacementStrength";
    private static final String KEY_COLOR_CHANGE_INTERVAL_MS = "colorChangeIntervalMs";
    private static final String KEY_RANDOM_COLOR_SWITCH_ENABLED = "randomColorSwitchEnabled";
    private static final String KEY_FIXED_COLOR = "fixedColor";
    private static final String KEY_PROJECTION_SYNC_OFFSET_MS = "projectionSyncOffsetMs";
    private static final String KEY_LYRICS_SOURCE_MODE = "lyricsSourceMode";
    private static final String VALUE_LYRICS_SOURCE_NETWORK_ONLY = "NETWORK_ONLY";
    private static final String VALUE_LYRICS_SOURCE_SUPER_ONLY = "SUPER_LYRIC_ONLY";
    private static final String VALUE_LYRICS_SOURCE_MIXED = "MIXED";
    /** 与 [com.wmqc.miroot.ui.music.LyricsUiSettings] 中默认值一致（毫秒）。 */
    private static final int DEFAULT_PROJECTION_SYNC_OFFSET_MS = 0;
    private static final String KEY_ABYSSAL_MIRROR = "abyssalMirror"; // 深渊镜效果开关
    private static final String KEY_ABYSSAL_GYRO_SENSITIVITY = "abyssalGyroSensitivity"; // 深渊镜陀螺仪跟随倍数
    private static final String KEY_ABYSSAL_MOVABLE_RANGE = "abyssalMovableRange"; // 深渊镜可移动范围倍率
    private static final String KEY_PROJECTION_LYRICS_FONT = "projectionLyricsFont";
    private static final String KEY_PROJECTION_LYRICS_CUSTOM_PATH = "projectionLyricsCustomPath";
    private static final float DEFAULT_ABYSSAL_GYRO_SENSITIVITY = 1.0f;
    private static final float DEFAULT_ABYSSAL_MOVABLE_RANGE = 2.5f;
    /** 深渊镜歌词换行预提前量（ms），补偿 MediaController 位置读取管道延迟，使换行与听感同步。 */
    private static final long ABYSSAL_LINE_PRE_ADVANCE_MS = 150L;
    private static final float DEFAULT_TEXT_SIZE = 65f;  // 默认歌词文本大小65px
    private static final float DEFAULT_BACKGROUND_TEXTURE_SIZE = 1.3f;
    private static final int DEFAULT_NORMAL_LYRICS_ALPHA = 30;  // 30%透明度
    private static final int DEFAULT_BACKGROUND_TEXTURE_ALPHA = 20;  // 20%透明度
    private static final int DEFAULT_BREATHING_BPM = 15;
    private static final float DEFAULT_BREATHING_SCALE_VARIANCE = 0.10f;
    private static final float DEFAULT_BREATHING_DISPLACEMENT_STRENGTH = 1f;
    private static final int DEFAULT_COLOR_CHANGE_INTERVAL_MS = 5000;
    private static final int MIN_COLOR_CHANGE_INTERVAL_MS = 1000;
    private static final int MAX_COLOR_CHANGE_INTERVAL_MS = 30000;
    private static final int DEFAULT_MARQUEE_LIGHT_DURATION_MS = 5000;
    /**
     * 背屏左侧条带（摄像头/装饰区）：歌名、控制条与歌词绘制均从此宽度之后开始；
     * rootLayout 不再做横向 padding，专辑底图仍满屏铺满。
     */
    private static final int REAR_LYRICS_LEFT_CLEAR_PX = 277;
    private float currentTextSize = DEFAULT_TEXT_SIZE;
    private float backgroundTextureSize = DEFAULT_BACKGROUND_TEXTURE_SIZE;
    private int normalLyricsAlpha = DEFAULT_NORMAL_LYRICS_ALPHA;
    private int backgroundTextureAlpha = DEFAULT_BACKGROUND_TEXTURE_ALPHA;
    private boolean wordByWordEnabled = false;  // 默认关闭逐字显示
    private boolean shuffleSplitEffectEnabled = false;
    private boolean shuffleSplitMulticolorEnabled = false;
    private String shuffleSplitMode = "WORD";
    private boolean shuffleSplitOnlyCurrentLine = true;
    private boolean marqueeLightEnabled = true;  // 默认开启跑马灯
    private boolean neonDisplayEnabled = true;  // 霓虹总开关（歌词发光 + 边框受 neonBorder 约束）
    private boolean neonBorderEnabled = true;  // 「边框显示」：是否绘制边缘边框（无霓虹时为纯描边）
    private float marqueeLightSize = 17f;  // 默认跑马灯线条宽度17px
    private int marqueeLightDurationMs = DEFAULT_MARQUEE_LIGHT_DURATION_MS;
    private boolean gestureControlEnabled = false;  // 默认关闭手势控制
    private boolean powerSavingModeEnabled = false;  // 默认关闭省电模式
    private boolean borderPerformanceGuardEnabled = false;
    private boolean borderLightweightModeEnabled = false;
    private boolean backgroundTextureEnabled = false;  // 默认关闭歌词底图显示
    private boolean albumArtBackgroundEnabled = false;
    private int albumArtAlphaPercent = DEFAULT_ALBUM_ART_ALPHA_PERCENT;
    private float albumArtBlurRadiusPx = DEFAULT_ALBUM_ART_BLUR_RADIUS_PX;
    private Bitmap currentAlbumArtBitmap;
    private boolean breathingEnabled = false;
    private int breathingBpm = DEFAULT_BREATHING_BPM;
    private float breathingScaleVariance = DEFAULT_BREATHING_SCALE_VARIANCE;
    private float breathingDisplacementStrength = DEFAULT_BREATHING_DISPLACEMENT_STRENGTH;
    
    private int colorChangeIntervalMs = DEFAULT_COLOR_CHANGE_INTERVAL_MS;
    private boolean randomColorSwitchEnabled = true;
    private int fixedColor = 0xFFFFFFFF;
    /** 与 [com.wmqc.miroot.ui.music.LyricsSettingsRepository] 键名一致；正数表示歌词相对媒体进度提前显示（毫秒）。 */
    private int projectionSyncOffsetMs = DEFAULT_PROJECTION_SYNC_OFFSET_MS;
    private boolean abyssalMirrorEnabled = false;  // 默认关闭深渊镜效果
    private float abyssalGyroSensitivity = DEFAULT_ABYSSAL_GYRO_SENSITIVITY;  // 深渊镜陀螺仪跟随倍数
    private float abyssalMovableRange = DEFAULT_ABYSSAL_MOVABLE_RANGE;  // 深渊镜可移动范围倍率
    private String projectionLyricsFontId = LyricsFontHelper.DEFAULT_ID;
    private String projectionLyricsCustomPath;
    private String lyricsSourceMode = VALUE_LYRICS_SOURCE_MIXED;
    private String lastAppliedSettingsFingerprint = "";
    private static final int DEFAULT_ALBUM_ART_ALPHA_PERCENT = 35;
    private static final float DEFAULT_ALBUM_ART_BLUR_RADIUS_PX = 12f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 禁用转场动画（去掉背屏切换界面的动画）
        overridePendingTransition(0, 0);
        // 与车控一致：首帧即透明，避免主屏占位迁屏期间闪出默认窗口/小窗
        try {
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        } catch (Exception ignored) {
        }
        
        // 检查是否是点击通知结束投屏
        if (getIntent() != null && "ACTION_STOP_PROJECTION".equals(getIntent().getAction())) {
            LogHelper.d(TAG, "🛑 onCreate中收到结束投屏请求，立即销毁Activity");
            try {
                // 固定黑底，避免销毁时闪白
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                // 立即隐藏窗口，避免显示内容
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                
                // 统一防闪白退出流程：淡出->隐藏->恢复->销毁
                finishProjectionFromUser("onCreate-stop-intent");
                // 确保立即返回，不执行后续代码
                return;
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理结束投屏请求时发生异常", e);
                // 即使发生异常，也尝试清理资源并销毁Activity
                try {
                    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                    finishProjectionFromUser("onCreate-stop-intent-error");
                } catch (Exception e2) {
                    LogHelper.e(TAG, "❌ 销毁Activity时发生异常", e2);
                }
                return;
            }
        }

        // 兜底校验：防止磁贴/外部组件直接 am start 该 Activity 从而绕过 MainActivity 的激活门禁。
        if (!OfflineActivationRepository.INSTANCE.isActivated(this)) {
            try {
                MainDisplayUi.showToast(
                    this,
                    getString(R.string.activation_required_to_use),
                    android.widget.Toast.LENGTH_SHORT
                );
            } catch (Throwable ignored) {
            }
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            startActivity(i);
            finishProjectionTask();
            return;
        }

        registerLyricsProjectionBackPressedCallback();
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        RootTaskServiceConnector.prewarm(this);
        try {
            SuperLyricApi.init();
        } catch (Throwable t) {
            LogHelper.w(TAG, "SuperLyricApi.init() failed: " + t.getMessage());
        }

        // <!-- 检查是否通过广播启动（通过Intent的extra参数判断） -->
        Intent intent = getIntent();
        if (intent != null) {
            boolean isBroadcast = intent.getBooleanExtra("isBroadcast", false);
            setBroadcastStarted(isBroadcast);
            if (isBroadcast) {
                LogHelper.d(TAG, "📻 检测到通过广播启动的音乐投屏，不受自动投屏开关影响");
            }
            // 旧版磁贴曾带 isMainScreenLandscape；主屏歌词现由 MainScreenMusicActivity 独立承载
            if (intent.getBooleanExtra("isMainScreenLandscape", false)) {
                redirectLegacyMainScreenLaunchAndFinish();
                return;
            }
        } else {
            // 如果没有Intent，默认不是广播启动
            setBroadcastStarted(false);
        }
        
        // 保存静态实例
        currentInstance = this;
        LogHelper.d(TAG, "✅ 静态实例已保存: " + this);
        
        // 判断当前所在的屏幕
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displayId = getDisplay().getDisplayId();
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }
        LogHelper.d(TAG, "📍 onCreate时displayId=" + displayId);

        // 背屏：正常歌词投屏（唯一允许展示 UI 的显示屏）
        if (displayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            initialDisplayId = displayId;
            LogHelper.d(TAG, "🎯 在背屏执行，开始设置歌词显示");
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);

            setupWindow();

            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            getWindow().setAttributes(params);

            try {
                loadSettings();
            } catch (Throwable t) {
                LogHelper.e(TAG, "loadSettings failed, using defaults", t);
            }
            createUI();
            initLyricsAnimator();
            setupMediaController();
            try {
                updateMediaInfo();
            } catch (Throwable t) {
                LogHelper.w(TAG, "updateMediaInfo failed", t);
            }
            updatePlaybackState();
            registerActiveSessionsChangedListener();

            startMusicProjectionWakeService();
            bindTaskService();
            registerScreenReceiver();
            registerKeepScreenOnPreferenceListener();
            mediaSessionInitializedThisCycle = true;
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != 0 && displayId != 1) {
            LogHelper.w(TAG, "非背屏入口落在非常规显示屏(displayId=" + displayId + ")，销毁");
            finishIllegalProjectionSurface();
            return;
        }

        // 主屏 display 0：仅透明占位，不创建 UI，等待迁往背屏（与车控一致）
        LogHelper.d(TAG, "💤 在主屏占位等待迁往背屏（最长 " + MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS + "ms）");
        RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(
                this, RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER);
        scheduleMainScreenPlaceholderTimeout();
        schedulePollRearLyricsUiInitAfterMove();
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        return;
    }

    /**
     * 注册背屏常亮开关状态变化监听
     */
    private void registerKeepScreenOnPreferenceListener() {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            keepScreenOnPreferenceListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if ("flutter.keep_screen_on_enabled".equals(key)) {
                        LogHelper.d(TAG, "🔆 背屏常亮(Flutter)偏好变化 -> 同步 Wake 注册");
                        uiHandler.post(RearScreenLyricsActivity.this::applyMusicProjectionKeepScreenWakeFromPrefs);
                    }
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(keepScreenOnPreferenceListener);
            LogHelper.d(TAG, "✅ 已注册背屏常亮开关状态变化监听");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 注册背屏常亮开关状态变化监听失败", e);
            keepScreenOnPreferenceListener = null;
        }
    }

    private void registerRearAssistProximityPreferenceListener() {
        if (rearAssistProximityPrefsListener != null) {
            return;
        }
        try {
            SharedPreferences prefs = RearAssistPrefs.INSTANCE.prefs(this);
            rearAssistProximityPrefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (RearAssistPrefs.KEY_PROXIMITY.equals(key)) {
                        uiHandler.post(RearScreenLyricsActivity.this::updateProjectionProximitySession);
                    } else if (RearAssistPrefs.KEY_KEEP_SCREEN_ON.equals(key)) {
                        LogHelper.d(TAG, "🔆 背屏常亮(miroot_rear_assist)变化 -> 同步 Wake 注册");
                        uiHandler.post(RearScreenLyricsActivity.this::applyMusicProjectionKeepScreenWakeFromPrefs);
                    }
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(rearAssistProximityPrefsListener);
            LogHelper.d(TAG, "✅ 已注册背屏遮盖偏好监听");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 注册背屏遮盖偏好监听失败", e);
            rearAssistProximityPrefsListener = null;
        }
    }

    private void updateProjectionProximitySession() {
        if (!RearAssistPrefs.INSTANCE.isProximityEnabled(this)) {
            if (projectionProximitySession != null) {
                projectionProximitySession.detach();
            }
            return;
        }
        if (isMainScreenLandscapeMode) {
            if (projectionProximitySession != null) {
                projectionProximitySession.detach();
            }
            return;
        }
        int displayId = getDisplayIdSafe();
        if (displayId != 1 || !hasLyricsView() || isFinishing()) {
            if (projectionProximitySession != null) {
                projectionProximitySession.detach();
            }
            return;
        }
        if (projectionProximitySession == null) {
            projectionProximitySession = new RearProjectionProximitySession(this);
        }
        projectionProximitySession.attach();
    }

    private void releaseProjectionProximitySession() {
        if (projectionProximitySession != null) {
            projectionProximitySession.releaseExecutor();
            projectionProximitySession = null;
        }
    }

    /**
     * 设置窗口属性（统一管理窗口flags）
     */
    private void setupWindow() {
        RearMirootProjectionLifecycle.restoreProjectionWindowVisible(this);
        RearMirootProjectionLifecycle.applyRearOpaqueWindowBase(this, 0xFF000000);

        // 音乐投屏时保持常亮（与功能页「投屏常亮」一致）
        boolean keepScreenOnEnabled = isProjectionKeepScreenOnEnabled();

        if (keepScreenOnEnabled) {
            // 保持常亮 + 锁屏显示 + 点亮屏幕
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
            LogHelper.d(TAG, "✅ 音乐投屏已启动，屏幕保持常亮（用户设置：开启）");
        } else {
            // 只设置锁屏显示和点亮屏幕，不保持常亮
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
            // 确保清除常亮标志（如果之前设置了）
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            LogHelper.d(TAG, "✅ 音乐投屏已启动，屏幕不保持常亮（用户设置：关闭）");
        }
        
        // 适配新API：锁屏时显示 + 点亮屏幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        
        // 优化渲染性能（参考充电动画，解决DequeueBuffer超时）
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        
        // 让内容始终延伸到摄像头区域（Display Cutout，参考充电动画）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(params);
            LogHelper.d(TAG, "✅ 已设置窗口延伸至刘海区域");
        }
    }
    
    /**
     * 注册锁屏监听广播（包括屏幕关闭事件，防止双击息屏）
     */
    private void registerScreenReceiver() {
        screenReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                // 检查是否在背屏
                int displayId = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        displayId = getDisplay().getDisplayId();
                    } catch (Exception e) {
                        // 忽略错误
                    }
                }
                
                if (displayId != 1) {
                    return; // 不在背屏，不处理
                }
                
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // 屏幕关闭（双击息屏等）- 立即重新点亮屏幕（与未投屏时常亮实现方式一致）
                    LogHelper.d(TAG, "📱 检测到屏幕关闭（双击息屏），立即重新点亮屏幕");
                    uiHandler.post(() -> {
                        try {
                            pauseRearStopExitGraceForScreenOff();
                            if (!isFinishing() && !isDestroyed() && getWindow() != null) {
                                boolean keepScreenOnEnabled = isProjectionKeepScreenOnEnabled();

                                // 将变量声明为final，以便在内部lambda中使用
                                final boolean keepScreenOnEnabledFinal = keepScreenOnEnabled;
                                
                                if (keepScreenOnEnabledFinal) {
                                    // 立即重新应用常亮flags（与未投屏时常亮实现方式一致）
                                    getWindow().addFlags(
                                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                    );
                                    
                                    // 适配新API：确保flags持续生效
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                        setShowWhenLocked(true);
                                        setTurnScreenOn(true);
                                    }
                                    
                                    // 立即发送唤醒命令（与未投屏时常亮实现方式一致）
                                    if (taskService != null) {
                                        try {
                                            taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                            LogHelper.d(TAG, "✅ 已发送唤醒命令（双击息屏后）");
                                        } catch (Exception e) {
                                            LogHelper.w(TAG, "发送唤醒命令失败", e);
                                        }
                                    }
                                    
                                    // 延迟再次发送唤醒命令，确保屏幕能够重新点亮（与未投屏时常亮实现方式一致）
                                    if (screenOffWakeRetryRunnable != null) {
                                        uiHandler.removeCallbacks(screenOffWakeRetryRunnable);
                                    }
                                    screenOffWakeRetryRunnable = () -> {
                                        if (!isFinishing() && !isDestroyed() && getWindow() != null && keepScreenOnEnabledFinal) {
                                            try {
                                                // 再次重新应用常亮flags
                                                getWindow().addFlags(
                                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                                                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                                                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                                );
                                                
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                                    setShowWhenLocked(true);
                                                    setTurnScreenOn(true);
                                                }
                                                
                                                // 再次发送唤醒命令
                                                if (taskService != null) {
                                                    try {
                                                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                                        LogHelper.d(TAG, "✅ 已再次发送唤醒命令（双击息屏后延迟）");
                                                    } catch (Exception e) {
                                                        LogHelper.w(TAG, "再次发送唤醒命令失败", e);
                                                    }
                                                }
                                            } catch (Exception e) {
                                                LogHelper.w(TAG, "延迟处理屏幕关闭事件失败", e);
                                            }
                                        }
                                    };
                                    uiHandler.postDelayed(screenOffWakeRetryRunnable, 100); // 100ms延迟
                                    
                                    LogHelper.d(TAG, "✅ 已重新应用常亮flags（双击息屏后）");
                                    ensureProjectionWakeServiceAfterScreenOff();
                                }
                            }
                        } catch (Exception e) {
                            LogHelper.e(TAG, "处理屏幕关闭事件失败", e);
                        }
                    });
                } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    // 屏幕点亮或用户解锁 - 立即重新应用常亮flags（与未投屏时常亮实现方式一致）
                    LogHelper.d(TAG, "📱 屏幕点亮/解锁，立即重新应用常亮flags");
                    uiHandler.post(() -> {
                        try {
                            handleProjectionForegroundAfterStopGrace();
                            if (!isFinishing() && !isDestroyed() && getWindow() != null) {
                                // 检查背屏常亮开关（与功能页一致）
                                boolean keepScreenOnEnabled = isProjectionKeepScreenOnEnabled();

                                if (keepScreenOnEnabled) {
                                    // 立即重新应用常亮flags（与未投屏时常亮实现方式一致）
                                    getWindow().addFlags(
                                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                    );
                                    
                                    // 适配新API：确保flags持续生效
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                        setShowWhenLocked(true);
                                        setTurnScreenOn(true);
                                    }
                                    
                                    // 立即发送唤醒命令（确保屏幕保持点亮）
                                    if (taskService != null) {
                                        try {
                                            taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                            LogHelper.d(TAG, "✅ 已发送唤醒命令（屏幕点亮/解锁后）");
                                        } catch (Exception e) {
                                            LogHelper.w(TAG, "发送唤醒命令失败", e);
                                        }
                                    }
                                    
                                    LogHelper.d(TAG, "✅ 已重新应用常亮flags（屏幕点亮/解锁后）");
                                }
                                
                                // 不主动全量 invalidate：歌词/动画链路会按需刷新，避免屏幕点亮时触发整帧重绘峰值。
                            }
                        } catch (Exception e) {
                            LogHelper.e(TAG, "恢复显示失败", e);
                        }
                    });
                }
            }
        };
        
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); // 添加屏幕关闭监听
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);
        LogHelper.d(TAG, "✅ 已注册锁屏监听（包括屏幕关闭事件）");

        LogHelper.d(TAG, "ℹ 旧 Hook 歌词广播通道已停用，改用 SuperLyricApi 优先拉取");
    }
    
    /**
     * 创建UI布局
     */
    private void createUI() {
        try {
            LogHelper.d(TAG, "🎨 开始创建UI布局");
            // 预填充共享同步颜色，确保首帧歌词和跑马灯颜色一致
            LyricsProjectionColorSync.prefetch(colorChangeIntervalMs);
            // 深渊镜：尽早 setDecorFitsSystemWindows(false)，确保首帧布局即全屏铺满（避免 content 被 insets 内缩）
            if (abyssalMirrorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                LogHelper.d(TAG, "✅ 深渊镜：已尽早设置 setDecorFitsSystemWindows(false)，确保首帧全屏");
            }
            // 使用FrameLayout作为根布局，以便跑马灯可以覆盖在最上层
            android.widget.FrameLayout frameLayout = new android.widget.FrameLayout(this);
            mainFrameLayout = frameLayout;
            frameLayout.setBackgroundColor(0xFF000000); // 纯黑色背景
            frameLayout.setClipToPadding(false);
            frameLayout.setClipChildren(false);
            applyRenderFrameRateBudget(frameLayout);
            albumArtBackgroundView = new ImageView(this);
            // 专辑图背景不固定分辨率（如 976x596）；始终按当前设备背屏实际尺寸铺满，
            // 保持 CENTER_CROP + 居中：无黑边、无拉伸、等比裁剪填充。
            albumArtBackgroundView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            albumArtBackgroundView.setAdjustViewBounds(false);
            albumArtBackgroundView.setCropToPadding(false);
            albumArtBackgroundView.setAlpha(albumArtAlphaPercent / 100f);
            setVisibilityIfChanged(albumArtBackgroundView, View.GONE);
            android.widget.FrameLayout.LayoutParams albumBgParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            );
            albumBgParams.gravity = android.view.Gravity.CENTER;
            frameLayout.addView(
                albumArtBackgroundView,
                albumBgParams
            );
            if (abyssalMirrorEnabled) {
                frameLayout.setPadding(0, 0, 0, 0);
                frameLayout.setFitsSystemWindows(false); // 深渊镜：左右铺满，不消费 insets
            }
            LogHelper.d(TAG, "✅ FrameLayout创建成功");
        
        // 内容布局（原有的LinearLayout）
        LinearLayout rootLayout = new LinearLayout(this);
        contentRootLayout = rootLayout;
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFF000000); // 纯黑色背景
        applyRenderFrameRateBudget(rootLayout);
        rootLayout.setPadding(0, 0, 0, 0);
        rootLayout.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        
        // 顶部标题区域（歌词和歌手合并为一个整体）
        // 使用单个TextView显示歌名和歌手，支持滚动显示
        songTitleText = new TextView(this);
        setTextIfChanged(songTitleText, "未检测到音乐播放");
        songTitleText.setTextColor(0x99FFFFFF); // 浅灰色（60%透明度）
        // 主屏横屏模式：字体大小 x1.3（14 -> 18.2）
        float songTitleTextSize = isMainScreenLandscapeMode ? 14f * 1.3f : 14f;
        songTitleText.setTextSize(songTitleTextSize);
        songTitleText.setTypeface(null, android.graphics.Typeface.NORMAL);
        songTitleText.setGravity(android.view.Gravity.CENTER | android.view.Gravity.BOTTOM); // 水平居中，垂直底部对齐
        songTitleText.setPadding(0, 8, songTitleRightPaddingPx(), 15);
        
        // 启用滚动显示（marquee）
        songTitleText.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        songTitleText.setMarqueeRepeatLimit(-1); // 无限滚动
        songTitleText.setSingleLine(true);
        songTitleText.setSelected(true); // 必须设置为selected才能滚动
        
        // 歌名区域统一手势：单击显隐按钮、长按开关跑马灯、左右滑切歌（互斥处理，避免冲突）
        final int titleSwipeMinDistancePx = dp(60);
        final int titleSwipeMinVelocityPx = dp(36);
        final float titleSwipeDirectionRatio = 1.35f;
        final GestureDetector titleGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleButtonsVisibility();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                toggleMarqueeLight();
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);
                if (absDx < titleSwipeMinDistancePx) return false;
                if (absDx <= absDy * titleSwipeDirectionRatio) return false;
                if (Math.abs(velocityX) < titleSwipeMinVelocityPx || Math.abs(velocityX) <= Math.abs(velocityY)) {
                    return false;
                }
                // 左滑(→)下一首，右滑(←)上一首，与深渊镜手势方向一致
                if (dx < 0) {
                    executeMediaSkip(true, "歌名区左滑下一首");
                } else {
                    executeMediaSkip(false, "歌名区右滑上一首");
                }
                return true;
            }
        });
        // 计算顶部距离：跑马灯宽度 + 2px
        int topMargin = (int) (marqueeLightSize + dp(2));
        int leftMargin = dp(20);
        int rightMargin = dp(20);
        
        // 设置标题区域布局参数：宽度填满，左边距100px，右边距20px，顶部距离跟随跑马灯宽度，内容水平居中
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,  // 宽度填满
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        titleParams.setMargins(leftMargin, topMargin, rightMargin, 0);
        songTitleLayoutParams = titleParams;
        rootLayout.addView(songTitleText, songTitleLayoutParams);
        if (BuildConfig.DEBUG) {
            hookSourceStatusText = new TextView(this);
            hookSourceStatusText.setTextColor(0x80FFFFFF);
            hookSourceStatusText.setTextSize(isMainScreenLandscapeMode ? 11f * 1.3f : 11f);
            hookSourceStatusText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            hookSourceStatusText.setPadding(0, 0, 0, 10);
            updateHookSourceStatusText(LyricsRuntimeSource.IDLE);
            LinearLayout.LayoutParams hookStatusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            hookStatusParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            hookStatusParams.setMargins(leftMargin, 0, rightMargin, 0);
            hookSourceStatusLayoutParams = hookStatusParams;
            rootLayout.addView(hookSourceStatusText, hookSourceStatusLayoutParams);
        }
        
        LogHelper.d(TAG, "✅ 歌曲名称控件设置: 宽度=填满, 左边距=" + leftMargin + "px, 右边距=" + rightMargin + "px, 顶部距离=" + topMargin + "px (跑马灯宽度" + marqueeLightSize + "px + 2dp), 内容水平居中");
        
        // artistText保留用于内部使用（但不在UI中显示）
        artistText = new TextView(this);
        setTextIfChanged(artistText, "");
        setVisibilityIfChanged(artistText, View.GONE); // 隐藏，不在UI中显示
        
        // 根据设置选择显示模式
        if (abyssalMirrorEnabled) {
            // 深渊镜模式：隐藏歌词名称，全屏显示
            if (songTitleText != null) {
                setVisibilityIfChanged(songTitleText, View.GONE);
                setVisibilityIfChanged(gestureTitleLayer, View.GONE);
                LogHelper.d(TAG, "✅ 深渊镜模式：已隐藏歌词名称");
            }
            
            // 深渊镜模式（使用新的ViewGroup版本，多层边框效果）
            // 广播启动时通过构造函数传入固定圆角，在 init() 里最先设置，避免与 onSizeChanged 竞态导致圆角有时对有时错
            try {
                abyssalMirrorLyricsViewGroup = new AbyssalMirrorLyricsViewGroup(this, shouldUseFixedRearProjectionCornerRadius());
                // 设置是否为主屏横屏模式（用于调整圆角半径）
                abyssalMirrorLyricsViewGroup.setMainScreenLandscapeMode(isMainScreenLandscapeMode);
                // 使用调整后的字体大小（主屏横屏模式已x2）
                abyssalMirrorLyricsViewGroup.setTextSize(currentTextSize);
                abyssalMirrorLyricsViewGroup.setTextColor(randomColorSwitchEnabled ? randomHighSaturationColor() : fixedColor);
                abyssalMirrorLyricsViewGroup.setFitsSystemWindows(false); // 不避系统栏，真正全屏
                abyssalMirrorLyricsViewGroup.setGyroSensitivityMultiplier(abyssalGyroSensitivity);
                abyssalMirrorLyricsViewGroup.setMovableRangeMultiplier(abyssalMovableRange);
                abyssalMirrorLyricsViewGroup.setColorChangeIntervalMs(colorChangeIntervalMs);
                abyssalMirrorLyricsViewGroup.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
                abyssalMirrorLyricsViewGroup.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
                abyssalMirrorLyricsViewGroup.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
                abyssalMirrorLyricsViewGroup.setLyricsTypeface(LyricsFontHelper.resolveTypeface(this, projectionLyricsFontId, projectionLyricsCustomPath));
                // 深渊镜不加入 rootLayout，在后面直接加入 frameLayout（MATCH_PARENT），避免在上层被约束导致不全屏
                
                // 如果已有歌词数据，立即设置到视图
                if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
                    // 获取当前行的歌词文本
                    int currentIndex = findCurrentLineIndexForAbyssal(enhancedLyricLines, 0);
                    if (currentIndex >= 0 && currentIndex < enhancedLyricLines.size()) {
                        String currentLyric = enhancedLyricLines.get(currentIndex).text;
                        abyssalMirrorLyricsViewGroup.setLyric(currentLyric);
                        LogHelper.d(TAG, "✅ 已设置歌词数据到深渊镜视图: " + enhancedLyricLines.size() + " 行，当前行=" + currentIndex);
                    } else {
                        abyssalMirrorLyricsViewGroup.setLyric("未获取到歌词");
                    }
                } else {
                    abyssalMirrorLyricsViewGroup.setLyric("未获取到歌词");
                }
                
                LogHelper.d(TAG, "✅ 已创建深渊镜歌词视图（ViewGroup版本，多层边框效果）");
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 创建深渊镜歌词视图失败，回退到普通模式", e);
                // 回退到普通模式
                abyssalMirrorEnabled = false;
                // 恢复歌词名称显示
                if (songTitleText != null) {
                    setVisibilityIfChanged(songTitleText, View.VISIBLE);
                    setVisibilityIfChanged(gestureTitleLayer, View.VISIBLE);
                }
                // 继续执行普通模式的创建
            }
            // 方案1：深渊镜手势上一首/下一首回调和开关（与 prevButton/nextButton 逻辑一致）
            if (abyssalMirrorLyricsViewGroup != null) {
                abyssalMirrorLyricsViewGroup.setOnPrevNextGestureListener(new AbyssalMirrorLyricsViewGroup.OnPrevNextGestureListener() {
                    @Override
                    public void onPrevious() {
                        if (!checkNotificationListenerPermission()) {
                            MainDisplayUi.showToast(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG);
                            openNotificationListenerSettings();
                            return;
                        }
                        // 与 prevButton 一致：勿在已有 controller 时重复 setup（酷我直连 KwMediaSession 为异步，重复 setup 会先清空导致此处恒为 null）
                        if (mediaController == null) {
                            setupMediaController();
                            if (mediaController == null) {
                                MainDisplayUi.showToast(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT);
                                return;
                            }
                        }
                        android.media.session.MediaController.TransportControls controls = mediaController.getTransportControls();
                        if (controls != null) {
                            try { controls.skipToPrevious(); } catch (Exception e) { LogHelper.e(TAG, "手势上一首失败", e); }
                        }
                    }
                    @Override
                    public void onNext() {
                        if (!checkNotificationListenerPermission()) {
                            MainDisplayUi.showToast(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG);
                            openNotificationListenerSettings();
                            return;
                        }
                        if (mediaController == null) {
                            setupMediaController();
                            if (mediaController == null) {
                                MainDisplayUi.showToast(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT);
                                return;
                            }
                        }
                        android.media.session.MediaController.TransportControls controls = mediaController.getTransportControls();
                        if (controls != null) {
                            try { controls.skipToNext(); } catch (Exception e) { LogHelper.e(TAG, "手势下一首失败", e); }
                        }
                    }
                });
                abyssalMirrorLyricsViewGroup.setEnableGesture(gestureControlEnabled);
            }
        }
        
        android.util.Log.wtf("MIR", "lyrics-setup abyssal=" + abyssalMirrorEnabled);
        System.out.println("MIR-LYRIC-SETUP abyssalMirrorEnabled=" + abyssalMirrorEnabled);
        if (!abyssalMirrorEnabled) {
            // 普通模式
            lyricsView = new ModernLyricsView(this);
            applyRenderFrameRateBudget(lyricsView);
            lyricsView.setShowTranslation(true);     // 显示翻译
            lyricsView.setEnableWordByWord(effectiveWordByWordForSuperLyric(null));
            lyricsView.setShowProgress(false);       // 不显示进度条
            lyricsView.setEnableGesture(gestureControlEnabled);  // 使用保存的手势控制设置
            lyricsView.setTextSize(currentTextSize);  // 使用保存的字体大小（主屏横屏模式已x2）
            lyricsView.setBreathingRhythmMs(breathingBpmToRhythmMs(breathingBpm));
            lyricsView.setBreathingScaleVariance(
                (powerSavingModeEnabled || !breathingEnabled) ? 0f : breathingScaleVariance
            );
            lyricsView.setBreathingDisplacementStrength(
                (powerSavingModeEnabled || !breathingEnabled) ? 0f : breathingDisplacementStrength
            );
            // 与 SharedPreferences 中 breathingEnabled 对齐：未调用则 ModernLyricsView 默认不启动呼吸 ValueAnimator
            lyricsView.setBreathingScaleEnabled(breathingEnabled && !powerSavingModeEnabled);
            lyricsView.setColorChangeIntervalMs(colorChangeIntervalMs);
            lyricsView.setShuffleLayoutRebuildIntervalMs(0L);

            // 主屏横屏模式：调大行间距（1.5倍，因为主屏幕可用分辨率大）
            if (isMainScreenLandscapeMode) {
                float defaultLineSpacing = 160f; // 默认行间距
                float mainScreenLineSpacing = defaultLineSpacing * 1.5f; // 主屏横屏模式：1.5倍
                lyricsView.setLineSpacing(mainScreenLineSpacing);
                LogHelper.d(TAG, "🖥️ 主屏横屏模式：行间距调整为 " + mainScreenLineSpacing + "px (默认值x1.5)");
            }
            
            lyricsView.setBackgroundTextureSize(backgroundTextureSize);  // 使用保存的底图字体大小倍数（跟随字体大小）
            lyricsView.setShowBackgroundTexture(backgroundTextureEnabled);  // 使用保存的底图显示设置
            
            // 歌词颜色由调试开关控制：随机节奏变色或固定高可读黑白配色；跑马灯/边框跟随歌词当前色。
            lyricsView.setColorSyncEnabled(true);
            lyricsView.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
            lyricsView.setNeonLyricsEnabled(neonDisplayEnabled);
            lyricsView.setLyricsFont(projectionLyricsFontId, projectionLyricsCustomPath);
            lyricsView.setTimeAdjustOffset(projectionSyncOffsetMs);
            
            // 设置歌词视图布局参数（占据剩余空间）
            LinearLayout.LayoutParams lyricsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f  // 占据剩余空间
            );
            lyricsView.setLayoutParams(lyricsParams);
            lyricsView.setLyricsHorizontalInsetPx(isMainScreenLandscapeMode ? 0 : REAR_LYRICS_LEFT_CLEAR_PX);

            // 设置歌词行点击监听器（点击行可跳转）
            lyricsView.setOnLyricLineClickListener(new ModernLyricsView.OnLyricLineClickListener() {
                @Override
                public void onLyricLineClick(int lineIndex) {
                    if (!checkNotificationListenerPermission()) {
                        MainDisplayUi.showToast(getApplicationContext(), "需要通知监听权限才能跳转进度", android.widget.Toast.LENGTH_LONG);
                        openNotificationListenerSettings();
                        return;
                    }
                    if (mediaController == null) {
                        setupMediaController();
                        if (mediaController == null) {
                            MainDisplayUi.showToast(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT);
                            return;
                        }
                    }
                    if (lineIndex >= 0 && lineIndex < enhancedLyricLines.size()) {
                        long targetTime = enhancedLyricLines.get(lineIndex).time;
                        try {
                            android.media.session.MediaController.TransportControls controls =
                                    mediaController.getTransportControls();
                            if (controls != null) {
                                controls.seekTo(targetTime);
                            }
                        } catch (Exception e) {
                            LogHelper.e(TAG, "点击歌词跳转进度失败", e);
                        }
                    }
                }
            });
            
            rootLayout.addView(lyricsView);

                // 歌词、边框、跑马灯共用同一配色源，主背屏同时显示时保持一致
                LyricsProjectionColorSync.bindLyricsView(lyricsView, randomColorSwitchEnabled, colorChangeIntervalMs);

            LogHelper.d(TAG, "✅ 已创建普通歌词视图");

            // 酷我车载广播桥：监听 LYRIC_FULL / LYRIC_PROGRESS（参考酷我移植参考文档.md §9）
            try {
                android.util.Log.wtf("MIR", "kuwo-bridge-start");
                if (kuwoBroadcastLyricBridge == null) {
                    kuwoBroadcastLyricBridge = new KuwoBroadcastLyricBridge(this, lyricsView);
                }
                kuwoBroadcastLyricBridge.start();
                android.util.Log.wtf("MIR", "kuwo-bridge-started");
            } catch (Exception e) {
                android.util.Log.wtf("MIR", "kuwo-bridge-fail: " + e.getMessage());
            }
        }

        // 控制按钮容器：左侧与歌词区一致的背屏留白，三键在条内均分
        buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(android.view.Gravity.CENTER);
        buttonLayout.setPadding(dp(22), dp(14), dp(22), dp(14));
        setVisibilityIfChanged(buttonLayout, View.GONE);
        buttonsVisible = false;

        int btnGap = dp(8);
        final int compactBtnWidth = dp(44);
        final int compactBtnHeight = dp(34);

        // 上一首按钮
        prevButton = new Button(this);
        prevButton.setText("⏮");
        android.graphics.drawable.GradientDrawable prevBg = new android.graphics.drawable.GradientDrawable();
        styleMediaTransportButton(prevButton, prevBg);
        LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(
            compactBtnWidth,
            compactBtnHeight
        );
        prevParams.setMarginEnd(btnGap / 2);
        prevButton.setLayoutParams(prevParams);
        prevButton.setOnClickListener(v -> {
            executeMediaSkip(false, "发送上一首命令");
        });
        buttonLayout.addView(prevButton);
        
        // 播放/暂停按钮
        playPauseButton = new Button(this);
        playPauseButton.setText("▶");
        android.graphics.drawable.GradientDrawable playBg = new android.graphics.drawable.GradientDrawable();
        styleMediaTransportButton(playPauseButton, playBg);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
            compactBtnWidth,
            compactBtnHeight
        );
        playParams.setMarginStart(btnGap / 2);
        playParams.setMarginEnd(btnGap / 2);
        playPauseButton.setLayoutParams(playParams);
        playPauseButton.setOnClickListener(v -> {
            if (!checkNotificationListenerPermission()) {
                MainDisplayUi.showToast(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG);
                openNotificationListenerSettings();
                return;
            }
            if (mediaController == null) {
                setupMediaController();
                if (mediaController == null) {
                    MainDisplayUi.showToast(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT);
                    return;
                }
            }
            android.media.session.MediaController.TransportControls controls = mediaController.getTransportControls();
            if (controls != null) {
                try {
                    android.media.session.PlaybackState playbackState = mediaController.getPlaybackState();
                    boolean isPlaying = playbackState != null && 
                        playbackState.getState() == android.media.session.PlaybackState.STATE_PLAYING;
                    if (isPlaying) {
                        controls.pause();
                    } else {
                        controls.play();
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "发送播放/暂停命令失败", e);
                }
            }
        });
        buttonLayout.addView(playPauseButton);
        
        // 下一首按钮
        nextButton = new Button(this);
        nextButton.setText("⏭");
        android.graphics.drawable.GradientDrawable nextBg = new android.graphics.drawable.GradientDrawable();
        styleMediaTransportButton(nextButton, nextBg);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
            compactBtnWidth,
            compactBtnHeight
        );
        nextParams.setMarginStart(btnGap / 2);
        nextButton.setLayoutParams(nextParams);
        nextButton.setOnClickListener(v -> {
            executeMediaSkip(true, "发送下一首命令");
        });
        buttonLayout.addView(nextButton);
        
        // 将内容布局或深渊镜视图添加到FrameLayout
        android.widget.FrameLayout.LayoutParams contentParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        );
        if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
            // 深渊镜：直接加入 frameLayout 作为底层，绕过 rootLayout，避免宽度/高度在上层被约束导致不全屏
            frameLayout.addView(abyssalMirrorLyricsViewGroup, contentParams);
            LogHelper.d(TAG, "✅ 深渊镜歌词视图已直接添加到FrameLayout（全屏铺满）");
        } else {
            frameLayout.addView(rootLayout, contentParams);
            LogHelper.d(TAG, "✅ 内容布局已添加到FrameLayout");
        }

        mediaButtonBarLayoutParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        mediaButtonBarLayoutParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        frameLayout.addView(buttonLayout, mediaButtonBarLayoutParams);
        LogHelper.d(TAG, "✅ 控制按钮条已加入根布局（边距由 applyMediaButtonBarInsets 更新）");

        
        // 添加跑马灯视图（放在最上层，但设置不拦截触摸事件）
        // 广播启动时通过构造函数传入固定圆角，避免与 onSizeChanged 竞态导致圆角有时对有时错
        try {
            marqueeLightView = new MarqueeLightView(this, shouldUseFixedRearProjectionCornerRadius());
            // 设置是否为主屏横屏模式（用于重新检测圆角半径）
            marqueeLightView.setMainScreenLandscapeMode(isMainScreenLandscapeMode);
            android.widget.FrameLayout.LayoutParams marqueeParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            );
            frameLayout.addView(marqueeLightView, marqueeParams);
            // 深渊镜模式下隐藏跑马灯/霓虹 overlay，让深渊镜全屏贴边；否则按设置显示
            boolean shouldBeVisible = !abyssalMirrorEnabled && (marqueeLightEnabled || neonBorderEnabled);
            marqueeLightView.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE);
            marqueeLightView.setBorderFrameEnabled(neonBorderEnabled);
            marqueeLightView.setNeonEffectsEnabled(neonDisplayEnabled);
            marqueeLightView.setMarqueeLightEnabled(marqueeLightEnabled);  // 设置跑马灯是否启用
            marqueeLightView.setLightSize(marqueeLightSize);  // 设置跑马灯线条宽度（主屏横屏模式已x2.3）
            marqueeLightView.setAnimationDuration(marqueeLightDurationMs);
            marqueeLightView.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
            marqueeLightView.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
            marqueeLightView.setColorChangeIntervalMs(colorChangeIntervalMs);
            marqueeLightView.setClickable(false);
            marqueeLightView.setFocusable(false);
            
            // 建立颜色联动：跑马灯和霓虹灯边框与歌词高亮同色
            if (marqueeLightView != null) {
                LyricsProjectionColorSync.bindMarqueeLight(marqueeLightView, randomColorSwitchEnabled, colorChangeIntervalMs);
                LogHelper.d(TAG, "✅ 跑马灯和霓虹灯边框颜色已设置为双屏联动共享颜色");
            }
            
            LogHelper.d(TAG, "✅ 跑马灯视图已添加，跑马灯: " + (marqueeLightEnabled ? "启用" : "禁用") + 
                      ", 线条宽度=" + marqueeLightSize + "px, 霓虹效果=" + neonDisplayEnabled + ", 边框显示=" + neonBorderEnabled +
                      ", 视图可见: " + shouldBeVisible);
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 创建跑马灯视图失败", e);
            // 跑马灯失败不影响主界面显示
        }

        // 顶层透明手势层：放在跑马灯之上，避免边框动画区域影响歌名手势识别
        gestureTitleLayer = new android.widget.FrameLayout(this);
        gestureTitleLayer.setBackgroundColor(0x00000000);
        gestureTitleLayer.setClickable(true);
        gestureTitleLayer.setFocusable(false);
        gestureTitleLayer.setOnTouchListener((v, event) -> titleGestureDetector.onTouchEvent(event));
        final int gestureExtraTop = dp(GESTURE_TITLE_LAYER_EXTRA_TOP_DP);
        int titleTouchBaseHeight = dp(GESTURE_TITLE_LAYER_BASE_HEIGHT_DP);
        if (songTitleText != null) {
            int measured = songTitleText.getMeasuredHeight();
            int current = songTitleText.getHeight();
            int raw = Math.max(measured, current);
            if (raw > 0) {
                titleTouchBaseHeight = raw;
            }
        }
        final int gestureExtraBottom = dp(GESTURE_TITLE_LAYER_EXTRA_BOTTOM_DP);
        final int gestureHeight = titleTouchBaseHeight + gestureExtraTop + gestureExtraBottom;
        final int gestureTopMargin = Math.max(0, topMargin - gestureExtraTop);
        gestureTitleLayerLayoutParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            gestureHeight
        );
        gestureTitleLayerLayoutParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        gestureTitleLayerLayoutParams.setMargins(leftMargin, gestureTopMargin, rightMargin, 0);
        frameLayout.addView(gestureTitleLayer, gestureTitleLayerLayoutParams);
        if (abyssalMirrorEnabled) {
            setVisibilityIfChanged(gestureTitleLayer, View.GONE);
        }

        setContentView(frameLayout);
        applyProjectionBackgroundMode();
        LogHelper.d(TAG, "✅ UI布局创建完成，已设置ContentView");
        
        // 应用安全区域（避开摄像头）
        applySafeAreaPadding();
        applyMediaButtonBarInsets();
        getWindow().getDecorView().post(this::applyMediaButtonBarInsets);
        
        // 首帧即应用 insets（内部按 displayId 区分主/背屏），再 post 一次以贴合 layout 稳定后
        hideSystemUI();
        getWindow().getDecorView().post(this::hideSystemUI);
        
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 创建UI布局时发生异常", e);
            // 即使出现异常，也显示一个基本的黑色背景，避免完全黑屏
            try {
                View errorView = new View(this);
                errorView.setBackgroundColor(0xFF000000);
                setContentView(errorView);
                LogHelper.d(TAG, "✅ 已设置错误占位视图");
                hideSystemUI();
                getWindow().getDecorView().post(this::hideSystemUI);
            } catch (Exception e2) {
                LogHelper.e(TAG, "❌ 设置错误占位视图也失败", e2);
            }
        }
    }
    
    /**
     * 初始化歌词动画器
     * 每次调用前先停止旧的动画器与位置同步，避免 onConfigurationChanged/onDisplayChanged/onResume 多次调用导致多个 LyricsAnimator 同时更新视图、歌词闪烁
     */
    private void initLyricsAnimator() {
        if (lyricsAnimator != null) {
            try {
                lyricsAnimator.stop();
                LogHelper.d(TAG, "✅ 已停止旧歌词动画器，避免重复");
            } catch (Throwable t) {
                LogHelper.w(TAG, "停止旧歌词动画器时异常: " + t.getMessage());
            }
            lyricsAnimator = null;
        }
        stopPositionSync();
        
        lyricsAnimator = new LyricsAnimator();
        lyricsAnimator.setOnUpdateListener(new LyricsAnimator.OnUpdateListener() {
            @Override
            public void onPositionUpdate(long position) {
                // 更新歌词视图（支持普通模式和深渊镜模式）
                updatePositionToView(position);
            }
            
            @Override
            public void onLineChanged(int lineIndex) {
                // 行变化时的处理
                LogHelper.d(TAG, "歌词行变化: " + lineIndex);
            }
            
            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                // 播放状态变化时的处理
                LogHelper.d(TAG, "播放状态变化: " + (isPlaying ? "播放" : "暂停"));
            }
        });
        lyricsAnimator.start();
        
        // 启动定期同步
        startPositionSync();
    }
    
    /**
     * 启动播放位置定期同步
     */
    private void startPositionSync() {
        if (positionSyncHandler == null) {
            positionSyncHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        
        if (positionSyncRunnable == null) {
            positionSyncRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        maybeRefreshMediaControllerForActivePlayer();
                        // 从MediaController同步播放位置
                        if (mediaController != null && mediaController.getPlaybackState() != null) {
                            android.media.session.PlaybackState state = mediaController.getPlaybackState();
                            int playState = state.getState();
                            
                            if (playState == android.media.session.PlaybackState.STATE_PLAYING) {
                                long position = state.getPosition();
                                long updateTime = state.getLastPositionUpdateTime();
                                
                                if (lyricsAnimator != null && position >= 0) {
                                    lyricsAnimator.calibratePosition(position, updateTime);
                                }
                                // 播放时每1000ms同步一次（降低频率提升性能）
                                if (positionSyncHandler != null) {
                                    positionSyncHandler.postDelayed(this, 1000);
                                }
                            } else {
                                // 暂停时降低同步频率到3秒，减少资源占用
                                if (positionSyncHandler != null) {
                                    positionSyncHandler.postDelayed(this, 3000);
                                }
                            }
                        } else {
                            // 没有MediaController时，降低同步频率到2秒
                            if (positionSyncHandler != null) {
                                positionSyncHandler.postDelayed(this, 2000);
                            }
                        }
                    } catch (Exception e) {
                        LogHelper.e(TAG, "同步播放位置失败", e);
                        // 出错时降低同步频率
                        if (positionSyncHandler != null) {
                            positionSyncHandler.postDelayed(this, 2000);
                        }
                    }
                }
            };
        }
        
        // 启动同步
        positionSyncHandler.post(positionSyncRunnable);
        LogHelper.d(TAG, "✅ 播放位置同步已启动");
    }
    
    /**
     * 停止播放位置同步
     */
    private void stopPositionSync() {
        if (positionSyncHandler != null && positionSyncRunnable != null) {
            positionSyncHandler.removeCallbacks(positionSyncRunnable);
            LogHelper.d(TAG, "⏹ 播放位置同步已停止");
        }
    }
    
    /**
     * 背屏常亮总开关（与 [RearAssistPrefs] / 功能页一致）开启时注册 {@link RearScreenWakeService}（合并通知）；关闭时仅显示「投屏」通知。
     */
    private void applyMusicProjectionKeepScreenWakeFromPrefs() {
        if (isMainScreenLandscapeMode) {
            LogHelper.d(TAG, "⏸️ 主屏横屏模式：不注册背屏前台服务");
            updateProjectionProximitySession();
            return;
        }
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displayId = getDisplay().getDisplayId();
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }
        boolean hasUI = hasLyricsView();
        boolean keepOn = RearAssistPrefs.INSTANCE.isKeepScreenOnEnabled(this);
        if (displayId == 1 && hasUI && !isFinishing()) {
            try {
                if (keepOn) {
                    ProjectionOnlyNotificationHelper.cancelMusic(this);
                    RearScreenWakeManager.getInstance().startWakeService(getApplicationContext(), RearScreenLyricsActivity.class);
                    RearScreenWakeService.requestNotificationRefresh(this);
                    LogHelper.d(TAG, "✅ 已注册音乐投屏前台服务（合并通知）");
                } else {
                    RearScreenWakeManager.getInstance().stopWakeService(getApplicationContext(), RearScreenLyricsActivity.class);
                    ProjectionOnlyNotificationHelper.showMusic(this);
                    LogHelper.d(TAG, "✅ 音乐投屏：仅显示通知（常亮关）");
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 注册背屏前台服务失败", e);
            }
        } else {
            LogHelper.d(TAG, "⏸️ 未注册背屏前台服务 (displayId=" + displayId + ", hasUI=" + hasUI + ", isFinishing=" + isFinishing() + ")");
        }
        if (displayId == 1 && hasUI && !isFinishing() && !isDestroyed()) {
            setupWindow();
        }
        updateProjectionProximitySession();
    }

    /**
     * 背屏常亮总开关开启时注册 {@link RearScreenWakeService}（合并通知）；关闭时仅显示「投屏」通知。
     */
    private void startMusicProjectionWakeService() {
        registerRearAssistProximityPreferenceListener();
        applyMusicProjectionKeepScreenWakeFromPrefs();
    }
    
    /**
     * 停止背屏常亮服务（使用统一的RearScreenWakeManager）
     * @param skipProximityUpdate 为 true 时不刷新投屏遮盖会话（退出清理路径，避免销毁前短暂挂传感器）
     */
    private void stopMusicProjectionWakeService(boolean skipProximityUpdate) {
        try {
            ProjectionOnlyNotificationHelper.cancelMusic(this);
            RearScreenWakeManager.getInstance().stopWakeService(this, RearScreenLyricsActivity.class);
            if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                getApplicationContext().stopService(
                        new Intent(getApplicationContext(), RearScreenWakeService.class));
            }
            LogHelper.d(TAG, "⏹ 背屏常亮服务已停止（音乐投屏）");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 停止背屏常亮服务失败", e);
        }
        if (!skipProximityUpdate) {
            updateProjectionProximitySession();
        }
    }

    private void stopMusicProjectionWakeService() {
        stopMusicProjectionWakeService(false);
    }

    /** 功能页「投屏常亮」与 [RearAssistPrefs] 一致（手势/广播/磁贴入口共用）。 */
    private boolean isProjectionKeepScreenOnEnabled() {
        return RearAssistPrefs.INSTANCE.isKeepScreenOnEnabled(this);
    }

    /**
     * 绑定 TaskService（通过 RootTaskService，用于屏蔽/恢复官方手势服务）
     */
    private void bindTaskService() {
        try {
            if (taskService != null) {
                LogHelper.d(TAG, "TaskService已经绑定，跳过");
                return;
            }

            LogHelper.d(TAG, "🔗 开始绑定 RootTaskService（用于屏蔽官方手势服务）...");
            Intent intent = new Intent(this, RootTaskService.class);
            bindService(intent, taskServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 绑定 RootTaskService 失败", e);
        }
    }
    
    /**
     * 解绑TaskService
     */
    private void unbindTaskService() {
        try {
            if (taskService != null) {
                unbindService(taskServiceConnection);
                taskService = null;
                LogHelper.d(TAG, "✅ TaskService已解绑（RootTaskService）");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "解绑TaskService失败: " + e.getMessage());
        }
    }

    /**
     * 与 3.4 {@code ProjectionHelper#waitAndDisableGesture} 对齐：TaskService 就绪后再次 force-stop 官方背屏中心，
     * 与 {@link com.wmqc.miroot.rear.OfficialSubscreenMiRootProjectionSession} 前置禁用互补（迁屏窗口后巩固）。
     */
    private void checkAndDisableOfficialGesture() {
        disableOfficialGesture();
    }
    
    /**
     * 屏蔽官方手势服务（com.xiaomi.subscreencenter）
     */
    private void disableOfficialGesture() {
        if (isOfficialGestureDisabled) {
            LogHelper.d(TAG, "官方手势服务已屏蔽，跳过");
            return;
        }
        
        ITaskService ts = taskService;
        if (ts == null) {
            ts = RootTaskServiceConnector.getIfConnected();
        }
        if (ts == null) {
            LogHelper.w(TAG, "⚠️ TaskService未连接，暂无法屏蔽官方手势服务（不影响投屏本身）");
            return;
        }
        
        try {
            LogHelper.d(TAG, "🚫 开始屏蔽官方手势服务（com.xiaomi.subscreencenter）");
            boolean success = ts.disableSubScreenLauncher();
            if (success) {
                isOfficialGestureDisabled = true;
                if (taskService == null) {
                    taskService = ts;
                }
                LogHelper.d(TAG, "✅ 已屏蔽官方手势服务（com.xiaomi.subscreencenter）");
                
                if (taskServiceRecheckRunnable != null) {
                    uiHandler.removeCallbacks(taskServiceRecheckRunnable);
                }
                taskServiceRecheckRunnable = () -> {
                    try {
                        // 检查进程是否还在运行
                        boolean isRunning = taskService.isLauncherProcessRunning();
                        if (!isRunning) {
                            LogHelper.d(TAG, "✅ 验证成功：官方手势服务进程已停止");
                        } else {
                            LogHelper.w(TAG, "⚠️ 验证失败：官方手势服务进程仍在运行");
                        }
                    } catch (Exception e) {
                        LogHelper.w(TAG, "验证屏蔽状态失败: " + e.getMessage());
                    }
                };
                uiHandler.postDelayed(taskServiceRecheckRunnable, 1000);
            } else {
                LogHelper.e(TAG, "❌ disableSubScreenLauncher返回false");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 屏蔽官方手势服务失败", e);
            if (taskServiceRebindRunnable != null) {
                uiHandler.removeCallbacks(taskServiceRebindRunnable);
            }
            taskServiceRebindRunnable = () -> {
                if (taskService != null && !isOfficialGestureDisabled) {
                    LogHelper.d(TAG, "🔄 屏蔽失败，延迟重试");
                    try {
                        boolean success = taskService.disableSubScreenLauncher();
                        if (success) {
                            isOfficialGestureDisabled = true;
                            LogHelper.d(TAG, "✅ 重试成功：已屏蔽官方手势服务");
                        } else {
                            LogHelper.e(TAG, "❌ 重试屏蔽失败：disableSubScreenLauncher返回false");
                        }
                    } catch (Exception e2) {
                        LogHelper.e(TAG, "❌ 重试屏蔽也失败", e2);
                    }
                }
            };
            uiHandler.postDelayed(taskServiceRebindRunnable, 1000);
        }
    }
    
    /**
     * 恢复官方手势服务（com.xiaomi.subscreencenter）
     */
    private void enableOfficialGesture() {
        if (!isOfficialGestureDisabled) {
            LogHelper.d(TAG, "官方手势服务未屏蔽，跳过");
            return;
        }
        
        if (taskService == null) {
            LogHelper.w(TAG, "⚠️ TaskService未连接，无法恢复官方手势服务");
            // 即使TaskService未连接，也标记为已恢复，避免重复尝试
            isOfficialGestureDisabled = false;
            return;
        }
        
        try {
            // 使用enableSubScreenLauncher方法（与其他界面保持一致）
            boolean success = taskService.enableSubScreenLauncher();
            isOfficialGestureDisabled = false;
            if (success) {
                LogHelper.d(TAG, "✅ 已恢复官方手势服务（com.xiaomi.subscreencenter）");
            } else {
                LogHelper.w(TAG, "⚠️ enableSubScreenLauncher返回false");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 恢复官方手势服务失败", e);
            // 即使失败，也标记为已恢复，避免重复尝试
            isOfficialGestureDisabled = false;
        }
    }
    
    private void registerActiveSessionsChangedListener() {
        if (activeSessionsListenerRegistered) {
            return;
        }
        if (!checkNotificationListenerPermission()) {
            LogHelper.wThrottled(TAG, "通知使用权未开启或被系统限制：跳过注册会话监听（MIUI 请检查自启动/后台限制）", 10 * 60 * 1000L);
            return;
        }
        MediaSessionManager sm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sm == null) {
            return;
        }
        activeSessionsChangedListener = controllers -> {
            if (isFinishing()) {
                return;
            }
            if (lyricsView == null && !isMainScreenLandscapeMode) {
                return;
            }
            uiHandler.removeCallbacks(activeSessionsChangedDebouncedRunnable);
            uiHandler.postDelayed(activeSessionsChangedDebouncedRunnable, 120);
        };
        try {
            // API 31+ 签名为 (listener, notificationListener)；旧版为 (notificationListener, listener)。当前 compileSdk 36 匹配前者。
            sm.addOnActiveSessionsChangedListener(
                    activeSessionsChangedListener,
                    new ComponentName(this, MiRootNotificationListenerService.class));
            activeSessionsListenerRegistered = true;
            LogHelper.d(TAG, "✅ 已注册 OnActiveSessionsChangedListener");
        } catch (SecurityException e) {
            LogHelper.wThrottled(
                    TAG,
                    "注册 OnActiveSessionsChangedListener 被拒绝（通知使用权/MIUI 后台限制？）: " + LogHelper.truncateForLog(e.toString(), 180),
                    10 * 60 * 1000L
            );
        } catch (Throwable t) {
            LogHelper.wThrottled(
                    TAG,
                    "注册 OnActiveSessionsChangedListener 失败: " + LogHelper.truncateForLog(String.valueOf(t), 180),
                    10 * 60 * 1000L
            );
        }
    }

    private void unregisterActiveSessionsChangedListener() {
        if (!activeSessionsListenerRegistered || activeSessionsChangedListener == null) {
            return;
        }
        MediaSessionManager sm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sm != null) {
            try {
                sm.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
            } catch (Throwable t) {
                LogHelper.w(TAG, "注销 OnActiveSessionsChangedListener 失败: " + t.getMessage());
            }
        }
        uiHandler.removeCallbacks(activeSessionsChangedDebouncedRunnable);
        activeSessionsChangedListener = null;
        activeSessionsListenerRegistered = false;
        LogHelper.d(TAG, "✅ 已注销 OnActiveSessionsChangedListener");
    }

    /**
     * 设置MediaController
     * 每次调用前先注销旧回调，避免 onConfigurationChanged/onDisplayChanged/onResume 多次调用导致重复回调、元数据/播放状态反复触发 loadLyrics 与 updatePositionToView 引起歌词闪烁
     */
    private void refreshMediaControllerBinding() {
        setupMediaController();
        updateMediaInfo();
        updatePlaybackState();
    }

    /**
     * 周期性/播放态回调中校验「当前出声」的 MediaSession；列表未变但播放应用已切换时重绑。
     */
    private void maybeRefreshMediaControllerForActivePlayer() {
        long now = System.currentTimeMillis();
        if (now - lastMediaControllerRefreshCheckMs < MEDIA_CONTROLLER_REFRESH_CHECK_THROTTLE_MS) {
            return;
        }
        lastMediaControllerRefreshCheckMs = now;
        KuwoCarLyricsPolicy.maybeRefreshIfNeeded(
                this,
                mediaController,
                kuwoCarLyricsSessionActive,
                this::refreshMediaControllerBinding);
    }

    private void setupMediaController() {
        final String previousBoundPkg = lastBoundMediaPackage;
        try {
            if (!checkNotificationListenerPermission()) {
                LogHelper.wThrottled(TAG, "通知使用权未开启或被系统限制：跳过 MediaController 初始化（MIUI 请检查自启动/后台限制）", 10 * 60 * 1000L);
                return;
            }
            // 先注销旧回调，避免重复注册导致元数据变化时多次触发 updateMediaInfo/loadLyrics
            if (mediaController != null && mediaControllerCallback != null) {
                try {
                    mediaController.unregisterCallback(mediaControllerCallback);
                    LogHelper.d(TAG, "✅ 已注销旧 MediaController 回调，避免重复");
                } catch (Throwable t) {
                    LogHelper.w(TAG, "注销旧 MediaController 回调时异常: " + t.getMessage());
                }
                mediaControllerCallback = null;
            }
            mediaController = null;
            kuwoCarLyricsSessionActive = false;

            if (kuwoCarMediaSessionHelper == null) {
                kuwoCarMediaSessionHelper = new KuwoCarMediaSessionHelper(getApplicationContext());
            }

            MediaSessionManager sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sessionManager == null) {
                LogHelper.w(TAG, "⚠ MediaSessionManager为null");
                return;
            }

            List<MediaController> controllers = sessionManager.getActiveSessions(
                new android.content.ComponentName(this, MiRootNotificationListenerService.class)
            );

            if (controllers.isEmpty()) {
                LogHelper.w(TAG, "⚠ 没有活动的MediaController");
                return;
            }

            final MediaController first = KuwoCarLyricsPolicy.preferredActiveController(controllers);
            if (first == null) {
                LogHelper.w(TAG, "⚠ 无法从活跃会话中选择 MediaController");
                return;
            }
            final List<MediaController> controllerSnapshot = new ArrayList<>(controllers);

            if (KuwoCarLyricsPolicy.shouldUseKuwoCarLyrics(first)) {
                kuwoCarMediaSessionHelper.disconnect();
                // 先用活跃会话首位同步挂上 controller；MediaBrowser 连接 KwMediaSession 为异步，否则此期间手势/点击会得到 null
                mediaController = first;
                registerMediaControllerCallbacks();
                noteMediaControllerPackageBound(previousBoundPkg);
                updateMediaInfo();
                kuwoCarMediaSessionHelper.connect(
                    controller -> {
                        kuwoCarLyricsSessionActive = true;
                        if (mediaController != null && mediaControllerCallback != null) {
                            try {
                                mediaController.unregisterCallback(mediaControllerCallback);
                            } catch (Throwable t) {
                                LogHelper.w(TAG, "切换到 KwMediaSession 前注销回调: " + t.getMessage());
                            }
                            mediaControllerCallback = null;
                        }
                        mediaController = controller;
                        registerMediaControllerCallbacks();
                        noteMediaControllerPackageBound(lastBoundMediaPackage);
                        LogHelper.d(TAG, "✅ MediaController已设置（酷我车载播放中，直连 KwMediaSession）");
                        updateMediaInfo();
                        tryApplyKuwoLyricsFromCurrentExtras();
                    },
                    () -> {
                        kuwoCarLyricsSessionActive = false;
                        if (!controllerSnapshot.isEmpty()) {
                            // 同步阶段已挂 first 时勿重复 registerCallback
                            if (mediaController == null) {
                                MediaController fb = KuwoCarLyricsPolicy.preferredActiveController(controllerSnapshot);
                                if (fb != null) {
                                    mediaController = fb;
                                    registerMediaControllerCallbacks();
                                    noteMediaControllerPackageBound(lastBoundMediaPackage);
                                }
                            }
                            LogHelper.w(TAG, "⚠ 酷我 MediaBrowser 未连接，回退使用优先活跃会话");
                            updateMediaInfo();
                        } else {
                            mediaController = null;
                            LogHelper.w(TAG, "⚠ 酷我 MediaBrowser 未连接且无可用会话");
                        }
                    }
                );
                return;
            }

            kuwoCarMediaSessionHelper.disconnect();
            mediaController = first;
            registerMediaControllerCallbacks();
            noteMediaControllerPackageBound(previousBoundPkg);

            LogHelper.d(TAG, "✅ MediaController已设置");
        } catch (SecurityException e) {
            LogHelper.wThrottled(
                    TAG,
                    "设置 MediaController 被拒绝（通知使用权/MIUI 后台限制？）: " + LogHelper.truncateForLog(e.toString(), 180),
                    10 * 60 * 1000L
            );
        } catch (Exception e) {
            LogHelper.wThrottled(
                    TAG,
                    "设置 MediaController 失败: " + LogHelper.truncateForLog(e.toString(), 180),
                    10 * 60 * 1000L
            );
        }
    }

    private void noteMediaControllerPackageBound(String previousBoundPkg) {
        if (mediaController == null) {
            return;
        }
        String newPkg;
        try {
            newPkg = mediaController.getPackageName();
        } catch (Throwable t) {
            return;
        }
        if (newPkg == null || newPkg.isEmpty()) {
            return;
        }
        if (!newPkg.equals(previousBoundPkg) && previousBoundPkg != null && !previousBoundPkg.isEmpty()) {
            resetLyricsStateForPlayerSwitch(previousBoundPkg, newPkg);
        }
        lastBoundMediaPackage = newPkg;
    }

    /** 用户切换音乐 App：清空旧歌词/去重状态，避免继续显示上一播放器的歌词。 */
    private void resetLyricsStateForPlayerSwitch(String fromPkg, String toPkg) {
        LogHelper.d(TAG, "🔄 切换播放器 " + fromPkg + " -> " + toPkg + "，重置歌词状态");
        lastLyricsLoadTrackSignature = "";
        stableTrackSignature = "";
        stableTitle = "";
        stableArtist = "";
        stableDurationMs = 0L;
        lastKuwoAudioLyricTrackKey = "";
        lastKuwoAudioLyricPayload = "";
        kuwoBroadcastWordTimestampsApplied = false;
        apiAttemptedTrackKey = "";
        cancelPendingKuwoNativeFallbackWait();
        kuwoPostNativeFallbackScheduled = false;
        kuwoAllowThirdPartyFallback = false;
        lyricsRequestSeq++;
        cancelPendingNoLyrics();
        try {
            enhancedLyricLines = new ArrayList<>();
            lastAbyssalRenderedLineIndex = -1;
            lastAppliedLyricsTrackKey = "";
            lastAppliedLyricsFingerprint = "";
            // V3.17+: 先清空歌词视图再置 trackLoading
            setLyricsToView(null);
            if (lyricsView != null) {
                lyricsView.setTrackLoading(true);
                lyricsView.clearTokenizationCache();
            }
            updateHookSourceStatusText(LyricsRuntimeSource.ACQUIRING);
        } catch (Exception ignored) {
        }
    }

    private void registerMediaControllerCallbacks() {
        if (mediaController == null) {
            return;
        }
        if (mediaControllerCallback != null) {
            try {
                mediaController.unregisterCallback(mediaControllerCallback);
            } catch (Throwable t) {
                LogHelper.w(TAG, "替换回调前注销旧 MediaController.Callback: " + t.getMessage());
            }
            mediaControllerCallback = null;
        }
        mediaControllerCallback = new RearLyricsMediaSessionCallbacks(
                () -> {
                    LogHelper.d(TAG, "📻 元数据变化");
                    updateMediaInfo();
                },
                this::updatePlaybackState,
                extras -> {
                    if (kuwoCarLyricsSessionActive) {
                        tryApplyKuwoLyricsFromExtrasBundle(extras);
                    }
                });
        mediaController.registerCallback(mediaControllerCallback);
    }

    private void tryApplyKuwoLyricsFromCurrentExtras() {
        if (mediaController == null) {
            return;
        }
        try {
            tryApplyKuwoLyricsFromExtrasBundle(mediaController.getExtras());
        } catch (Throwable t) {
            LogHelper.w(TAG, "读取 MediaController extras 失败: " + t.getMessage());
            maybeKuwoExtrasFallbackToApi("读取 extras 异常");
        }
    }

    private void tryApplyKuwoLyricsFromExtrasBundle(Bundle extras) {
        if (extras == null) {
            LogHelper.d(TAG, "酷我 extras 为空，继续等待 AUDIO_LYRIC（由超时任务兜底）");
            return;
        }
        // 如果 enhancedLyricLines 已包含来自 LYRIC_FULL 广播的逐字时间戳（words_json），
        // 则跳过 AUDIO_LYRIC（仅行级），因为广播数据更优。
        if (hasBroadcastWordTimestamps()) {
            LogHelper.d(TAG, "🛡️ 跳过 AUDIO_LYRIC：广播 LYRIC_FULL 已提供逐字时间戳数据");
            return;
        }
        String json = extras.getString(KuwoAudioLyricParser.EXTRA_AUDIO_LYRIC);
        final String kuwoTrackKey = buildTrackKey(stableTitle, stableArtist, resolveCurrentLyricsPackageName(), stableDurationMs);
        if (kuwoTrackKey.equals(lastKuwoAudioLyricTrackKey)
            && json != null
            && !json.isEmpty()
            && json.equals(lastKuwoAudioLyricPayload)) {
            LogHelper.d(TAG, "🛡️ 酷我 AUDIO_LYRIC 防重：同曲同内容，跳过重复解析");
            return;
        }
        EnhancedLRCParser.ParseResult pr = KuwoAudioLyricParser.parse(json);
        if (pr == null || pr.lines == null || pr.lines.isEmpty()) {
            maybeKuwoExtrasFallbackToApi("AUDIO_LYRIC 无效或为空");
            return;
        }
        runOnUiThread(() -> {
            if (kuwoBroadcastWordTimestampsApplied) {
                lastKuwoAudioLyricTrackKey = kuwoTrackKey;
                lastKuwoAudioLyricPayload = json != null ? json : "";
                LogHelper.d(TAG, "🛡️ 酷我广播逐字已就绪，跳过 AUDIO_LYRIC 覆盖");
                return;
            }
            enhancedLyricLines = pr.lines;
            cancelPendingNoLyrics();
            superLyricFallbackModeActive = false;
            applyFallbackRenderingModeIfNeeded();
            setLyricsToView(enhancedLyricLines);
            lastKuwoAudioLyricTrackKey = kuwoTrackKey;
            lastKuwoAudioLyricPayload = json != null ? json : "";
            markKuwoAudioLyricApplied();
            LogHelper.d(TAG, "✅ 已应用酷我 AUDIO_LYRIC，行数=" + enhancedLyricLines.size());
        });
    }

    /**
     * 应用酷我车机版 LYRIC_FULL 广播携带的逐字歌词（参考酷我移植参考文档.md §9）。
     * 与 AUDIO_LYRIC（仅行级）相比，本路径的 wordTimestamps 来自 words_json，可驱动逐字高亮。
     * 此来源优先级最高：接收到广播数据后，其他来源（AUDIO_LYRIC、网络 API、SuperLyric）不会覆盖。
     * 数据通过 setLyricsToView 走与 AUDIO_LYRIC 一致的链路，更新 enhancedLyricLines 并交给 ModernLyricsView。
     */
    public void applyKuwoBroadcastLyrics(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        runOnUiThread(() -> {
            try {
                enhancedLyricLines = lines;
                cancelPendingNoLyrics();
                superLyricFallbackModeActive = false;
                applyFallbackRenderingModeIfNeeded();
                setLyricsToView(enhancedLyricLines);
                markKuwoAudioLyricApplied();
                // 更新来源标识为广播逐字（覆盖 AUDIO_LYRIC 的通用标记）
                updateHookSourceStatusText(LyricsRuntimeSource.KUWO_AUDIO_LYRIC, "广播·逐字");
                if (lyricsView != null) {
                    lyricsView.setEnableWordByWord(true);
            lyricsView.setCharJumpEnabled(true);
                }
                int wordCount = 0;
                for (EnhancedLRCParser.EnhancedLyricLine l : lines) {
                    if (l != null && l.wordTimestamps != null) {
                        wordCount += l.wordTimestamps.size();
                    }
                }
                LogHelper.d(TAG, "✅ 已应用酷我 LYRIC_FULL 广播逐字数据：" + lines.size()
                    + " 行 / " + wordCount + " 字（优先级：最高，广播 > AUDIO_LYRIC > 其他来源）");
                if (wordCount > 0) {
                    kuwoBroadcastWordTimestampsApplied = true;
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 应用酷我 LYRIC_FULL 广播失败", e);
            }
        });
    }

    /**
     * 应用酷我车机版 LYRIC_PROGRESS 广播携带的播放进度（参考酷我移植参考文档.md §9.4）。
     * @param positionMs 绝对播放进度（毫秒）
     * @param playing 是否正在播放
     * @param lineIndex 当前行索引（-1 表示未提供）
     * @param wordCharStart 当前字起始字符位置（-1 表示未提供）
     * @param wordCharEnd 当前字结束字符位置（-1 表示未提供）
     */
    public void applyKuwoBroadcastProgress(long positionMs, boolean playing,
                                           int lineIndex, int wordCharStart, int wordCharEnd) {
        runOnUiThread(() -> {
            try {
                if (lyricsView != null) {
                    lyricsView.setPlaybackActive(playing);
                    if (positionMs > 0L && positionMs <= 3600000L) {
                        lyricsView.updatePosition(positionMs);
                    }
                    // Direct word hint from Kuwo broadcast — bypass time-based interpolation
                    if (lineIndex >= 0 && wordCharStart >= 0 && wordCharEnd >= 0) {
                        lyricsView.setKuwoWordHighlightHint(lineIndex, wordCharStart, wordCharEnd);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 应用酷我车机版 LYRIC_PROGRESS 广播（仅进度，兼容旧调用方）。
     */
    public void applyKuwoBroadcastProgress(long positionMs, boolean playing) {
        applyKuwoBroadcastProgress(positionMs, playing, -1, -1, -1);
    }

    /**
     * 酷我 extras 无有效逐行歌词时，在专用解析确认失败后走网络 API / 逐字融合兜底。
     */
    private void maybeKuwoExtrasFallbackToApi(String reason) {
        if (!kuwoCarLyricsSessionActive) {
            return;
        }
        runOnUiThread(() -> {
            if (!kuwoCarLyricsSessionActive) {
                return;
            }
            if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
                cancelPendingKuwoNativeFallbackWait();
                kuwoPostNativeFallbackScheduled = false;
                return;
            }
            String title = stableTitle;
            if (title == null || title.isEmpty() || "未知歌曲".contentEquals(title) || isLikelyLyricLine(title)) {
                LogHelper.d(TAG, "酷我 extras 无有效歌词，等待下一次 AUDIO_LYRIC（无有效曲名）: " + reason);
                return;
            }
            if ("extras=null".equals(reason)) {
                LogHelper.d(TAG, "酷我 extras 仍为空，继续等待 AUDIO_LYRIC");
                return;
            }
            String artist = stableArtist != null ? stableArtist : "";
            final int requestSeq = lyricsRequestSeq;
            final String trackKey = currentTrackKey != null ? currentTrackKey : "";
            beginKuwoLyricsFallbackAfterNativeFailed(reason, requestSeq, trackKey, title, artist);
        });
    }

    /**
     * 酷我 extras 等独立场景：自增请求序号后拉取第三方 API。
     */
    private void fetchLyricsFromSuperLyricApi(String title, String artist, int requestSeq, String trackKey) {
        // NETWORK_ONLY：任何路径都不允许触发 SuperLyric 拉取。
        if (isNetworkOnlyLyricsSourceMode()) {
            return;
        }
        if (isKuwoAwaitingNativeLyrics() && !kuwoAllowThirdPartyFallback) {
            LogHelper.d(TAG, "酷我 AUDIO_LYRIC 等待中，忽略 SuperLyric 拉取");
            return;
        }
        if (title == null || title.isEmpty()) return;
        final String finalTitle = title;
        final String finalArtist = artist != null ? artist : "";
        final String sourcePkg = resolveCurrentLyricsPackageName();
        AppExecutors.runInBackground(() -> {
            try {
                String superLyrics = isSuperLyricOnlySourceMode()
                    ? SuperLyricApi.fetchLyricsFromModuleBinderOnly(finalTitle, finalArtist, sourcePkg)
                    : SuperLyricApi.fetchLyrics(
                        RearScreenLyricsActivity.this,
                        mediaController,
                        finalTitle,
                        finalArtist,
                        sourcePkg
                    );
                if (superLyrics != null && !superLyrics.trim().isEmpty()) {
                    EnhancedLRCParser.ParseResult parsed = parseLyricsContent(superLyrics);
                    if (parsed != null && parsed.lines != null && !parsed.lines.isEmpty()) {
                        runOnUiThread(() -> {
                            if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                                return;
                            }
                            prepareSuperLyricModuleLyricLines(parsed.lines);
                            enhancedLyricLines = parsed.lines;
                            cancelPendingNoLyrics();
                            // 有整曲歌词时用正常渲染链路，逐字由后续 SuperLyric 模块回调写入。
                            if (isMixedLyricsSourceMode()) {
                                markStructuredLyricsForWordFusion(sourcePkg, LyricsRuntimeSource.SUPER_LYRIC, "");
                                updateHookSourceStatusText(LyricsRuntimeSource.SUPER_LYRIC, "整曲·待逐字");
                            } else {
                                markStructuredLyricsFromSuperLyricModule(sourcePkg);
                                updateHookSourceStatusText(LyricsRuntimeSource.SUPER_LYRIC, "整曲·待逐字");
                            }
                            applyFallbackRenderingModeIfNeeded();
                            setLyricsToView(enhancedLyricLines);
                            if (lyricsView != null) {
                                lyricsView.refreshAllLines();
                                lyricsView.postInvalidateOnAnimation();
                            }
                            tryFlushCachedSuperLyricWordFusion();
                            LogHelper.d(TAG, "✅ SuperLyric 整曲歌词命中: " + enhancedLyricLines.size() + " 行"
                                + (isMixedLyricsSourceMode() ? "（智能切换·可逐字融合）" : "（仅模块·可逐字）"));
                        });
                        return;
                    }
                }
                SuperLyricApi.SuperLyricFallbackPayload payload = isSuperLyricOnlySourceMode()
                    ? SuperLyricApi.fetchFallbackPayloadForModuleOnly(finalTitle, finalArtist, sourcePkg)
                    : SuperLyricApi.fetchFallbackPayload(finalTitle, finalArtist, sourcePkg);
                if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                    return;
                }
                if (payload != null && payload.text != null && !payload.text.trim().isEmpty()) {
                    runOnUiThread(() -> applySuperLyricFallbackPayload(payload, sourcePkg, requestSeq, trackKey));
                    return;
                }
                runOnUiThread(() -> {
                    if (superLyricRealtimeListener != null) {
                        tryFlushCachedSuperLyricFallback();
                    }
                    scheduleNoLyricsIfStillEmpty(
                        requestSeq, trackKey, LyricsRuntimeSource.SUPER_LYRIC, "未命中");
                });
            } catch (Exception e) {
                LogHelper.w(TAG, "SuperLyricApi 读取失败: " + e.getMessage());
                runOnUiThread(() -> scheduleNoLyricsIfStillEmpty(
                    requestSeq, trackKey, LyricsRuntimeSource.SUPER_LYRIC, "异常"));
            }
        });
    }

    /**
     * 在工作线程拉取并解析网络歌词；成功则 post 到主线程应用。
     *
     * @return true 表示解析出非空行并已投递 UI（不等待主线程执行完毕）
     */
    private boolean fetchAndApplyNetworkLyricsOnWorkerThread(
            String finalTitle,
            String finalArtist,
            long finalDurationMs,
            boolean strictTitleArtistMatch,
            boolean enableSecondaryNetworkFallback,
            int requestSeq,
            String trackKey,
            String okLogPrefix) {
        String sourcePkg = kuwoCarLyricsSessionActive
            ? KuwoCarMediaSessionHelper.KUWO_PACKAGE
            : resolveCurrentLyricsPackageName();
        NetworkLyricsOrchestrator.Payload networkPayload = MusicInfoHelper.fetchNetworkLyricsPayload(
            RearScreenLyricsActivity.this,
            finalTitle,
            finalArtist,
            finalDurationMs,
            null,
            sourcePkg,
            strictTitleArtistMatch,
            enableSecondaryNetworkFallback,
            false
        );
        if (networkPayload == null || !networkPayload.success
            || networkPayload.lyrics == null || networkPayload.lyrics.isEmpty()) {
            return false;
        }
        final String networkProvider = networkPayload.provider != null ? networkPayload.provider : "";
        final MusicPlayerLyricsPolicy.PrimaryStrategy networkStrategy = networkPayload.strategy;
        EnhancedLRCParser.ParseResult parsedResult = parseLyricsContent(networkPayload.lyrics);
        if (parsedResult == null || parsedResult.lines == null || parsedResult.lines.isEmpty()) {
            LogHelper.w(TAG, "⚠ 歌词解析后为空，原始歌词长度: " + networkPayload.lyrics.length()
                + " 字符, strictMatch=" + strictTitleArtistMatch
                + ", provider=" + networkProvider);
            return false;
        }
        final EnhancedLRCParser.ParseResult prFinal = parsedResult;
        final LyricsRuntimeSource networkRuntimeSource = resolveNetworkRuntimeSource(networkProvider);
        final String strategyLabel = MusicPlayerLyricsPolicy.strategyDisplayLabel(networkStrategy);
        runOnUiThread(() -> {
            try {
                if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                    return;
                }
                String curPkg = resolveCurrentLyricsPackageName();
                if (!shouldAcceptApiResultForTrack(curPkg)) {
                    LogHelper.d(TAG, "🛡️ API 结果被优先级机制拦截（避免覆盖/闪烁）");
                    return;
                }
                unlockFromSuperLyricFallbackAndApplyNetworkLyrics(
                    prFinal.lines,
                    curPkg,
                    networkRuntimeSource,
                    strategyLabel,
                    networkProvider
                );
                LogHelper.d(TAG, okLogPrefix + enhancedLyricLines.size() + " 行, provider="
                    + networkProvider + ", strategy=" + strategyLabel);
            } catch (Exception e) {
                LogHelper.e(TAG, "解析API歌词时发生异常: " + e.getMessage(), e);
            }
        });
        return true;
    }

    /**
     * 智能切换（MIXED）：所有 App（除酷我车机）全源严格匹配 → 全部失败则 SuperLyric 模块兜底。
     * 网络优先（NETWORK_ONLY）：单次走完编排器全链路。
     */
    private boolean tryNetworkLyricsStrictThenLoose(
            String finalTitle,
            String finalArtist,
            long finalDurationMs,
            int requestSeq,
            String trackKey) {
        final boolean mixedMode = isMixedLyricsSourceMode();
        final boolean networkOnly = isNetworkOnlyLyricsSourceMode();
        final String networkPkg = kuwoCarLyricsSessionActive
            ? KuwoCarMediaSessionHelper.KUWO_PACKAGE
            : resolveCurrentLyricsPackageName();

        // 酷我车机：走 AUDIO_LYRIC 专用解析，网络回退不参与严格匹配
        if (MusicPlayerLyricsPolicy.isKuwoCarPackage(networkPkg)) {
            return fetchAndApplyNetworkLyricsOnWorkerThread(
                finalTitle, finalArtist, finalDurationMs,
                false, true, requestSeq, trackKey,
                "✅ 网络API·酷我车机："
            );
        }

        // 智能切换：全源严格匹配，不再模糊搜索
        if (mixedMode) {
            if (finalArtist == null || finalArtist.trim().isEmpty()) {
                LogHelper.d(TAG, "智能切换：歌手元数据为空，尝试宽松网络（含次级 API）");
                return fetchAndApplyNetworkLyricsOnWorkerThread(
                    finalTitle, finalArtist, finalDurationMs,
                    false, true, requestSeq, trackKey,
                    "✅ 网络API(宽松网络)："
                );
            }
            LogHelper.d(TAG, "智能切换：全源严格匹配 qsgc → 酷狗 → lrclib ∥ lyrics.ovh");
            return fetchAndApplyNetworkLyricsOnWorkerThread(
                finalTitle, finalArtist, finalDurationMs,
                true, true, requestSeq, trackKey,
                "✅ 智能切换·全源严格："
            );
        }

        if (!networkOnly) {
            return false;
        }

        // 网络优先：单次走完编排器全链路
        return fetchAndApplyNetworkLyricsOnWorkerThread(
            finalTitle, finalArtist, finalDurationMs,
            false, true, requestSeq, trackKey,
            "✅ 网络API·网络优先："
        );
    }

    /**
     * 酷我 extras 等独立场景：自增请求序号后拉取第三方 API。
     */
    private void fetchLyricsFromThirdPartyApi(String title, String artist, String logTag) {
        if (title == null || title.isEmpty()) {
            return;
        }
        final String trackKey = buildTrackKey(title, artist, resolveCurrentLyricsPackageName(), stableDurationMs);
        final int requestSeq = ++lyricsRequestSeq;
        currentTrackKey = trackKey;
        cancelPendingNoLyrics();
        fetchLyricsFromThirdPartyApi(title, artist, logTag, requestSeq, trackKey);
    }

    /**
     * 与 loadLyrics「策略 3」一致：异步拉取第三方 API；使用调用方已生成的 requestSeq / trackKey（避免重复递增）。
     */
    private void fetchLyricsFromThirdPartyApi(String title, String artist, String logTag, int requestSeq, String trackKey) {
        if (title == null || title.isEmpty()) {
            return;
        }
        if (isKuwoAwaitingNativeLyrics() && !kuwoAllowThirdPartyFallback) {
            LogHelper.d(TAG, "酷我 AUDIO_LYRIC 等待中，忽略第三方 API: " + logTag);
            return;
        }
        if (!shouldUseNetworkApiSource()) {
            // SUPER_ONLY：仅走 SuperLyric；NETWORK_ONLY 不会进入这个分支。
            fetchLyricsFromSuperLyricApi(title, artist, requestSeq, trackKey);
            return;
        }
        if (trackKey != null && !trackKey.isEmpty() && trackKey.equals(inflightApiTrackKey)) {
            LogHelper.d(TAG, "🛡️ API 防重：同曲目请求进行中，跳过重复调用");
            return;
        }
        if (trackKey != null && !trackKey.isEmpty() && trackKey.equals(apiAttemptedTrackKey)) {
            LogHelper.d(TAG, "🛡️ API 防重：同曲目已拉取过网络歌词，跳过重复调用");
            return;
        }
        inflightApiTrackKey = trackKey != null ? trackKey : "";
        apiAttemptedTrackKey = inflightApiTrackKey;
        final String finalTitle = title;
        final String finalArtist = artist != null ? artist : "";
        final long finalDurationMs = stableDurationMs;

        LogHelper.d(TAG, logTag + "，尝试从第三方API获取: " + finalTitle + " - " + finalArtist);

        AppExecutors.runInBackground(() -> {
            try {
                boolean networkOk = false;
                try {
                    networkOk = tryNetworkLyricsStrictThenLoose(
                        finalTitle, finalArtist, finalDurationMs, requestSeq, trackKey);
                } catch (Exception e) {
                    LogHelper.e(TAG, "从第三方API获取歌词失败: " + e.getMessage(), e);
                    if (isMixedLyricsSourceMode()
                        && MusicPlayerLyricsPolicy.isKuwoCarPackage(
                            resolveCurrentLyricsPackageName())) {
                        try {
                            networkOk = fetchAndApplyNetworkLyricsOnWorkerThread(
                                finalTitle,
                                finalArtist,
                                finalDurationMs,
                                false,
                                true,
                                requestSeq,
                                trackKey,
                                "✅ 网络API·逐字融合(宽松网络异常恢复)："
                            );
                        } catch (Exception e2) {
                            LogHelper.e(TAG, "网络API·逐字融合宽松网络兜底失败: " + e2.getMessage(), e2);
                        }
                    }
                }
                if (networkOk) {
                    return;
                }
                runOnUiThread(() -> {
                    if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                        return;
                    }
                    cancelPendingQishuiSuperLyricPreviewWait();
                    qishuiAwaitingNetworkLyrics = false;
                    String curPkg = resolveCurrentLyricsPackageName();
                    if (!shouldAcceptApiResultForTrack(curPkg)) {
                        return;
                    }
                    if (shouldUseSuperLyricFallback()) {
                        fetchLyricsFromSuperLyricApi(finalTitle, finalArtist, requestSeq, trackKey);
                        LogHelper.d(TAG, "⚠ 网络API未命中，尝试 SuperLyricApi 3.4 兜底");
                    } else {
                        scheduleNoLyricsIfStillEmpty(
                            requestSeq, trackKey, LyricsRuntimeSource.NETWORK, "未命中");
                    }
                });
            } finally {
                runOnUiThread(() -> {
                    if (trackKey != null && trackKey.equals(inflightApiTrackKey)) {
                        inflightApiTrackKey = "";
                    }
                });
            }
        });
    }

    /**
     * 更新媒体信息（歌名、歌手、歌词）
     */
    private void updateMediaInfo() {
        if (mediaController == null) {
            LogHelper.w(TAG, "⚠ MediaController为null，无法更新媒体信息");
            return;
        }
        
        MediaMetadata metadata = mediaController.getMetadata();
        if (metadata == null) {
            LogHelper.w(TAG, "⚠ MediaMetadata为null");
            setTextIfChanged(songTitleText, "未检测到音乐播放");
            if (songTitleText != null) {
                songTitleText.post(this::applySongTitleSafeInsets);
            }
            setTextIfChanged(artistText, "");
            updateAlbumArtBackground(null);
            return;
        }
        updateAlbumArtBackground(metadata);
        
        // 更新歌名和歌手（合并为一个整体显示）
        final String pkg = mediaController != null ? mediaController.getPackageName() : "";
        String rawTitle = pickNonLyricString(
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            safeToString(metadata.getDescription() != null ? metadata.getDescription().getTitle() : null),
            null
        );
        String rawArtist = pickNonLyricString(
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            safeToString(metadata.getDescription() != null ? metadata.getDescription().getSubtitle() : null),
            null
        );
        long rawDurationMs = 0L;
        try {
            rawDurationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {}

        // 若 metadata 的 title 被藍牙歌詞污染，嘗試從通知再取一次（不影響車機，只影響本App顯示/檢索）
        if (rawTitle == null || rawTitle.isEmpty()) {
            TitleArtist ta = getTitleArtistFromActiveMusicNotificationCached(pkg);
            if (ta != null) {
                rawTitle = pickNonLyricString(rawTitle, ta.title);
                rawArtist = pickNonLyricString(rawArtist, ta.artist);
            }
        }

        // 構造曲目簽名：優先 mediaId，否則使用 (album/artist/duration/trackNumber) 的組合做穩定判斷
        final String signature = buildTrackSignature(metadata, pkg);
        final long now = System.currentTimeMillis();

        // 判斷是否「同一首歌」：簽名相同，或僅酷狗等後補的 mediaId/时长（避免二次拉词）
        final boolean sameTrack = isSameTrackMetadataSignature(
            signature,
            stableTrackSignature,
            stableTitle,
            stableArtist,
            rawTitle,
            rawArtist
        );

        // 決定要用於 UI 顯示的 title/artist（stable）
        String titleForDisplay = rawTitle;
        String artistForDisplay = rawArtist;

        if (!sameTrack) {
            // 換歌：接受新 title/artist，但若 title 像歌詞行（車載藍牙常見）則拒用，顯示「未知歌曲」
            stableTrackSignature = signature != null ? signature : "";
            apiAttemptedTrackKey = "";
            lastNotificationLookupPkg = "";
            lastNotificationLookupAtMs = 0L;
            lastNotificationLookupResult = null;
            // 切歌时重置「同曲去重」状态，允许新歌进入一次完整加载链。
            lastKuwoAudioLyricTrackKey = "";
            lastKuwoAudioLyricPayload = "";
            if (rawTitle != null && !rawTitle.isEmpty() && !isLikelyLyricLine(rawTitle)) {
                stableTitle = rawTitle;
                stableArtist = (rawArtist != null) ? rawArtist : "";
            } else {
                stableTitle = "";
                stableArtist = (rawArtist != null && !isLikelyLyricLine(rawArtist)) ? rawArtist : "";
            }
            stableDurationMs = rawDurationMs;
            lastStableUpdateMs = now;
        } else {
            // 同一首歌：若已經鎖定 stableTitle，後續即便播放器把歌詞塞進 title 也不再覆蓋
            boolean titleChanged = rawTitle != null && !rawTitle.equals(stableTitle) && !rawTitle.isEmpty();
            boolean artistChanged = rawArtist != null && !rawArtist.equals(stableArtist) && !rawArtist.isEmpty();

            // 允許 artist 變更（某些播放器先空後補），但 title 在同一曲目內一旦穩定就不再更新，避免被藍牙歌詞污染
            if (artistChanged && (stableArtist == null || stableArtist.isEmpty())) {
                // 只在尚未鎖定 artist 時接受第一個非歌詞形式的 artist
                if (!isLikelyLyricLine(rawArtist)) {
                    stableArtist = rawArtist;
                }
            }
            if (stableDurationMs <= 0 && rawDurationMs > 0) {
                stableDurationMs = rawDurationMs;
            }

            if (titleChanged) {
                // stableTitle 一旦非空就視為已鎖定，不再被後續 metadata 覆蓋
                if (stableTitle == null || stableTitle.isEmpty()) {
                    boolean tooSoon = (now - lastStableUpdateMs) < STABLE_TITLE_MIN_UPDATE_INTERVAL_MS;
                    boolean looksLikeLyric = isLikelyLyricLine(rawTitle);
                    if (!tooSoon && !looksLikeLyric) {
                        // 僅在尚未鎖定且不像歌詞的情況下允許一次修正（避免臨時標題）
                        stableTitle = rawTitle;
                        lastStableUpdateMs = now;
                    } else {
                        LogHelper.d(TAG, "🛡️ 忽略疑似藍牙歌詞造成的title跳動: " + safeShort(rawTitle));
                    }
                } else {
                    // 已有穩定標題時，完全忽略後續 title 變更（常見於車載藍牙歌詞）
                    LogHelper.d(TAG, "🛡️ 已鎖定 stableTitle，忽略後續 title 更新: " + safeShort(rawTitle));
                }
            }
        }

        // 若 rawArtist 類似「歌名 - 歌手」，且前半部分與穩定曲名高度相似，則只保留後半部分作為 artist
        if (rawArtist != null && !rawArtist.isEmpty()) {
            String trimmedArtist = rawArtist.trim();
            int sep = trimmedArtist.indexOf(" - ");
            if (sep > 0 && sep < trimmedArtist.length() - 3) {
                String left = trimmedArtist.substring(0, sep).trim();
                String right = trimmedArtist.substring(sep + 3).trim();
                String baseTitle = (stableTitle != null && !stableTitle.isEmpty()) ? stableTitle : rawTitle;
                if (baseTitle != null && !baseTitle.isEmpty()) {
                    String normLeft = left.replace(" ", "");
                    String normTitle = baseTitle.replace(" ", "");
                    if (!isLikelyLyricLine(left) && normLeft.equalsIgnoreCase(normTitle) && !right.isEmpty()) {
                        rawArtist = right;
                    }
                }
            }
        }

        // 使用穩定值顯示；回退到 rawTitle 時再做一次歌詞過濾，確保不把歌詞當歌名顯示
        String fallbackTitle = (rawTitle != null && !rawTitle.isEmpty() && !isLikelyLyricLine(rawTitle)) ? rawTitle : "";
        titleForDisplay = (stableTitle != null && !stableTitle.isEmpty()) ? stableTitle : fallbackTitle;
        artistForDisplay = (stableArtist != null && !stableArtist.isEmpty()) ? stableArtist : rawArtist;
        
        // 构建显示文本：歌名 + " - " + 歌手（如果歌手存在）
        StringBuilder displayText = new StringBuilder();
        if (titleForDisplay != null && !titleForDisplay.isEmpty()) {
            displayText.append(titleForDisplay);
        } else {
            displayText.append("未知歌曲");
        }
        
        if (artistForDisplay != null && !artistForDisplay.isEmpty()) {
            displayText.append(" - ").append(artistForDisplay);
        }

        // 某些車機在中途喚起時會給出「歌詞 - 歌手 - 歌名-歌手」的組合格式：
        // 這裡在最終顯示前做一次清洗：若整體至少包含兩個「 - 」分隔，則丟棄第一段，只保留後半部分
        String combined = displayText.toString();
        if (combined != null && !combined.isEmpty() && combined.contains(" - ")) {
            String[] parts = combined.split(" - ");
            if (parts.length == 2) {
                // 明確兼容「歌詞 - 歌曲名稱-歌手」：
                // 僅當後半段本身還包含「-」（無空格，多半是「歌名-歌手」連在一起）時，才視為該格式
                String tail = parts[1] != null ? parts[1].trim() : "";
                if (!tail.isEmpty() && tail.contains("-") && !tail.contains(" - ")) {
                    combined = tail;
                }
            } else if (parts.length >= 3) {
                // 「歌詞 - 歌手 - 歌曲名稱-歌手」：丟棄前兩段，只保留最後一段「歌曲名稱-歌手」
                String last = parts[parts.length - 1] != null ? parts[parts.length - 1].trim() : "";
                if (!last.isEmpty()) {
                    combined = last;
                }
            }
        }
        
        // 设置合并后的文本（支持滚动显示）
        setTextIfChanged(songTitleText, combined);
        if (songTitleText != null) {
            songTitleText.post(this::applySongTitleSafeInsets);
        }
        
        // artistText保留用于内部使用（但不在UI中显示）
        if (artistForDisplay != null && !artistForDisplay.isEmpty()) {
            setTextIfChanged(artistText, artistForDisplay);
        } else {
            setTextIfChanged(artistText, "");
        }
        
        // 获取歌词（仅切歌触发）：同一首歌播放期间不重复请求/解析/设置。
        // 注意：歌詞抓取必須用穩定曲名，禁止用歌詞行檢索
        boolean canLoadLyrics = titleForDisplay != null
            && !titleForDisplay.isEmpty()
            && !"未知歌曲".contentEquals(titleForDisplay)
            && !isLikelyLyricLine(titleForDisplay);
        String effectiveSignature = signature != null ? signature : "";
        if (canLoadLyrics && !effectiveSignature.isEmpty()) {
            if (effectiveSignature.equals(lastLyricsLoadTrackSignature)) {
                LogHelper.d(TAG, "🛡️ 同曲目签名未变，跳过重复 loadLyrics");
            } else if (!lastLyricsLoadTrackSignature.isEmpty()
                && enhancedLyricLines != null && !enhancedLyricLines.isEmpty()
                && isSameTrackMetadataSignature(
                    effectiveSignature,
                    lastLyricsLoadTrackSignature,
                    stableTitle,
                    stableArtist,
                    titleForDisplay,
                    artistForDisplay
                )) {
                lastLyricsLoadTrackSignature = effectiveSignature;
                stableTrackSignature = effectiveSignature;
                LogHelper.d(TAG, "🛡️ 播放器元数据补全(mediaId/时长)，保留歌词不重拉");
            } else {
                lastLyricsLoadTrackSignature = effectiveSignature;
                loadLyrics(titleForDisplay, artistForDisplay);
            }
        } else if (!canLoadLyrics) {
            // 若無法取得可信 title，避免用歌詞行/未知值去檢索歌詞；保留現有歌詞顯示
            LogHelper.d(TAG, "🛡️ 無可信曲名，跳過歌詞檢索以避免誤識別");
        } else if (canLoadLyrics) {
            LogHelper.d(TAG, "🛡️ 同曲目已触发歌词加载，跳过重复 loadLyrics");
        }
    }

    private static class TitleArtist {
        final String title;
        final String artist;
        TitleArtist(String title, String artist) {
            this.title = title;
            this.artist = artist;
        }
    }

    private TitleArtist getTitleArtistFromActiveMusicNotificationCached(String targetPackage) {
        if (targetPackage == null || targetPackage.isEmpty()) return null;
        long now = System.currentTimeMillis();
        if (targetPackage.equals(lastNotificationLookupPkg)
                && (now - lastNotificationLookupAtMs) < NOTIFICATION_SCAN_THROTTLE_MS) {
            return lastNotificationLookupResult;
        }
        TitleArtist resolved = getTitleArtistFromActiveMusicNotification(targetPackage);
        lastNotificationLookupPkg = targetPackage;
        lastNotificationLookupAtMs = now;
        lastNotificationLookupResult = resolved;
        return resolved;
    }

    private TitleArtist getTitleArtistFromActiveMusicNotification(String targetPackage) {
        if (targetPackage == null || targetPackage.isEmpty()) return null;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
            MiRootNotificationListenerService notificationService = MiRootNotificationListenerService.getInstance();
            if (notificationService == null) return null;
            android.service.notification.StatusBarNotification[] notifications = notificationService.getActiveNotifications();
            if (notifications == null) return null;
            for (android.service.notification.StatusBarNotification sbn : notifications) {
                if (!targetPackage.equals(sbn.getPackageName())) continue;
                android.app.Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;
                String title = n.extras.getString(android.app.Notification.EXTRA_TITLE, "");
                String text = n.extras.getString(android.app.Notification.EXTRA_TEXT, "");
                // 通知 text 在某些播放器上是歌手/專輯/或歌詞行，這裡也做過濾
                title = pickNonLyricString(title);
                String artist = pickNonLyricString(text);
                if ((title != null && !title.isEmpty()) || (artist != null && !artist.isEmpty())) {
                    return new TitleArtist(title, artist);
                }
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "從活動通知提取曲名失敗: " + t.getMessage());
        }
        return null;
    }

    private String pickNonLyricString(String... candidates) {
        if (candidates == null) return "";
        for (String c : candidates) {
            if (c == null) continue;
            String t = c.trim();
            if (t.isEmpty()) continue;
            if (isLikelyLyricLine(t)) continue;
            return t;
        }
        return "";
    }

    private String safeToString(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private String buildTrackSignature(MediaMetadata metadata, String packageName) {
        if (metadata == null) return "";
        try {
            String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (mediaId != null && !mediaId.isEmpty()) {
                return packageName + "|mid:" + mediaId;
            }
        } catch (Throwable ignored) {}

        String album = "";
        String artist = "";
        String title = "";
        long duration = 0L;
        long trackNumber = 0L;
        try {
            String a = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
            if (a != null) album = a;
        } catch (Throwable ignored) {}
        try {
            String ar = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (ar != null) artist = ar;
        } catch (Throwable ignored) {}
        try {
            String t = pickNonLyricString(
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                safeToString(metadata.getDescription() != null ? metadata.getDescription().getTitle() : null),
                null
            );
            if (t != null) title = t;
        } catch (Throwable ignored) {}
        try {
            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {}
        try {
            trackNumber = metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER);
        } catch (Throwable ignored) {}

        // 关键：fallback 签名加入「过滤后的 title」。
        // 某些播放器会长期给空 mediaId/album/trackNumber，若不含 title 会把下一首误判为同曲目，导致歌词停留在上一首。
        return packageName + "|ttl:" + title + "|alb:" + album + "|art:" + artist + "|dur:" + duration + "|trk:" + trackNumber;
    }

    /**
     * 酷狗等播放器常先后推送：先无 mediaId、时长为 0，再补全。此类变化不应视为切歌。
     */
    private static boolean isSameTrackMetadataSignature(String newSig,
                                                       String oldSig,
                                                       String lockedTitle,
                                                       String lockedArtist,
                                                       String rawTitle,
                                                       String rawArtist) {
        if (TextUtils.isEmpty(newSig) || TextUtils.isEmpty(oldSig)) {
            return false;
        }
        if (newSig.equals(oldSig)) {
            return true;
        }
        String newMid = extractSignatureMediaId(newSig);
        String oldMid = extractSignatureMediaId(oldSig);
        if (!TextUtils.isEmpty(newMid) && newMid.equals(oldMid)) {
            return true;
        }
        String title = !TextUtils.isEmpty(lockedTitle) ? lockedTitle : rawTitle;
        String artist = !TextUtils.isEmpty(lockedArtist) ? lockedArtist : rawArtist;
        if (!TextUtils.isEmpty(title)
            && metadataSignatureMatchesTitleArtist(newSig, title, artist)
            && metadataSignatureMatchesTitleArtist(oldSig, title, artist)) {
            return true;
        }
        return normalizeMetadataSignatureForCompare(newSig).equals(normalizeMetadataSignatureForCompare(oldSig));
    }

    private static String extractSignatureMediaId(String signature) {
        if (signature == null) {
            return "";
        }
        int idx = signature.indexOf("|mid:");
        if (idx < 0) {
            return "";
        }
        return signature.substring(idx + 5);
    }

    private static boolean metadataSignatureMatchesTitleArtist(String signature, String title, String artist) {
        if (TextUtils.isEmpty(signature) || TextUtils.isEmpty(title)) {
            return false;
        }
        String normTitle = normalizeSignatureToken(title);
        String sig = normalizeMetadataSignatureForCompare(signature);
        if (!sig.contains("|ttl:" + normTitle)) {
            return false;
        }
        if (TextUtils.isEmpty(artist)) {
            return true;
        }
        return sig.contains("|art:" + normalizeSignatureToken(artist));
    }

    private static String normalizeSignatureToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("\t", "");
    }

    private static String normalizeMetadataSignatureForCompare(String signature) {
        if (signature == null) {
            return "";
        }
        return signature.replaceAll("\\|dur:\\d+", "|dur:*");
    }

    private boolean isSameSongTrackKey(String newTrackKey, String oldTrackKey, String title, String artist) {
        if (TextUtils.isEmpty(newTrackKey)) {
            return false;
        }
        if (!TextUtils.isEmpty(oldTrackKey)) {
            if (newTrackKey.equals(oldTrackKey)) {
                return true;
            }
            if (isLikelySameTrackDurationBucketRefinement(oldTrackKey, newTrackKey)) {
                return true;
            }
        }
        if (TextUtils.isEmpty(title)) {
            return false;
        }
        String probe = buildTrackKey(title, artist != null ? artist : "", resolveCurrentLyricsPackageName(), 0L);
        String probeNew = newTrackKey;
        int i = probe.lastIndexOf("||");
        int j = probeNew.lastIndexOf("||");
        if (i >= 0 && j >= 0) {
            return probe.regionMatches(0, probeNew, 0, i);
        }
        return false;
    }

    private boolean isLikelyLyricLine(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;

        // 常見 LRC/逐字/歌詞行特徵
        if (t.contains("\n")) return true;
        if (t.startsWith("[") && t.contains("]")) return true; // [00:12.34]
        if (t.contains("♪") || t.contains("♩") || t.contains("♫")) return true;
        if (t.contains("【") && t.contains("】")) return true; // 一些播放器用作歌詞行/提示

        // 過長的「句子」更像歌詞，不像歌名（保守取值）
        if (t.length() >= 28) return true;

        // 含大量標點的短句也更像歌詞
        int punct = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("，。！？、；：,.!?;:~～".indexOf(c) >= 0) punct++;
        }
        if (punct >= 2) return true;

        // 針對車機常見的「單行中文句子型歌詞」做額外判斷：
        // - 含有常見主語/代詞/動詞，且總長度適中，更像一句話而非歌名
        if (t.length() >= 12) {
            String[] lyricHints = {" 我", "你", "他", "她", "它", "在", "要", "會", "把", "讓", "别", "別", "不要", "如果", "因為", "因为"};
            int hits = 0;
            for (String hint : lyricHints) {
                if (t.contains(hint)) {
                    hits++;
                    if (hits >= 2) {
                        return true;
                    }
                }
            }
        }

        // 默認視為不是明顯歌詞行
        return false;
    }

    private String safeShort(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        if (t.length() <= 40) return t;
        return t.substring(0, 37) + "...";
    }

    /** 判斷是否像「車載藍牙當前行歌詞」：單行、無 LRC 時間軸，用這種會覆蓋 API 完整 LRC 導致跳動 */
    private boolean isLikelySingleCurrentLyricLine(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        // 完整 LRC：有多行 或 含 [00:xx.xx] 格式
        if (t.contains("\n") && t.split("\n").length >= 3) return false;
        if (java.util.regex.Pattern.matches(".*\\[\\d{1,2}:\\d{2}\\.\\d{2,3}\\].*", t)) return false;
        if (java.util.regex.Pattern.matches(".*\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{2}\\].*", t)) return false;
        // 逐行时间戳/KRC 词时间轴存在时，视为有效歌词而非“当前行”。
        if (java.util.regex.Pattern.matches(".*<\\d+(?:,\\d+)?>.*", t)) return false;
        // 过短且标点稀少，通常是车载蓝牙“当前行”注入（例如一小句）。
        int punctuationCount = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (",，.。!！?？、;；:：".indexOf(c) >= 0) {
                punctuationCount++;
            }
        }
        return !t.contains("\n") && t.length() < 220 && punctuationCount <= 1;
    }

    private boolean isBluetoothLyricsPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase();
        return p.contains("bluetooth") || "com.android.bluetooth".equals(p);
    }

    private boolean shouldFilterSingleLineLyrics(String lyrics, SingleLineLyricsOrigin origin, String sourcePackage) {
        if (!isLikelySingleCurrentLyricLine(lyrics)) {
            return false;
        }
        // SuperLyricApi 结果不经过这里；保留对旧枚举值的兼容处理。
        if (origin == SingleLineLyricsOrigin.HOOK) {
            return false;
        }
        // 仅对蓝牙 / 通知来源做单行歌词过滤。
        return origin == SingleLineLyricsOrigin.NOTIFICATION
            || isBluetoothLyricsPackage(sourcePackage);
    }
    
    /**
     * 加载歌词（多级回退策略）
     */
    private void loadLyrics(String title, String artist) {
        if (title == null || title.isEmpty()) {
            LogHelper.w(TAG, "⚠ 歌曲标题为空，无法加载歌词");
            return;
        }
        // 車載藍牙歌詞：禁止用歌詞行去檢索歌詞，必須用真實曲名
        if (isLikelyLyricLine(title)) {
            LogHelper.d(TAG, "🛡️ loadLyrics: 標題疑似歌詞行，跳過檢索以避免誤用歌詞搜歌詞");
            return;
        }
        
        final String trackKey = buildTrackKey(title, artist, resolveCurrentLyricsPackageName(), stableDurationMs);
        final long now = System.currentTimeMillis();
        // 同曲且已经有可用歌词时，不再重复走加载链，避免元数据抖动导致重复调用 API
        if (trackKey.equals(currentTrackKey) && enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
            LogHelper.d(TAG, "🛡️ loadLyrics 防重：同曲目已存在歌词，跳过重载");
            return;
        }
        if (isLikelySameTrackDurationBucketRefinement(currentTrackKey, trackKey)
            && enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
            currentTrackKey = trackKey;
            lastLoadLyricsTrackKey = trackKey;
            lastLoadLyricsTime = now;
            cancelPendingNoLyrics();
            LogHelper.d(TAG, "🛡️ loadLyrics：仅时长桶由未知补全，保留已有歌词不重载");
            return;
        }
        // 防抖：同曲目短時間內不重載，避免 metadata 頻繁變化導致覆蓋 API 歌詞、請求亂序出現「無歌詞」
        if (trackKey.equals(lastLoadLyricsTrackKey) && (now - lastLoadLyricsTime) < LOAD_LYRICS_DEBOUNCE_MS) {
            LogHelper.d(TAG, "🛡️ loadLyrics 防抖：同曲目 " + (LOAD_LYRICS_DEBOUNCE_MS / 1000) + " 秒內跳過重載");
            return;
        }
        if (trackKey.equals(currentTrackKey) && trackKey.equals(inflightApiTrackKey)) {
            LogHelper.d(TAG, "🛡️ loadLyrics 防重：同曲目请求进行中，跳过重复触发");
            return;
        }
        lastLoadLyricsTrackKey = trackKey;
        lastLoadLyricsTime = now;

        // 切歌：按优先级重新获取（酷我专用解析 > 网络API > SuperLyricApi 兜底），并清空旧歌词避免跨曲串词。
        if (!trackKey.equals(currentTrackKey)) {
            cancelPendingNoLyrics();
            LogHelper.d(TAG, "🔄 检测到切歌，按优先级重新获取歌词");
        }
        
        // 每次进入都生成一个新的请求序号；任何异步结果只有在序号匹配时才允许更新UI
        final int requestSeq = ++lyricsRequestSeq;
        currentTrackKey = trackKey;
        cancelPendingNoLyrics();

        // 先建立本曲目的来源策略（先尝试网络 API，失败后再走 SuperLyricApi 兜底）
        beginLyricsAcquisitionForTrack(resolveCurrentLyricsPackageName(), trackKey, requestSeq, title, artist);
        
        boolean willFetchFromThirdPartyApi = false;
        try {
            android.content.ComponentName notificationListener = new android.content.ComponentName(this, MiRootNotificationListenerService.class);
            String lyrics;

            if (shouldUseKuwoNativeLyricsPath(resolveCurrentLyricsPackageName())) {
                // 酷我车载：仅使用直连会话 extras 中的 AUDIO_LYRIC JSON，不使用通知/通用 API
                lyrics = "";
                if (mediaController != null) {
                    try {
                        // 如果广播已提供带逐字时间戳的歌词（LYRIC_FULL），跳过 AUDIO_LYRIC
                        if (hasBroadcastWordTimestamps()) {
                            LogHelper.d(TAG, "🛡️ loadLyrics 跳过 AUDIO_LYRIC：广播逐字数据已就绪");
                            // 不 return，继续检查是否需要第三方 API 兜底
                        } else {
                            Bundle ex = mediaController.getExtras();
                            String json = ex != null ? ex.getString(KuwoAudioLyricParser.EXTRA_AUDIO_LYRIC) : null;
                            EnhancedLRCParser.ParseResult kuwoPr = KuwoAudioLyricParser.parse(json);
                            if (kuwoPr != null && kuwoPr.lines != null && !kuwoPr.lines.isEmpty()) {
                                enhancedLyricLines = kuwoPr.lines;
                                cancelPendingNoLyrics();
                                superLyricFallbackModeActive = false;
                                applyFallbackRenderingModeIfNeeded();
                                setLyricsToView(enhancedLyricLines);
                                markKuwoAudioLyricApplied();
                                LogHelper.d(TAG, "✅ 酷我 AUDIO_LYRIC 已解析: " + enhancedLyricLines.size() + " 行");
                                return;
                            }
                        }
                    } catch (Exception e) {
                        LogHelper.w(TAG, "酷我歌词解析失败: " + e.getMessage());
                    }
                }
                if (lyrics == null || lyrics.isEmpty()) {
                    LogHelper.d(TAG, "酷我 AUDIO_LYRIC 无有效逐行歌词，继续等待专用解析或超时兜底");
                }
            } else if (isSuperLyricOnlySourceMode()) {
                // 仅 SuperLyric：不读取 MediaController/通知歌词，避免与模块来源混用。
                lyrics = "";
            } else {
                MusicInfoHelper.MusicInfo musicInfo = MusicInfoHelper.getMusicInfoFromMediaController(this, notificationListener);

                lyrics = musicInfo != null ? musicInfo.lyrics : "";
                // 車載藍牙歌詞：通知/MediaController 經常只給「當前一行」；用單行會覆蓋 API 完整 LRC 導致跳動，故視為無效
                String mediaSourcePackage = musicInfo != null ? musicInfo.packageName : "";
                if (shouldFilterSingleLineLyrics(lyrics, SingleLineLyricsOrigin.MEDIA_CONTROLLER, mediaSourcePackage)) {
                    lyrics = "";
                    LogHelper.d(TAG, "🛡️ 忽略單行歌詞（MediaController 藍牙來源），改用 API");
                }

                // 策略1: 从MediaController获取（已在上面處理）
                // 策略2: 从通知获取
                if ((lyrics == null || lyrics.isEmpty()) && mediaController != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            MiRootNotificationListenerService notificationService = MiRootNotificationListenerService.getInstance();
                            if (notificationService != null) {
                                android.service.notification.StatusBarNotification[] notifications =
                                    notificationService.getActiveNotifications();

                                if (notifications != null) {
                                    for (android.service.notification.StatusBarNotification sbn : notifications) {
                                        if (sbn.getPackageName().equals(mediaController.getPackageName())) {
                                            MusicInfoHelper.MusicInfo notificationInfo =
                                                MusicInfoHelper.getMusicInfoFromNotification(sbn);
                                            if (notificationInfo != null && !notificationInfo.lyrics.isEmpty()) {
                                                String notifLyrics = notificationInfo.lyrics;
                                                if (!shouldFilterSingleLineLyrics(
                                                    notifLyrics,
                                                    SingleLineLyricsOrigin.NOTIFICATION,
                                                    sbn.getPackageName()
                                                )) {
                                                    lyrics = notifLyrics;
                                                    LogHelper.d(TAG, "✅ 从通知获取到歌词");
                                                } else {
                                                    LogHelper.d(TAG, "🛡️ 通知歌詞疑似單行，跳過");
                                                }
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LogHelper.w(TAG, "从通知获取歌词失败: " + e.getMessage());
                    }
                }
            }

            // 策略3: API 兜底（由 beginLyricsAcquisitionForTrack 发起；失败后自动转 SuperLyricApi）
            if ((lyrics == null || lyrics.isEmpty()) && !title.isEmpty()
                && !shouldUseKuwoNativeLyricsPath(resolveCurrentLyricsPackageName())) {
                willFetchFromThirdPartyApi = true;
            }
            
            // 解析并显示歌词（如果已经获取到；仅 SuperLyric 模式不走此通道）
            if (!isSuperLyricOnlySourceMode() && lyrics != null && !lyrics.isEmpty()) {
                final String notificationLyrics = lyrics;
                lyricsParseExecutor.execute(() -> {
                    try {
                        EnhancedLRCParser.ParseResult result = parseLyricsContent(notificationLyrics);
                        runOnUiThread(() -> {
                            if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                                return;
                            }
                            if (result == null || result.lines == null || result.lines.isEmpty()) {
                                LogHelper.w(TAG, "⚠ 通知歌词解析后为空，保留已有歌词。原始歌词长度: " + notificationLyrics.length());
                                return;
                            }
                            if (qishuiAwaitingNetworkLyrics
                                && isMixedLyricsSourceMode()
                                && MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending(
                                    resolveCurrentLyricsPackageName(), true)) {
                                LogHelper.d(TAG, "🛡️ 汽水等待 qsgc 网络 API，忽略通知/Media 多行歌词");
                                return;
                            }
                            if (isMixedLyricsSourceMode()) {
                                prepareNetworkApiLyricLines(result.lines);
                            }
                            enhancedLyricLines = result.lines;
                            cancelPendingNoLyrics();
                            if (isMixedLyricsSourceMode() && !enhancedLyricLines.isEmpty()) {
                                markStructuredLyricsForWordFusion(
                                    resolveCurrentLyricsPackageName(),
                                    LyricsRuntimeSource.NETWORK,
                                    ""
                                );
                            }
                            setLyricsToView(enhancedLyricLines);
                            if (isMixedLyricsSourceMode()) {
                                tryFlushCachedSuperLyricWordFusion();
                            }
                            LogHelper.d(TAG, "🎤 歌词已解析: " + enhancedLyricLines.size() + " 行"
                                + (isMixedLyricsSourceMode() ? "（智能切换·可逐字融合）" : ""));
                        });
                    } catch (Exception e) {
                        LogHelper.e(TAG, "解析通知歌词时发生异常: " + e.getMessage(), e);
                    }
                });
            } else if (title.isEmpty()) {
                // 如果没有音乐信息，清空歌词
                scheduleNoLyrics(requestSeq, trackKey);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "加载歌词失败", e);
            // 不立即清空，等待API结果
        }
        // 未走第三方 API 时（如酷我等仅等 extras），本轮结束后仍无歌词则延迟显示「暂无」；已发起 API 的由成功/失败回调里 schedule/cancel
        if (!title.isEmpty()
            && (enhancedLyricLines == null || enhancedLyricLines.isEmpty())
            && !willFetchFromThirdPartyApi
            && !(shouldUseKuwoNativeLyricsPath(resolveCurrentLyricsPackageName()) && kuwoPostNativeFallbackScheduled)) {
            scheduleNoLyrics(requestSeq, trackKey, NO_LYRICS_UI_DELAY_MS);
        }
    }
    
    /**
     * 更新播放状态
     */
    private void updatePlaybackState() {
        maybeRefreshMediaControllerForActivePlayer();
        if (mediaController == null) {
            return;
        }
        
        android.media.session.PlaybackState state = mediaController.getPlaybackState();
        if (state == null) {
            return;
        }
        int playState = state.getState();
        boolean isPlaying = (playState == android.media.session.PlaybackState.STATE_PLAYING);

        if (lastPlayingState == null) {
            applyPlaybackState(isPlaying, state);
            return;
        }

        // 状态未变化：若存在反向待应用状态，说明是抖动回弹，取消待应用。
        if (lastPlayingState == isPlaying) {
            if (pendingPlaybackState != null && pendingPlaybackState != isPlaying) {
                cancelPendingPlaybackStateApply();
                if (BuildConfig.DEBUG) {
                    LogHelper.d(TAG, "⏱ 播放状态抖动恢复，取消待应用状态: " + (isPlaying ? "暂停" : "播放"));
                }
            }
            return;
        }

        // 状态切换防抖：等待 200ms，确认状态稳定后再应用，避免歌词跳动与重复刷新。
        if (pendingPlaybackState != null && pendingPlaybackState == isPlaying) {
            return;
        }
        schedulePlaybackStateApply(isPlaying);
    }

    private void schedulePlaybackStateApply(boolean targetState) {
        cancelPendingPlaybackStateApply();
        pendingPlaybackState = targetState;
        pendingPlaybackStateRunnable = () -> {
            pendingPlaybackStateRunnable = null;
            pendingPlaybackState = null;
            if (mediaController == null) return;
            android.media.session.PlaybackState latest = mediaController.getPlaybackState();
            if (latest == null) return;
            boolean latestPlaying = latest.getState() == android.media.session.PlaybackState.STATE_PLAYING;
            if (latestPlaying != targetState) {
                if (BuildConfig.DEBUG) {
                    LogHelper.d(TAG, "⏱ 播放状态抖动，放弃应用: " + (targetState ? "播放" : "暂停"));
                }
                return;
            }
            applyPlaybackState(targetState, latest);
        };
        uiHandler.postDelayed(pendingPlaybackStateRunnable, STATE_CHANGE_DEBOUNCE_MS);
    }

    private void cancelPendingPlaybackStateApply() {
        if (pendingPlaybackStateRunnable != null) {
            try {
                uiHandler.removeCallbacks(pendingPlaybackStateRunnable);
            } catch (Exception ignored) {}
        }
        pendingPlaybackStateRunnable = null;
        pendingPlaybackState = null;
    }

    private void applyPlaybackState(boolean isPlaying, android.media.session.PlaybackState state) {
        long currentTime = System.currentTimeMillis();
        boolean stateChanged = (lastPlayingState == null || lastPlayingState != isPlaying);
        lastPlayingState = isPlaying;
        lastStateChangeTime = currentTime;

        // 只在状态真正变化时才输出日志
        if (stateChanged) {
            LogHelper.d(TAG, "播放状态变化: " + (isPlaying ? "播放" : "暂停"));
        }

        // 更新播放/暂停按钮（只在状态变化时更新）
        if (stateChanged && playPauseButton != null) {
            if (isPlaying) {
                playPauseButton.setText("⏸");
            } else {
                playPauseButton.setText("▶");
            }
        }
        if (lyricsView != null) {
            lyricsView.setPlaybackActive(isPlaying);
        }

        if (lyricsAnimator != null) {
            if (isPlaying) {
                long position = state.getPosition();
                long updateTime = state.getLastPositionUpdateTime();
                // 只在状态变化时才校准位置，否则由定时同步处理
                if (stateChanged) {
                    lyricsAnimator.calibratePosition(position, updateTime);
                    lyricsAnimator.resume();
                }
            } else {
                // 暂停时始终执行（确保及时暂停）
                lyricsAnimator.pause();
            }
        }

        if (stateChanged) {
            maybeRefreshMediaControllerForActivePlayer();
        }
    }
    
    /**
     * 切换控制按钮的显示/隐藏状态
     */
    private void toggleButtonsVisibility() {
        if (buttonLayout == null) {
            return;
        }
        
        buttonsVisible = !buttonsVisible;
        setVisibilityIfChanged(buttonLayout, buttonsVisible ? View.VISIBLE : View.GONE);
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "🔄 控制按钮状态已切换: " + (buttonsVisible ? "显示" : "隐藏"));
        }
    }
    
    /**
     * 检查通知监听权限
     */
    private boolean checkNotificationListenerPermission() {
        String packageName = getPackageName();
        String flat = android.provider.Settings.Secure.getString(
            getContentResolver(),
            "enabled_notification_listeners"
        );
        
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        
        String[] names = flat.split(":");
        for (String name : names) {
            ComponentName cn = ComponentName.unflattenFromString(name);
            if (cn != null && packageName.equals(cn.getPackageName())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 打开通知监听权限设置页面
     */
    private void openNotificationListenerSettings() {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            LogHelper.e(TAG, "打开通知监听设置失败", e);
        }
    }
    
    /**
     * 切换跑马灯显示/隐藏状态，并同步保存到设置
     */
    private void toggleMarqueeLight() {
        marqueeLightEnabled = !marqueeLightEnabled;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_MARQUEE_LIGHT, marqueeLightEnabled);
        if (marqueeLightEnabled) {
            editor.putBoolean(KEY_ABYSSAL_MIRROR, false);
            abyssalMirrorEnabled = false;
        }
        editor.apply();

        if (marqueeLightView != null) {
            boolean shouldBeVisible = marqueeLightEnabled || neonBorderEnabled;
            setVisibilityIfChanged(marqueeLightView, shouldBeVisible ? View.VISIBLE : View.GONE);
            marqueeLightView.setBorderFrameEnabled(neonBorderEnabled);
            marqueeLightView.setNeonEffectsEnabled(neonDisplayEnabled);
            marqueeLightView.setMarqueeLightEnabled(marqueeLightEnabled);
        }

        applySettings();
        LogHelper.d(TAG, "🔄 跑马灯状态已切换: " + (marqueeLightEnabled ? "显示" : "隐藏") + "，设置已保存");
    }
    
    /**
     * 设置歌词数据（同步到当前显示的视图）
     */
    private void setLyricsToView(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        try {
            String applyingTrackKey = currentTrackKey != null ? currentTrackKey : "";
            String fingerprint = buildLyricsFingerprint(lines);
            if (TextUtils.equals(lastAppliedLyricsTrackKey, applyingTrackKey)
                && TextUtils.equals(lastAppliedLyricsFingerprint, fingerprint)) {
                if (!abyssalMirrorEnabled && lyricsView != null) {
                    lyricsView.setTrackLoading(false);
                }
                return;
            }
            lastAppliedLyricsTrackKey = applyingTrackKey;
            lastAppliedLyricsFingerprint = fingerprint;
            // 新歌词数据生效时重置“按行刷新”索引，确保首帧会应用正确文本。
            lastAbyssalRenderedLineIndex = -1;
            if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
                // 新的ViewGroup版本：保存歌词数据，并显示当前行（禁止将 enhancedLyricLines 置为 null，后续 loadLyrics 等会调 isEmpty）
                if (lines == null) {
                    if (enhancedLyricLines == null) {
                        enhancedLyricLines = new ArrayList<>();
                    } else {
                        enhancedLyricLines.clear();
                    }
                } else {
                    enhancedLyricLines = lines;
                }
                int lineCount = lines != null ? lines.size() : 0;
                
                long currentPosition = resolveLyricsAnchorPositionMs();
                
                if (lines != null && !lines.isEmpty()) {
                    int currentIndex = findCurrentLineIndexForAbyssal(lines, currentPosition);
                    if (currentIndex >= 0 && currentIndex < lines.size()) {
                        String currentLyric = lines.get(currentIndex).text;
                        abyssalMirrorLyricsViewGroup.setLyric(currentLyric);
                        LogHelper.d(TAG, "✅ 已设置歌词数据到深渊镜视图: " + lineCount + " 行，当前行=" + currentIndex);
                    } else {
                        EnhancedLRCParser.EnhancedLyricLine first = lines.get(0);
                        String t = first != null ? first.text : "";
                        abyssalMirrorLyricsViewGroup.setLyric(
                            t != null ? t : "",
                            false);
                    }
                } else {
                    String noLyrics = getString(R.string.music_no_lyrics);
                    abyssalMirrorLyricsViewGroup.setLyric(noLyrics);
                }
            } else if (abyssalMirrorEnabled && abyssalMirrorLyricsView != null) {
                // 旧版3D旋转版本（兼容）
                int lineCount = lines != null ? lines.size() : 0;
                abyssalMirrorLyricsView.setLyricLines(lines);
                LogHelper.d(TAG, "✅ 已设置歌词数据到深渊镜视图（旧版）: " + lineCount + " 行");
            } else if (lyricsView != null) {
                if (lines != null && !lines.isEmpty()) {
                    lyricsView.setTrackLoading(false);
                    long playbackPosition = resolveLyricsAnchorPositionMs();
                    syncLyricsRenderPositionCache(playbackPosition);
                    lyricsView.setLyricsPreservingPlaybackAnchor(lines, playbackPosition, -1);
                    lyricsView.centerCurrentLineImmediately();
                } else {
                    // V3.17+: 不在此清除 trackLoading，切歌时由 beginLyricsAcquisitionForTrack 置 true，
                    // 防止 SuperLyric 回退推送上一首歌词。trackLoading 由歌词就绪或 scheduleNoLyrics 超时清除。
                    syncLyricsRenderPositionCache(0L);
                    lyricsView.setLyrics(lines);
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 设置歌词数据失败", e);
        }
    }

    private String buildLyricsFingerprint(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        if (lines == null || lines.isEmpty()) return "empty";
        EnhancedLRCParser.EnhancedLyricLine first = lines.get(0);
        EnhancedLRCParser.EnhancedLyricLine last = lines.get(lines.size() - 1);
        long firstTime = first != null ? first.time : -1L;
        long lastTime = last != null ? last.time : -1L;
        int firstHash = first != null && first.text != null ? first.text.hashCode() : 0;
        int lastHash = last != null && last.text != null ? last.text.hashCode() : 0;
        // 计入逐字时间戳总数：从无逐字升级到有逐字（如 AUDIO_LYRIC → 酷我广播 LYRIC_FULL）
        // 时不会被指纹去重误判为同源，确保升级到带逐字数据的来源能立即生效。
        long wordCount = 0L;
        for (EnhancedLRCParser.EnhancedLyricLine line : lines) {
            if (line != null && line.wordTimestamps != null) {
                wordCount += line.wordTimestamps.size();
            }
        }
        return lines.size() + "|" + firstTime + "|" + lastTime + "|" + firstHash + "|" + lastHash + "|w" + wordCount;
    }

    private String buildTrackKey(String title, String artist, String packageName, long durationMs) {
        String t = title == null ? "" : title.trim().toLowerCase();
        String a = artist == null ? "" : artist.trim().toLowerCase();
        String p = packageName == null ? "" : packageName.trim().toLowerCase();
        long durationBucket = durationMs > 0 ? (durationMs / 1000L) : 0L;
        return p + "||" + t + "||" + a + "||" + durationBucket;
    }

    /**
     * 曲目键仅「时长桶」从 0（未知）变为大于 0（播放器后补）时视为同一曲。
     * <p>
     * 否则 {@link #loadLyrics} 会误判切歌并走 {@link #beginLyricsAcquisitionForTrack} 清空视图，
     * 已拿到的歌词会被 {@code setLyricsToView(null)} 短暂打成「暂无歌词」。
     */
    private static boolean isLikelySameTrackDurationBucketRefinement(String oldTrackKey, String newTrackKey) {
        if (oldTrackKey == null || newTrackKey == null) return false;
        if (oldTrackKey.equals(newTrackKey)) return true;
        int i = oldTrackKey.lastIndexOf("||");
        int j = newTrackKey.lastIndexOf("||");
        if (i < 0 || j < 0 || i != j) return false;
        if (!oldTrackKey.regionMatches(0, newTrackKey, 0, i + 2)) {
            return false;
        }
        String oldDur = oldTrackKey.substring(i + 2);
        String newDur = newTrackKey.substring(j + 2);
        try {
            long oldB = Long.parseLong(oldDur);
            long newB = Long.parseLong(newDur);
            return oldB == 0L && newB > 0L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String resolveCurrentLyricsPackageName() {
        try {
            if (mediaController != null && mediaController.getPackageName() != null && !mediaController.getPackageName().isEmpty()) {
                return mediaController.getPackageName();
            }
        } catch (Exception ignored) {}
        if (stableTrackSignature != null && !stableTrackSignature.isEmpty()) {
            int idx = stableTrackSignature.indexOf('|');
            if (idx > 0) {
                return stableTrackSignature.substring(0, idx);
            }
        }
        return "";
    }

    private boolean isLyricsRequestCurrent(int requestSeq, String trackKey) {
        return requestSeq == lyricsRequestSeq && trackKey != null && trackKey.equals(currentTrackKey) && !isFinishing();
    }

    private boolean isSuperLyricRealtimeTrackMatched(String callbackTitle, String callbackArtist) {
        String stableT = normalizeLyricToken(stableTitle);
        String stableA = normalizeLyricToken(stableArtist);
        String cbT = normalizeLyricToken(callbackTitle);
        String cbA = normalizeLyricToken(callbackArtist);
        if (TextUtils.isEmpty(stableT)) {
            return true;
        }
        if (TextUtils.isEmpty(cbT)) {
            return false;
        }
        if (!softLyricTokenEquals(stableT, cbT)) {
            return false;
        }
        if (TextUtils.isEmpty(stableA) || TextUtils.isEmpty(cbA)) {
            return true;
        }
        return softLyricTokenEquals(stableA, cbA);
    }

    private String normalizeLyricToken(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("\t", "")
            .replace("-", "")
            .replace("_", "")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "");
    }

    private boolean softLyricTokenEquals(String left, String right) {
        if (TextUtils.isEmpty(left) || TextUtils.isEmpty(right)) return false;
        return left.equals(right) || left.contains(right) || right.contains(left);
    }

    private EnhancedLRCParser.ParseResult parseLyricsContent(String rawLyrics) {
        EnhancedLRCParser.ParseResult result = EnhancedLRCParser.parse(rawLyrics);
        if (result == null || result.lines == null || result.lines.isEmpty()) {
            result = EnhancedLRCParser.parseAsPlainText(rawLyrics);
        }
        return result;
    }

    private long getCurrentPlaybackPositionSafe() {
        try {
            if (mediaController != null && mediaController.getPlaybackState() != null) {
                return Math.max(0L, mediaController.getPlaybackState().getPosition());
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private void syncLyricsRenderPositionCache(long positionMs) {
        latestLyricsRenderPositionMs = Math.max(0L, positionMs);
    }

    /**
     * 新歌词入库/切歌后用于定位当前行与 scrollY；勿把上首残留的 {@link #latestLyricsRenderPositionMs} 与新媒体进度盲目取 max。
     */
    private long resolveLyricsAnchorPositionMs() {
        long mediaPos = getCurrentPlaybackPositionSafe();
        long sinceTrackMs = lyricsTrackSettledAtMs > 0L
            ? SystemClock.uptimeMillis() - lyricsTrackSettledAtMs
            : Long.MAX_VALUE;
        if (sinceTrackMs < TRACK_CHANGE_PLAYBACK_TRUST_MS) {
            return mediaPos;
        }
        long cached = Math.max(0L, latestLyricsRenderPositionMs);
        if (cached <= 0L) {
            return mediaPos;
        }
        if (mediaPos <= 0L) {
            return cached;
        }
        if (mediaPos + TRACK_CHANGE_CACHED_POSITION_GAP_MS < cached) {
            return mediaPos;
        }
        return Math.max(mediaPos, cached);
    }

    /**
     * 智能切换拉词中禁止用上首 SuperLyric 单句覆盖；汽水等待网络时的单句预览除外。
     */
    private boolean shouldAllowSuperLyricSingleLineFallback(String sourcePkg,
                                                            int requestSeq,
                                                            String trackKey) {
        if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
            return false;
        }
        if (isSuperLyricOnlySourceMode()) {
            return true;
        }
        if (isMixedLyricsSourceMode()) {
            if (hasMixedModeNetworkLyricsStructure()) {
                return false;
            }
            if (MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending(sourcePkg, true)
                && (qishuiAwaitingNetworkLyrics || superLyricFallbackModeActive)) {
                return true;
            }
            if (lyricsView != null && lyricsView.isTrackLoading()) {
                return false;
            }
            return enhancedLyricLines == null || enhancedLyricLines.isEmpty();
        }
        return superLyricFallbackModeActive
            || enhancedLyricLines == null
            || enhancedLyricLines.isEmpty();
    }

    private void forceRefreshLyricsViewForSuperLyric(boolean restartWordProgress) {
        if (lyricsView == null) return;
        long playbackPosition = resolveLyricsAnchorPositionMs();
        lyricsView.updatePosition(playbackPosition);
        // SuperLyric 兜底会复用句子对象，强制触发行号/高亮/逐字刷新，避免同句数据更新被去重后不重绘。
        if (restartWordProgress) {
            lyricsView.refreshCurrentLine();
        } else {
            lyricsView.postInvalidateOnAnimation();
            lyricsView.invalidate();
        }
    }

    private void unlockFromSuperLyricFallbackAndApplyNetworkLyrics(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                                   String sourcePkg) {
        unlockFromSuperLyricFallbackAndApplyNetworkLyrics(
            lines, sourcePkg, LyricsRuntimeSource.NETWORK, null, "");
    }

    private void unlockFromSuperLyricFallbackAndApplyNetworkLyrics(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                                   String sourcePkg,
                                                                   LyricsRuntimeSource networkSource) {
        unlockFromSuperLyricFallbackAndApplyNetworkLyrics(lines, sourcePkg, networkSource, null, "");
    }

    private void unlockFromSuperLyricFallbackAndApplyNetworkLyrics(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                                   String sourcePkg,
                                                                   LyricsRuntimeSource networkSource,
                                                                   String strategyLabel) {
        unlockFromSuperLyricFallbackAndApplyNetworkLyrics(
            lines, sourcePkg, networkSource, strategyLabel, "");
    }

    private void unlockFromSuperLyricFallbackAndApplyNetworkLyrics(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                                   String sourcePkg,
                                                                   LyricsRuntimeSource networkSource,
                                                                   String strategyLabel,
                                                                   String networkProvider) {
        final boolean smoothUpgrade = MusicPlayerLyricsPolicy.prefersNetworkLyricsSmoothUpgrade(
            sourcePkg, isMixedLyricsSourceMode(), superLyricFallbackModeActive);
        cancelPendingQishuiSuperLyricPreviewWait();
        qishuiAwaitingNetworkLyrics = false;
        prepareNetworkApiLyricLines(lines);
        enhancedLyricLines = lines;
        cancelPendingNoLyrics();
        final boolean wasSuperLyricPreview = superLyricFallbackModeActive;
        markStructuredLyricsForWordFusion(
            sourcePkg,
            networkSource != null ? networkSource : LyricsRuntimeSource.NETWORK,
            networkProvider != null ? networkProvider : ""
        );
        applyFallbackRenderingModeIfNeeded();

        if (lyricsView != null) {
            long playbackPosition = resolveLyricsAnchorPositionMs();
            syncLyricsRenderPositionCache(playbackPosition);
            if (smoothUpgrade) {
                int textAnchor = findBestLineIndexForSuperLyricText(
                    lines, lastSuperLyricAppliedTextNormalized);
                int posAnchor = findCurrentLineIndexForAbyssal(lines, playbackPosition);
                int anchor = resolveQishuiUpgradeAnchor(
                    lines, playbackPosition, textAnchor, posAnchor);
                seedSuperLyricWordFusionAfterNetworkUpgrade(
                    lines, anchor, sourcePkg, wasSuperLyricPreview);
                lyricsView.setEnableWordByWord(effectiveWordByWordForSuperLyric(null));
                lyricsView.setShowTranslation(true);
                lyricsView.upgradeFromSingleLinePreview(lines, playbackPosition, anchor);
                lyricsView.setTrackLoading(false);
                if (lastWordFusionLineIndex >= 0) {
                    lyricsView.notifyWordTimestampsChanged(lastWordFusionLineIndex);
                }
                lyricsView.snapWordHighlightToPosition(playbackPosition);
                lastAppliedLyricsTrackKey = currentTrackKey != null ? currentTrackKey : "";
                lastAppliedLyricsFingerprint = buildLyricsFingerprint(lines);
                lastAbyssalRenderedLineIndex = -1;
                if (BuildConfig.DEBUG) {
                    LogHelper.d(TAG, "✅ 汽水单句→完整LRC 平滑升级: anchor=" + anchor
                        + ", fusionLine=" + lastWordFusionLineIndex
                        + ", pos=" + playbackPosition + "ms");
                }
            } else {
                lastWordFusionLineIndex = -1;
                setLyricsToView(enhancedLyricLines);
                lyricsView.postInvalidateOnAnimation();
            }
        } else {
            lastWordFusionLineIndex = -1;
            setLyricsToView(enhancedLyricLines);
        }
        if (isMixedLyricsSourceMode()) {
            tryFlushCachedSuperLyricWordFusion();
            if (smoothUpgrade && lyricsView != null && lastWordFusionLineIndex >= 0) {
                long playbackPosition = resolveLyricsAnchorPositionMs();
                lyricsView.notifyWordTimestampsChanged(lastWordFusionLineIndex);
                lyricsView.snapWordHighlightToPosition(playbackPosition);
            }
        }
        String detail = strategyLabel;
        if (detail == null || detail.trim().isEmpty()) {
            MusicPlayerLyricsPolicy.PrimaryStrategy strategy =
                MusicPlayerLyricsPolicy.resolvePrimaryStrategy(sourcePkg);
            detail = MusicPlayerLyricsPolicy.strategyDisplayLabel(strategy);
        }
        updateHookSourceStatusText(
            networkSource != null ? networkSource : LyricsRuntimeSource.NETWORK,
            detail
        );
    }

    /**
     * 汽水单句升级到完整 LRC 时，用当前 SuperLyric 句文本在完整歌词中找最佳行，减少切源跳屏。
     */
    private int findBestLineIndexForSuperLyricText(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                   String superTextNormalized) {
        if (lines == null || lines.isEmpty()
            || superTextNormalized == null || superTextNormalized.isEmpty()) {
            return -1;
        }
        double bestSim = 0.0;
        int bestIndex = -1;
        final double threshold = 0.82;
        for (int i = 0; i < lines.size(); i++) {
            EnhancedLRCParser.EnhancedLyricLine line = lines.get(i);
            if (line == null || line.text == null) {
                continue;
            }
            double sim = LyricsMatcher.similarity(superTextNormalized, line.text);
            if (sim > bestSim) {
                bestSim = sim;
                bestIndex = i;
            }
        }
        return bestSim >= threshold ? bestIndex : -1;
    }

    /**
     * 汽水单句→完整 LRC：优先播放进度锚点，文本锚点仅作 ±1 行微调。
     */
    private int resolveQishuiUpgradeAnchor(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                           long playbackPositionMs,
                                           int textAnchor,
                                           int posAnchor) {
        if (lines == null || lines.isEmpty()) {
            return -1;
        }
        if (posAnchor < 0 || posAnchor >= lines.size()) {
            return textAnchor >= 0 ? textAnchor : 0;
        }
        if (textAnchor < 0 || textAnchor >= lines.size()) {
            return posAnchor;
        }
        if (Math.abs(textAnchor - posAnchor) <= 1) {
            return posAnchor;
        }
        if (textAnchor >= 0 && textAnchor < lines.size()) {
            EnhancedLRCParser.EnhancedLyricLine textLine = lines.get(textAnchor);
            if (textLine != null && textLine.time >= 0L) {
                long end = textAnchor + 1 < lines.size()
                    ? lines.get(textAnchor + 1).time
                    : textLine.time + 8000L;
                if (playbackPositionMs >= textLine.time && playbackPositionMs < end) {
                    return textAnchor;
                }
            }
        }
        return posAnchor;
    }

    /**
     * 汽水网络完整歌词到达后，立即把 SuperLyric 逐字写入锚点附近行（升级前同步，避免逐字空白窗）。
     */
    private void seedSuperLyricWordFusionAfterNetworkUpgrade(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                             int anchorIndex,
                                                             String sourcePkg,
                                                             boolean wasSuperLyricPreview) {
        if (!isMixedLyricsSourceMode() || lines == null || lines.isEmpty()) {
            return;
        }
        int anchor = clampLyricLineIndex(lines, anchorIndex);
        if (anchor < 0) {
            return;
        }
        SuperLyricApi.SuperLyricFallbackPayload payload = cachedWordFusionPayload;
        if (payload == null || !payload.hasValidWords()) {
            payload = SuperLyricApi.peekCachedFallbackPayload(
                stableTitle != null ? stableTitle : "",
                stableArtist != null ? stableArtist : "",
                sourcePkg != null ? sourcePkg : ""
            );
        }
        List<EnhancedLRCParser.WordTimestamp> moduleWords = null;
        long moduleLineStartMs = 0L;
        String moduleText = null;
        if (payload != null && payload.hasValidWords()) {
            moduleWords = payload.wordTimestamps;
            moduleLineStartMs = payload.lineStartMs;
            moduleText = payload.text;
            cachedWordFusionPayload = payload;
        } else if (superLyricActiveLineHasWords
            && reusableSuperLyricLine.wordTimestamps != null
            && !reusableSuperLyricLine.wordTimestamps.isEmpty()) {
            moduleWords = reusableSuperLyricLine.wordTimestamps;
            moduleLineStartMs = reusableSuperLyricLine.time;
            moduleText = reusableSuperLyricLine.text;
        }
        if (moduleWords == null || moduleWords.isEmpty()) {
            return;
        }
        final double seedThreshold = 0.75;
        int primaryIndex = -1;
        int seedFrom = Math.max(0, anchor - 1);
        int seedTo = Math.min(lines.size() - 1, anchor + 1);
        for (int i = seedFrom; i <= seedTo; i++) {
            EnhancedLRCParser.EnhancedLyricLine line = lines.get(i);
            if (line == null || line.text == null) {
                continue;
            }
            boolean trustedAnchor = i == anchor && wasSuperLyricPreview;
            if (!trustedAnchor && moduleText != null && !moduleText.isEmpty()) {
                double sim = LyricsMatcher.similarity(line.text, moduleText);
                if (sim < seedThreshold
                    && !softLyricTokenEquals(normalizeLyricToken(line.text), lastSuperLyricAppliedTextNormalized)) {
                    continue;
                }
            }
            long lineTime = line.time > 0L ? line.time : Math.max(0L, moduleLineStartMs);
            long nextLineTimeMs = 0L;
            if (i + 1 < lines.size()) {
                EnhancedLRCParser.EnhancedLyricLine nextLine = lines.get(i + 1);
                if (nextLine != null && nextLine.time > 0L) {
                    nextLineTimeMs = nextLine.time;
                }
            }
            ArrayList<EnhancedLRCParser.WordTimestamp> fusedWords = null;
            if (trustedAnchor && wasSuperLyricPreview && payload != null && payload.hasValidWords()) {
                fusedWords = buildSuperLyricModuleLineWordTimestamps(
                    lineTime, line.text, payload.wordTimestamps, nextLineTimeMs, payload.lineStartMs);
            } else if (trustedAnchor
                && wasSuperLyricPreview
                && superLyricActiveLineHasWords
                && reusableSuperLyricLine.wordTimestamps != null
                && !reusableSuperLyricLine.wordTimestamps.isEmpty()) {
                fusedWords = SuperLyricWordTimestamps.reanchorAlignedWordsToLineTime(
                    reusableSuperLyricLine.wordTimestamps,
                    reusableSuperLyricLine.time,
                    lineTime
                );
            }
            if (fusedWords == null || fusedWords.isEmpty()) {
                fusedWords = buildSuperLyricModuleLineWordTimestamps(
                    lineTime, line.text, moduleWords, nextLineTimeMs, moduleLineStartMs);
            }
            if (fusedWords.isEmpty()) {
                fusedWords = SuperLyricWordTimestamps.alignToLineTime(lineTime, moduleWords);
            }
            if (fusedWords.isEmpty()) {
                continue;
            }
            line.wordTimestamps = fusedWords;
            line.moduleWordTimeline = false;
            if (i == anchor || primaryIndex < 0) {
                primaryIndex = i;
            }
        }
        if (primaryIndex >= 0) {
            lastWordFusionLineIndex = primaryIndex;
        }
    }

    /**
     * 同句且已融合行索引一致时，在 UI 线程快速对齐逐字（跳过 Matcher 线程池，降低 Binder→高亮延迟）。
     */
    private boolean tryApplySuperLyricWordFusionFastPath(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                         String sourcePkg,
                                                         int requestSeq,
                                                         String trackKey) {
        return tryApplySuperLyricWordFusionFastPath(payload, sourcePkg, requestSeq, trackKey, -1);
    }

    private boolean tryApplySuperLyricWordFusionFastPath(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                         String sourcePkg,
                                                         int requestSeq,
                                                         String trackKey,
                                                         int preferredLineIndex) {
        if (!isLyricsRequestCurrent(requestSeq, trackKey) || payload == null) {
            return false;
        }
        if (payload.wordTimestamps == null || payload.wordTimestamps.isEmpty()) {
            return false;
        }
        if (isMixedLyricsSourceMode()) {
            if (!hasMixedModeNetworkLyricsStructure()) {
                return false;
            }
        } else if (!canUseSuperLyricWordFusion()) {
            return false;
        }
        List<EnhancedLRCParser.EnhancedLyricLine> lines = enhancedLyricLines;
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        long playbackPosition = resolveLyricsAnchorPositionMs();
        double fastPathSimThreshold = MusicPlayerLyricsPolicy.appliesQishuiMixedStrictPolicy(sourcePkg, true)
            ? 0.90
            : 0.82;
        int lineIndex = clampLyricLineIndex(lines, preferredLineIndex);
        if (lineIndex < 0) {
            lineIndex = clampLyricLineIndex(
                lines, findCurrentLineIndexForAbyssal(lines, playbackPosition));
        }
        if (lineIndex < 0
            && lastWordFusionLineIndex >= 0
            && (lyricsView == null || !lyricsView.isTrackLoading())) {
            lineIndex = clampLyricLineIndex(lines, lastWordFusionLineIndex);
        }
        if (lineIndex < 0) {
            return false;
        }
        EnhancedLRCParser.EnhancedLyricLine targetLine = lines.get(lineIndex);
        if (targetLine == null || targetLine.text == null) {
            return false;
        }
        if (LyricsMatcher.similarity(targetLine.text, payload.text) < fastPathSimThreshold) {
            int playbackIndex = clampLyricLineIndex(
                lines, findCurrentLineIndexForAbyssal(lines, playbackPosition));
            if (playbackIndex >= 0 && playbackIndex != lineIndex) {
                EnhancedLRCParser.EnhancedLyricLine playbackLine = lines.get(playbackIndex);
                if (playbackLine != null
                    && playbackLine.text != null
                    && LyricsMatcher.similarity(playbackLine.text, payload.text) >= fastPathSimThreshold) {
                    lineIndex = playbackIndex;
                    targetLine = playbackLine;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
        long lineTime = targetLine.time > 0L ? targetLine.time : Math.max(0L, payload.lineStartMs);
        long nextLineTimeMs = 0L;
        if (lineIndex + 1 < lines.size()) {
            EnhancedLRCParser.EnhancedLyricLine nextLine = lines.get(lineIndex + 1);
            if (nextLine != null && nextLine.time > 0L) {
                nextLineTimeMs = nextLine.time;
            }
        }
        ArrayList<EnhancedLRCParser.WordTimestamp> fusedWords = buildSuperLyricModuleLineWordTimestamps(
            lineTime, targetLine.text, payload.wordTimestamps, nextLineTimeMs, payload.lineStartMs);
        if (fusedWords.isEmpty()) {
            fusedWords = SuperLyricWordTimestamps.alignToLineTime(lineTime, payload.wordTimestamps);
        }
        if (fusedWords.isEmpty()) {
            return false;
        }
        MixedLyricsLineMatcher.MatchResult fastMatch = new MixedLyricsLineMatcher.MatchResult(
            lineIndex,
            1.0,
            1.0,
            1.0,
            MixedLyricsLineMatcher.MatchTier.STRONG,
            true,
            preferredLineIndex >= 0 ? "fast_path_playback_line" : "fast_path_same_line"
        );
        commitSuperLyricWordFusion(
            new PendingWordFusion(requestSeq, trackKey, sourcePkg, payload, lineIndex, fastMatch, fusedWords),
            wordFusionGeneration.get()
        );
        return true;
    }

    /** 行级比对未过但当前播放行文本与模块句一致且已有逐字：保持模块轴，不回退行时间戳推算。 */
    private boolean tryApplyStickyWordFusionOnPlaybackLine(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                           String sourcePkg,
                                                           int requestSeq,
                                                           String trackKey,
                                                           long playbackPosition) {
        if (payload == null || !payload.hasValidWords()) {
            return false;
        }
        List<EnhancedLRCParser.EnhancedLyricLine> lines = enhancedLyricLines;
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        int playbackIndex = clampLyricLineIndex(
            lines, findCurrentLineIndexForAbyssal(lines, playbackPosition));
        if (playbackIndex < 0) {
            return false;
        }
        EnhancedLRCParser.EnhancedLyricLine line = lines.get(playbackIndex);
        if (line == null || line.text == null) {
            return false;
        }
        double threshold = MusicPlayerLyricsPolicy.appliesQishuiMixedStrictPolicy(sourcePkg, true)
            ? 0.88
            : 0.80;
        if (LyricsMatcher.similarity(line.text, payload.text) < threshold) {
            return false;
        }
        return tryApplySuperLyricWordFusionFastPath(
            payload, sourcePkg, requestSeq, trackKey, playbackIndex);
    }

    private static final class PendingWordFusion {
        final int requestSeq;
        final String trackKey;
        final String sourcePkg;
        final SuperLyricApi.SuperLyricFallbackPayload payload;
        final int matchedIndex;
        final MixedLyricsLineMatcher.MatchTier tier;
        final double textSimilarity;
        final double wordCoverage;
        final ArrayList<EnhancedLRCParser.WordTimestamp> fusedWords;

        PendingWordFusion(int requestSeq,
                          String trackKey,
                          String sourcePkg,
                          SuperLyricApi.SuperLyricFallbackPayload payload,
                          int matchedIndex,
                          MixedLyricsLineMatcher.MatchResult match,
                          ArrayList<EnhancedLRCParser.WordTimestamp> fusedWords) {
            this.requestSeq = requestSeq;
            this.trackKey = trackKey;
            this.sourcePkg = sourcePkg;
            this.payload = payload;
            this.matchedIndex = matchedIndex;
            this.tier = match != null ? match.tier : MixedLyricsLineMatcher.MatchTier.REJECTED;
            this.textSimilarity = match != null ? match.textSimilarity : 0.0;
            this.wordCoverage = match != null ? match.wordCoverage : 0.0;
            this.fusedWords = fusedWords;
        }
    }

    private static void clearLineModuleWordTimeline(EnhancedLRCParser.EnhancedLyricLine line) {
        if (line == null) {
            return;
        }
        line.moduleWordTimeline = false;
        if (line.wordTimestamps != null) {
            line.wordTimestamps.clear();
        }
    }

    /**
     * 智能切换逐字融合（参考 1.8.4）：网络歌词在且模块有逐字时，行级比对通过后只写当前行 wordTimestamps。
     */
    private boolean applyMixedModeWordFusionPayload(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                    String sourcePkg,
                                                    int requestSeq,
                                                    String trackKey) {
        if (!isLyricsRequestCurrent(requestSeq, trackKey) || payload == null) {
            return false;
        }
        if (!hasMixedModeNetworkLyricsStructure()) {
            return false;
        }
        if (payload.wordTimestamps == null || payload.wordTimestamps.isEmpty()) {
            return false;
        }
        if (enhancedLyricLines == null || enhancedLyricLines.isEmpty()) {
            return false;
        }
        if (tryApplySuperLyricWordFusionFastPath(payload, sourcePkg, requestSeq, trackKey)) {
            return true;
        }
        long playbackPosition = resolveLyricsAnchorPositionMs();
        int anchorIndex = findCurrentLineIndexForAbyssal(enhancedLyricLines, playbackPosition);
        MusicPlayerLyricsPolicy.LineMatchStrictness lineStrictness =
            MusicPlayerLyricsPolicy.resolveLineMatchStrictness(sourcePkg, true);
        MixedLyricsLineMatcher.MatchResult match = MixedLyricsLineMatcher.match(
            enhancedLyricLines,
            payload.text,
            payload.wordTimestamps,
            payload.lineStartMs,
            playbackPosition,
            anchorIndex,
            lineStrictness
        );
        int matchedIndex = clampLyricLineIndex(enhancedLyricLines, match != null ? match.lineIndex : -1);
        if (!match.accepted || matchedIndex < 0) {
            if (tryApplyStickyWordFusionOnPlaybackLine(
                payload, sourcePkg, requestSeq, trackKey, playbackPosition)) {
                return true;
            }
            if (BuildConfig.DEBUG) {
                LogHelper.d(TAG, "⏭ 智能切换·逐字融合未通过比对: " + (match != null ? match.reason : "")
                    + ", text=" + safeShort(payload.text));
            }
            return false;
        }
        EnhancedLRCParser.EnhancedLyricLine targetLine = enhancedLyricLines.get(matchedIndex);
        if (targetLine == null) {
            return false;
        }
        long lineTime = targetLine.time > 0L ? targetLine.time : Math.max(0L, payload.lineStartMs);
        String networkLineText = targetLine.text != null ? targetLine.text : "";
        long nextLineTimeMs = 0L;
        if (matchedIndex + 1 < enhancedLyricLines.size()) {
            EnhancedLRCParser.EnhancedLyricLine nextLine = enhancedLyricLines.get(matchedIndex + 1);
            if (nextLine != null && nextLine.time > 0L) {
                nextLineTimeMs = nextLine.time;
            }
        }
        ArrayList<EnhancedLRCParser.WordTimestamp> fusedWords = buildSuperLyricModuleLineWordTimestamps(
            lineTime, networkLineText, payload.wordTimestamps, nextLineTimeMs, payload.lineStartMs);
        if (fusedWords.isEmpty()) {
            fusedWords = SuperLyricWordTimestamps.alignToLineTime(lineTime, payload.wordTimestamps);
        }
        if (fusedWords.isEmpty()) {
            return false;
        }
        commitSuperLyricWordFusion(
            new PendingWordFusion(requestSeq, trackKey, sourcePkg, payload, matchedIndex, match, fusedWords),
            wordFusionGeneration.get()
        );
        return true;
    }

    private boolean applySuperLyricWordFusionPayload(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                     String sourcePkg,
                                                     int requestSeq,
                                                     String trackKey) {
        if (!isLyricsRequestCurrent(requestSeq, trackKey) || payload == null) {
            return false;
        }
        if (payload.wordTimestamps == null || payload.wordTimestamps.isEmpty()) {
            return false;
        }
        if (isMixedLyricsSourceMode()) {
            return applyMixedModeWordFusionPayload(payload, sourcePkg, requestSeq, trackKey);
        }
        if (!canUseSuperLyricWordFusion()) {
            return false;
        }
        if (tryApplySuperLyricWordFusionFastPath(payload, sourcePkg, requestSeq, trackKey)) {
            return true;
        }
        scheduleSuperLyricOnlyWordFusion(payload, sourcePkg, requestSeq, trackKey);
        return true;
    }

    /** 仅 SuperLyric 整曲多行：后台行级比对后提交逐字。 */
    private void scheduleSuperLyricOnlyWordFusion(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                  String sourcePkg,
                                                  int requestSeq,
                                                  String trackKey) {
        final int generation = wordFusionGeneration.get();
        final List<EnhancedLRCParser.EnhancedLyricLine> lines = enhancedLyricLines;
        final long playbackPosition = resolveLyricsAnchorPositionMs();
        superLyricWordFusionExecutor.execute(() -> {
            if (generation != wordFusionGeneration.get() || lines == null || lines.isEmpty()) {
                return;
            }
            int anchorIndex = findCurrentLineIndexForAbyssal(lines, playbackPosition);
            MixedLyricsLineMatcher.MatchResult match = MixedLyricsLineMatcher.match(
                lines,
                payload.text,
                payload.wordTimestamps,
                payload.lineStartMs,
                playbackPosition,
                anchorIndex,
                MusicPlayerLyricsPolicy.resolveLineMatchStrictness(sourcePkg, false)
            );
            if (!match.accepted || match.lineIndex < 0 || match.lineIndex >= lines.size()) {
                return;
            }
            EnhancedLRCParser.EnhancedLyricLine targetLine = lines.get(match.lineIndex);
            if (targetLine == null) {
                return;
            }
            long lineTime = targetLine.time > 0L ? targetLine.time : Math.max(0L, payload.lineStartMs);
            String lineText = targetLine.text != null ? targetLine.text : "";
            long nextLineTimeMs = 0L;
            if (match.lineIndex + 1 < lines.size()) {
                EnhancedLRCParser.EnhancedLyricLine nextLine = lines.get(match.lineIndex + 1);
                if (nextLine != null && nextLine.time > 0L) {
                    nextLineTimeMs = nextLine.time;
                }
            }
            ArrayList<EnhancedLRCParser.WordTimestamp> fusedWords = buildSuperLyricModuleLineWordTimestamps(
                lineTime, lineText, payload.wordTimestamps, nextLineTimeMs, payload.lineStartMs);
            if (fusedWords.isEmpty()) {
                return;
            }
            PendingWordFusion pending = new PendingWordFusion(
                requestSeq, trackKey, sourcePkg, payload, match.lineIndex, match, fusedWords
            );
            uiHandler.post(() -> commitSuperLyricWordFusion(pending, generation));
        });
    }

    private void commitSuperLyricWordFusion(PendingWordFusion pending, int generation) {
        if (pending == null || generation != wordFusionGeneration.get()) {
            return;
        }
        if (!isLyricsRequestCurrent(pending.requestSeq, pending.trackKey)) {
            return;
        }
        int safeIndex = clampLyricLineIndex(enhancedLyricLines, pending.matchedIndex);
        if (enhancedLyricLines == null || safeIndex < 0) {
            return;
        }
        EnhancedLRCParser.EnhancedLyricLine targetLine = enhancedLyricLines.get(safeIndex);
        if (targetLine == null || pending.fusedWords == null || pending.fusedWords.isEmpty()) {
            return;
        }
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "✅ SuperLyric 逐字融合命中: idx=" + safeIndex
                + ", tier=" + pending.tier
                + ", sim=" + String.format(Locale.US, "%.2f", pending.textSimilarity)
                + ", word=" + String.format(Locale.US, "%.2f", pending.wordCoverage));
        }
        if (targetLine.time <= 0L && pending.payload != null) {
            targetLine.time = Math.max(0L, pending.payload.lineStartMs);
        }
        boolean sameLineRefinement = safeIndex == lastWordFusionLineIndex
            && targetLine.wordTimestamps != null
            && !targetLine.wordTimestamps.isEmpty()
            && !SuperLyricWordTimestamps.hasMaterialWordTimingChange(
                targetLine.wordTimestamps, pending.fusedWords);
        if (sameLineRefinement) {
            if (lyricsView != null) {
                long playbackPosition = resolveLyricsAnchorPositionMs();
                lyricsView.updatePosition(playbackPosition);
            }
            return;
        }
        targetLine.wordTimestamps = pending.fusedWords;
        // V3.17+: 逐字轴为整曲绝对毫秒时走模块加权路径（computeFusedWordHighlightTarget），
        // 句内相对轴时走 legacy 等分路径（computeLegacyWordTimestampProgress），
        // 避免 absolute positionMs 比对 relative timestamps 导致进度瞬间跑完。
        boolean fusedTimestampsAbsolute = pending.fusedWords != null
            && !SuperLyricWordTimestamps.usesSentenceRelativeTimeline(
                pending.fusedWords, targetLine.time);
        targetLine.moduleWordTimeline = fusedTimestampsAbsolute && isMixedLyricsSourceMode();
        lastWordFusionLineIndex = safeIndex;
        cancelPendingNoLyrics();
        superLyricFallbackModeActive = false;
        applyFallbackRenderingModeIfNeeded();
        if (isSuperLyricOnlySourceMode()) {
            markStructuredLyricsFromSuperLyricModule(
                pending.sourcePkg != null ? pending.sourcePkg : currentLyricsSourcePkg
            );
            updateHookSourceStatusText(LyricsRuntimeSource.SUPER_LYRIC, "整曲·逐字");
        } else {
            markStructuredLyricsForWordFusion(
                pending.sourcePkg != null ? pending.sourcePkg : currentLyricsSourcePkg,
                lastNetworkLyricsRuntimeSource,
                lastNetworkLyricsProvider
            );
            updateHookSourceStatusWordFusion();
        }
        if (lyricsView != null) {
            lyricsView.setEnableWordByWord(effectiveWordByWordForSuperLyric(pending.payload));
            long playbackPosition = resolveLyricsAnchorPositionMs();
            lyricsView.updatePosition(playbackPosition);
            // 禁止 setLyrics / refreshCurrentLine：会 scrollY=0 再瞬间居中，且无上移动画。
            lyricsView.notifyWordTimestampsChanged(safeIndex);
        }
    }

    private boolean applySuperLyricFallbackPayload(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                   String sourcePkg,
                                                   int requestSeq,
                                                   String trackKey) {
        if (!shouldAllowSuperLyricSingleLineFallback(sourcePkg, requestSeq, trackKey) || payload == null) {
            return false;
        }
        String text = payload.text != null ? payload.text.trim() : "";
        if (text.isEmpty()) {
            scheduleNoLyricsIfStillEmpty(requestSeq, trackKey, null, null);
            return false;
        }
        String normalizedText = normalizeLyricToken(text);
        long payloadStart = Math.max(0L, payload.lineStartMs);
        long currentPlaybackPosition = resolveLyricsAnchorPositionMs();
        boolean textChangedFromLastApply = TextUtils.isEmpty(lastSuperLyricAppliedTextNormalized)
            || TextUtils.isEmpty(normalizedText)
            || !softLyricTokenEquals(normalizedText, lastSuperLyricAppliedTextNormalized);
        boolean moduleRelativeLineTime = payload.lineStartMs > 0L
            && payload.lineStartMs < 120_000L;
        boolean moduleRelativeWords = payload.hasValidWords()
            && SuperLyricWordTimestamps.usesSentenceRelativeTimeline(payload.wordTimestamps);
        if (payloadStart > 0L
            && currentPlaybackPosition > 0L
            && payloadStart + SUPER_LYRIC_STALE_BACKWARD_TOLERANCE_MS < currentPlaybackPosition
            && !superLyricFallbackModeActive
            && !textChangedFromLastApply
            && !isSuperLyricOnlySourceMode()
            && !moduleRelativeLineTime
            && !moduleRelativeWords) {
            if (BuildConfig.DEBUG) {
                LogHelper.w(TAG, "⏭ 忽略异常 SuperLyric 句子: pos=" + currentPlaybackPosition
                    + "ms, payloadStart=" + payloadStart
                    + "ms, tolerance=" + SUPER_LYRIC_STALE_BACKWARD_TOLERANCE_MS
                    + "ms, text=" + safeShort(text));
            }
            return false;
        }
        // 与 1.8.4 参考版一致：行结构锚到当前播放进度，逐字用 alignToLineTime 生成整曲绝对毫秒轴，
        // 避免句内 0 基轴 + 模块行起点锚定导致 (播放位置 - 锚点) 远超字时长而「一下跑完」。
        final long superLyricLeadCompensationMs = 40L;
        boolean sameLineAsLastApply = !TextUtils.isEmpty(normalizedText)
            && !TextUtils.isEmpty(lastSuperLyricAppliedTextNormalized)
            && softLyricTokenEquals(normalizedText, lastSuperLyricAppliedTextNormalized)
            && lastSuperLyricAppliedLineTimeMs > 0L;
        long anchoredCandidate = currentPlaybackPosition > 0L
            ? Math.max(currentPlaybackPosition + superLyricLeadCompensationMs, payloadStart)
            : payloadStart;
        long anchoredLineTime = sameLineAsLastApply
            ? Math.max(lastSuperLyricAppliedLineTimeMs, Math.max(payloadStart, 0L))
            : anchoredCandidate;
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "🎯 SuperLyric(单句) apply: trackKey=" + (trackKey != null ? trackKey : "")
                + ", seq=" + requestSeq
                + ", pos=" + currentPlaybackPosition + "ms"
                + ", payloadStart=" + payloadStart + "ms"
                + ", anchored=" + anchoredLineTime + "ms"
                + ", sameLine=" + sameLineAsLastApply
                + ", text=" + safeShort(text));
        }
        EnhancedLRCParser.EnhancedLyricLine line = reusableSuperLyricLine;
        line.time = Math.max(0L, anchoredLineTime);
        line.text = text;
        line.translation = null;
        superLyricActiveLineHasWords = payload.hasValidWords();
        if (superLyricActiveLineHasWords) {
            line.wordTimestamps = SuperLyricWordTimestamps.alignToLineTime(line.time, payload.wordTimestamps);
            // 单句兜底走绝对毫秒轴 + legacy 进度（与参考版 ModernLyricsView 一致），不用 moduleWordTimeline。
            line.moduleWordTimeline = false;
        } else {
            line.wordTimestamps = new ArrayList<>();
            line.moduleWordTimeline = false;
        }
        reusableSingleLineLyrics.clear();
        reusableSingleLineLyrics.add(line);
        enhancedLyricLines = reusableSingleLineLyrics;
        cancelPendingNoLyrics();
        boolean wasSuperLyricFallbackModeActive = superLyricFallbackModeActive;
        superLyricFallbackModeActive = true;
        applyFallbackRenderingModeIfNeeded();
        // 单行兜底切句时避免重复 setLyrics() 触发视图状态重置（会带来切句瞬间割裂感）。
        // 仅在首次进入兜底或非普通歌词视图路径时重绑数据。
        boolean shouldRebindSingleLineLyrics = !wasSuperLyricFallbackModeActive
            || abyssalMirrorEnabled
            || lyricsView == null;
        if (shouldRebindSingleLineLyrics) {
            setLyricsToView(reusableSingleLineLyrics);
        }
        lastSuperLyricAppliedTextNormalized = normalizedText;
        lastSuperLyricAppliedLineTimeMs = line.time;
        currentLyricsSource = LyricsSource.SUPER_LYRIC_FALLBACK;
        currentLyricsSourcePkg = sourcePkg != null ? sourcePkg : "";
        updateHookSourceStatusText(
            LyricsRuntimeSource.SUPER_LYRIC,
            payload.hasValidWords() ? "单句·逐字" : "单句"
        );
        if (lyricsView != null) {
            lyricsView.setEnableWordByWord(effectiveWordByWordForSuperLyric(payload));
            lyricsView.setEnableShuffleSplitEffect(shuffleSplitEffectEnabled);
            long latestPosition = resolveLyricsAnchorPositionMs();
            syncLyricsRenderPositionCache(latestPosition);
            lyricsView.updatePosition(latestPosition);
            if (sameLineAsLastApply && payload.hasValidWords()) {
                lyricsView.refreshCurrentLine();
            } else if (shouldRebindSingleLineLyrics) {
                lyricsView.refreshAllLines();
                lyricsView.refreshCurrentLine();
                lyricsView.centerCurrentLineImmediately();
            } else {
                lyricsView.refreshCurrentLine();
            }
            lyricsView.postInvalidateOnAnimation();
            lyricsView.invalidate();
        } else {
            forceRefreshLyricsViewForSuperLyric(payload.hasValidWords());
        }
        LogHelper.d(TAG, "✅ SuperLyricApi 兜底命中: words=" + payload.hasValidWords()
            + ", lines=1");
        return true;
    }

    /**
     * 当前曲目是否已有 SuperLyric 模块写入的逐字时间轴（非网络 LRC 行区间推算）。
     */
    private boolean structuredLyricsHaveWordTimestamps() {
        if (enhancedLyricLines == null || enhancedLyricLines.isEmpty()) {
            return false;
        }
        for (EnhancedLRCParser.EnhancedLyricLine line : enhancedLyricLines) {
            if (line == null) {
                continue;
            }
            if (line.wordTimestamps != null && !line.wordTimestamps.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean effectiveWordByWordForSuperLyric(SuperLyricApi.SuperLyricFallbackPayload payload) {
        if (!supportsWordByWordLyricsFeatures()) {
            return false;
        }
        boolean hasModuleWords = payload != null
            ? payload.hasValidWords()
            : (superLyricActiveLineHasWords || structuredLyricsHaveWordTimestamps());
        if (isSuperLyricOnlySourceMode()) {
            // 仅 SuperLyric：逐字高亮跟随用户「逐字显示」开关；开启且有模块逐字轴时用字时间，否则按行进度模拟。
            return wordByWordEnabled;
        }
        if (isMixedLyricsSourceMode()) {
            if (superLyricFallbackModeActive) {
                return hasModuleWords || wordByWordEnabled;
            }
            if (hasMixedModeNetworkLyricsStructure()) {
                return true;
            }
            return wordByWordEnabled || hasModuleWords;
        }
        if (wordByWordEnabled) {
            return true;
        }
        if (structuredLyricsHaveWordTimestamps()) {
            return true;
        }
        if (!superLyricFallbackModeActive) {
            return false;
        }
        return hasModuleWords;
    }

    private void applyFallbackRenderingModeIfNeeded() {
        if (lyricsView == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean effectiveWordByWord = effectiveWordByWordForSuperLyric(null);
        if (superLyricFallbackModeActive) {
            lyricsView.setShowTranslation(false);
            LyricsProjectionColorSync.bindLyricsView(lyricsView, randomColorSwitchEnabled, colorChangeIntervalMs);
            lyricsView.setEnableWordByWord(effectiveWordByWord);
            lyricsView.setCharJumpEnabled(effectiveWordByWord);
            lyricsView.setEnableShuffleSplitEffect(shuffleSplitEffectEnabled);
            lyricsView.setShuffleSplitMulticolorEnabled(shuffleSplitEffectEnabled && shuffleSplitMulticolorEnabled);
            lyricsView.setShuffleSplitMode(shuffleSplitEffectEnabled ? "WORD" : shuffleSplitMode);
            lyricsView.setShuffleOnlyCurrentLine(shuffleSplitEffectEnabled || shuffleSplitOnlyCurrentLine);
            lyricsView.setEnableGesture(gestureControlEnabled);
            lyricsView.setTimeAdjustOffset(0L);
            lyricsView.setShuffleSplitTiltRatio(prefs.getFloat("shuffleSplitTiltRatio", 5f));
            float scaleVariance = prefs.contains(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE)
                ? prefs.getFloat(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE, 0.22f)
                : adaptiveShuffleSplitScaleVariance(currentTextSize);
            lyricsView.setShuffleSplitScaleVariance(scaleVariance);
        } else {
            lyricsView.setShowTranslation(true);
            lyricsView.setEnableWordByWord(effectiveWordByWord);
            lyricsView.setCharJumpEnabled(effectiveWordByWord);
            lyricsView.setEnableShuffleSplitEffect(shuffleSplitEffectEnabled);
            lyricsView.setShuffleSplitMulticolorEnabled(shuffleSplitEffectEnabled && shuffleSplitMulticolorEnabled);
            lyricsView.setShuffleSplitMode(shuffleSplitEffectEnabled ? "WORD" : shuffleSplitMode);
            lyricsView.setShuffleOnlyCurrentLine(shuffleSplitEffectEnabled || shuffleSplitOnlyCurrentLine);
            lyricsView.setEnableGesture(gestureControlEnabled);
            lyricsView.setTimeAdjustOffset(projectionSyncOffsetMs);
            lyricsView.setShuffleSplitTiltRatio(prefs.getFloat("shuffleSplitTiltRatio", 5f));
            LyricsProjectionColorSync.bindLyricsView(lyricsView, randomColorSwitchEnabled, colorChangeIntervalMs);
            float scaleVariance = prefs.contains(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE)
                ? prefs.getFloat(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE, 0.22f)
                : adaptiveShuffleSplitScaleVariance(currentTextSize);
            lyricsView.setShuffleSplitScaleVariance(scaleVariance);
        }
    }

    private void cancelPendingNoLyrics() {
        try {
            if (pendingNoLyricsRunnable != null) {
                uiHandler.removeCallbacks(pendingNoLyricsRunnable);
                pendingNoLyricsRunnable = null;
            }
        } catch (Exception ignored) {}
    }

    /**
     * 仅当当前曲目仍无任何歌词行时调度「暂无歌词」；SuperLyric 实时回调可能晚于拉取失败。
     */
    private void scheduleNoLyricsIfStillEmpty(int requestSeq,
                                              String trackKey,
                                              LyricsRuntimeSource statusSource,
                                              String statusDetail) {
        if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
            return;
        }
        if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
            return;
        }
        if (statusSource != null) {
            updateHookSourceStatusText(statusSource, statusDetail);
        }
        long delayMs = NO_LYRICS_UI_DELAY_MS;
        if (superLyricRealtimeListener != null && shouldUseSuperLyricFallback()) {
            delayMs = Math.max(delayMs, isSuperLyricOnlySourceMode() ? 8_000L : 5_000L);
        }
        scheduleNoLyrics(requestSeq, trackKey, delayMs);
    }

    private void scheduleNoLyrics(int requestSeq, String trackKey) {
        scheduleNoLyrics(requestSeq, trackKey, NO_LYRICS_UI_DELAY_MS);
    }

    private void scheduleNoLyrics(int requestSeq, String trackKey, long delayMs) {
        cancelPendingNoLyrics();
        pendingNoLyricsRunnable = () -> {
            if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                return;
            }
            // V3.17+: 确认无歌词时清除 trackLoading，让视图进入”暂无歌词”稳态
            if (lyricsView != null && lyricsView.isTrackLoading()) {
                lyricsView.setTrackLoading(false);
            }
            // 只有在当前仍然没有歌词时才显示”未获取到歌词”
            if (enhancedLyricLines == null || enhancedLyricLines.isEmpty()) {
                setLyricsToView(null);
            }
        };
        // 延迟一点，避免短时间内”通知无歌词/随后API拿到歌词”导致闪烁；等 extras 时用更长 delay
        long d = Math.max(0L, delayMs);
        uiHandler.postDelayed(pendingNoLyricsRunnable, d);
    }
    
    /**
     * 更新歌词位置（同步到当前显示的视图）
     */
    private void updatePositionToView(long position) {
        try {
            latestLyricsRenderPositionMs = Math.max(0L, position);
            long adjustedPosition = position + (long) projectionSyncOffsetMs;
            if (adjustedPosition < 0) {
                adjustedPosition = 0;
            }
            if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
                // 新的ViewGroup版本：根据播放位置更新当前显示的歌词文本
                // 加入预提前量补偿 MediaController 位置读取与音频输出的管道延迟，使换行与听感同步
                if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
                    int currentIndex = findCurrentLineIndexForAbyssal(
                        enhancedLyricLines, adjustedPosition + ABYSSAL_LINE_PRE_ADVANCE_MS);
                    if (currentIndex >= 0
                        && currentIndex < enhancedLyricLines.size()
                        && currentIndex != lastAbyssalRenderedLineIndex) {
                        lastAbyssalRenderedLineIndex = currentIndex;
                        String currentLyric = enhancedLyricLines.get(currentIndex).text;
                        abyssalMirrorLyricsViewGroup.setLyric(currentLyric);
                    }
                }
            } else if (abyssalMirrorEnabled && abyssalMirrorLyricsView != null) {
                // 旧版3D旋转版本（兼容）
                if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
                    int currentIndex = findCurrentLineIndexForAbyssal(
                        enhancedLyricLines, adjustedPosition + ABYSSAL_LINE_PRE_ADVANCE_MS);
                    if (currentIndex != lastAbyssalRenderedLineIndex) {
                        lastAbyssalRenderedLineIndex = currentIndex;
                        abyssalMirrorLyricsView.setCurrentLineIndex(currentIndex);
                    }
                }
            } else if (lyricsView != null) {
                // ModernLyricsView 内再叠加 projectionSyncOffsetMs；此处传原始进度
                lyricsView.updatePosition(position);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 更新歌词位置失败", e);
        }
    }
    
    /**
     * 查找当前行索引（用于深渊镜视图，与ModernLyricsView逻辑一致）
     */
    private int findCurrentLineIndexForAbyssal(List<EnhancedLRCParser.EnhancedLyricLine> lyricLines, long position) {
        if (lyricLines == null || lyricLines.isEmpty()) {
            return -1;
        }
        
        // 检查位置值是否合理（不超过1小时，即3600000ms）
        if (position > 3600000) {
            LogHelper.w(TAG, "⚠️ 位置值异常: " + position + "ms（超过1小时），重置为0");
            position = 0;
        }
        
        // 如果位置为0或负数，返回第一行（索引0）
        if (position <= 0) {
            return 0;
        }
        
        // 进度超过末行：正常尾奏锁末行；远超末行时间轴则视为上首残留进度，回到首行。
        int lastIndex = lyricLines.size() - 1;
        EnhancedLRCParser.EnhancedLyricLine lastLine = lyricLines.get(lastIndex);
        if (lastLine != null && position >= lastLine.time) {
            if (position <= lastLine.time + 90_000L) {
                return lastIndex;
            }
            return 0;
        }
        
        // 从后往前查找，找到第一个时间小于等于位置且位置小于下一行时间的行
        for (int i = lyricLines.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(i);
            if (line == null) {
                continue;
            }
            
            // 计算这一行的有效结束时间
            long lineEndTime;
            if (i + 1 < lyricLines.size()) {
                EnhancedLRCParser.EnhancedLyricLine next = lyricLines.get(i + 1);
                lineEndTime = next != null ? next.time : line.time + 3000;
            } else {
                lineEndTime = line.time + 3000; // 默认3秒
            }
            if (lineEndTime <= line.time) {
                lineEndTime = line.time + 3000;
            }
            
            // 只有当position >= line.time才开始显示，且position < lineEndTime才保持显示
            if (position >= line.time && position < lineEndTime) {
                return i;
            }
        }
        
        // 如果所有行的时间都大于位置，返回第一行
        return 0;
    }
    
    /**
     * 检查是否有歌词视图（用于UI检查）
     */
    private boolean hasLyricsView() {
        return (abyssalMirrorEnabled && (abyssalMirrorLyricsViewGroup != null || abyssalMirrorLyricsView != null)) || 
               (!abyssalMirrorEnabled && lyricsView != null);
    }

    /**
     * 供 MainActivity 检查音乐投屏是否在运行（含深渊镜模式：有任一歌词视图即视为有 UI）
     */
    public boolean hasActiveLyricsUI() {
        return hasLyricsView();
    }

    /**
     * 主屏音乐快捷磁贴：是否为主屏横屏歌词界面且歌词 UI 已创建。
     */
    public boolean isMainScreenLandscapeLyricsActive() {
        return isMainScreenLandscapeMode && hasActiveLyricsUI();
    }

    /**
     * 主屏音乐磁贴启动前：若已有歌词 Activity（背屏、主屏占位等），应先停止再 {@code am start} 主屏横屏，
     * 避免与 singleInstance 任务冲突。
     */
    public static boolean hasConflictingLyricsActivityForMainScreenTile() {
        RearScreenLyricsActivity a = currentInstance;
        if (a == null) {
            return false;
        }
        return !a.isFinishing();
    }
    
    private void loadLyricsFontFieldsFromPrefs(SharedPreferences prefs) {
        projectionLyricsFontId = LyricsFontHelper.normalizeFontId(prefs.getString(KEY_PROJECTION_LYRICS_FONT, null));
        String pPath = prefs.getString(KEY_PROJECTION_LYRICS_CUSTOM_PATH, null);
        if (pPath != null && pPath.isEmpty()) {
            pPath = null;
        }
        projectionLyricsCustomPath = pPath;
        if (!LyricsFontHelper.ID_CUSTOM.equals(projectionLyricsFontId)) {
            projectionLyricsCustomPath = null;
        } else if (projectionLyricsCustomPath == null || !(new java.io.File(projectionLyricsCustomPath).isFile())) {
            projectionLyricsFontId = LyricsFontHelper.DEFAULT_ID;
            projectionLyricsCustomPath = null;
        }
    }

    /**
     * 加载设置
     */
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentTextSize = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
        backgroundTextureSize = prefs.getFloat(KEY_BACKGROUND_TEXTURE_SIZE, DEFAULT_BACKGROUND_TEXTURE_SIZE);
        normalLyricsAlpha = prefs.getInt(KEY_NORMAL_LYRICS_ALPHA, DEFAULT_NORMAL_LYRICS_ALPHA);
        backgroundTextureAlpha = prefs.getInt(KEY_BACKGROUND_TEXTURE_ALPHA, DEFAULT_BACKGROUND_TEXTURE_ALPHA);
        wordByWordEnabled = prefs.getBoolean(KEY_WORD_BY_WORD, false);  // 默认关闭逐字显示
        shuffleSplitEffectEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_EFFECT, false);
        shuffleSplitMulticolorEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_MULTICOLOR, false);
        shuffleSplitMode = prefs.getString(KEY_SHUFFLE_SPLIT_MODE, "WORD");
        if (shuffleSplitMode == null) shuffleSplitMode = "WORD";
        shuffleSplitOnlyCurrentLine = prefs.getBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, true);
        marqueeLightEnabled = prefs.getBoolean(KEY_MARQUEE_LIGHT, true);  // 默认开启跑马灯
        neonDisplayEnabled = prefs.getBoolean(KEY_NEON_DISPLAY, prefs.getBoolean(KEY_LYRICS_NEON_GLOW_LEGACY, true));
        neonBorderEnabled = prefs.getBoolean(KEY_NEON_BORDER, true);  // 默认开启霓虹灯边框
        marqueeLightSize = prefs.getFloat(KEY_MARQUEE_LIGHT_SIZE, 18f);  // 默认跑马灯线条宽度18px
        marqueeLightDurationMs = prefs.getInt(KEY_MARQUEE_LIGHT_DURATION_MS, DEFAULT_MARQUEE_LIGHT_DURATION_MS);
        gestureControlEnabled = prefs.getBoolean(KEY_GESTURE_CONTROL, false);  // 默认关闭手势控制
        if (shuffleSplitEffectEnabled) {
            // 分词模式与逐字/滑动互斥，进入页面时直接纠偏，避免旧偏好导致冲突
            wordByWordEnabled = false;
            gestureControlEnabled = false;
            shuffleSplitMode = "WORD";
            shuffleSplitOnlyCurrentLine = true;
        }
        abyssalMirrorEnabled = prefs.getBoolean(KEY_ABYSSAL_MIRROR, false);  // 默认关闭深渊镜效果
        if (abyssalMirrorEnabled) {
            // 深渊镜与逐字/分词互斥，进入页面时纠偏（手势切歌不互斥）
            wordByWordEnabled = false;
            shuffleSplitEffectEnabled = false;
        }
        backgroundTextureEnabled = prefs.getBoolean(KEY_BACKGROUND_TEXTURE, false);  // 默认关闭歌词底图显示
        albumArtBackgroundEnabled = prefs.getBoolean(KEY_ALBUM_ART_BACKGROUND, false);
        albumArtAlphaPercent = Math.max(0, Math.min(100, prefs.getInt(KEY_ALBUM_ART_ALPHA_PERCENT, DEFAULT_ALBUM_ART_ALPHA_PERCENT)));
        albumArtBlurRadiusPx = Math.max(0f, prefs.getFloat(KEY_ALBUM_ART_BLUR_RADIUS, DEFAULT_ALBUM_ART_BLUR_RADIUS_PX));
        powerSavingModeEnabled = prefs.getBoolean(KEY_POWER_SAVING_MODE, false);
        borderPerformanceGuardEnabled = prefs.getBoolean(KEY_BORDER_PERFORMANCE_GUARD, false);
        borderLightweightModeEnabled = prefs.getBoolean(KEY_BORDER_LIGHTWEIGHT_MODE, false);
        projectionSyncOffsetMs = prefs.getInt(KEY_PROJECTION_SYNC_OFFSET_MS, DEFAULT_PROJECTION_SYNC_OFFSET_MS);
        lyricsSourceMode = normalizeLyricsSourceMode(
            prefs.getString(KEY_LYRICS_SOURCE_MODE, VALUE_LYRICS_SOURCE_MIXED)
        );
        breathingEnabled = prefs.getBoolean(KEY_BREATHING_ENABLED, false);
        breathingBpm = prefs.getInt(KEY_BREATHING_BPM, DEFAULT_BREATHING_BPM);
        breathingScaleVariance = prefs.getFloat(KEY_BREATHING_SCALE_VARIANCE, DEFAULT_BREATHING_SCALE_VARIANCE);
        breathingDisplacementStrength = prefs.getFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, DEFAULT_BREATHING_DISPLACEMENT_STRENGTH);
        colorChangeIntervalMs = Math.max(
                MIN_COLOR_CHANGE_INTERVAL_MS,
                Math.min(
                        MAX_COLOR_CHANGE_INTERVAL_MS,
                        prefs.getInt(KEY_COLOR_CHANGE_INTERVAL_MS, DEFAULT_COLOR_CHANGE_INTERVAL_MS)
                )
        );
        randomColorSwitchEnabled = prefs.getBoolean(KEY_RANDOM_COLOR_SWITCH_ENABLED, true);
        fixedColor = prefs.getInt(KEY_FIXED_COLOR, 0xFFFFFFFF);
        // 同步到全局颜色管理器
        LyricsColorManager.INSTANCE.setRandomMode(randomColorSwitchEnabled);
        LyricsColorManager.INSTANCE.setColorChangeIntervalMs(colorChangeIntervalMs);
        LyricsColorManager.INSTANCE.setFixedColor(fixedColor);
        abyssalGyroSensitivity = prefs.getFloat(KEY_ABYSSAL_GYRO_SENSITIVITY, DEFAULT_ABYSSAL_GYRO_SENSITIVITY);
        abyssalMovableRange = prefs.getFloat(KEY_ABYSSAL_MOVABLE_RANGE, DEFAULT_ABYSSAL_MOVABLE_RANGE);
        loadLyricsFontFieldsFromPrefs(prefs);
        LogHelper.d(TAG, "📋 加载设置: 字体大小=" + currentTextSize + ", 底图大小=" + backgroundTextureSize + 
                  ", 未唱歌词透明度=" + normalLyricsAlpha + "%, 底图透明度=" + backgroundTextureAlpha + "%" +
                  ", 逐字显示=" + wordByWordEnabled + ", 跑马灯=" + marqueeLightEnabled + 
                  ", 跑马灯线条宽度=" + marqueeLightSize + "px, 霓虹效果=" + neonDisplayEnabled + ", 边框显示=" + neonBorderEnabled + 
                  ", 手势控制=" + gestureControlEnabled +
                  ", 底图显示=" + backgroundTextureEnabled +
                  ", 专辑图背景=" + albumArtBackgroundEnabled +
                  ", 深渊镜效果=" + abyssalMirrorEnabled +
                  ", 边框护栏=" + borderPerformanceGuardEnabled +
                  ", 边框轻量模式=" + borderLightweightModeEnabled);
    }

    private long breathingBpmToRhythmMs(int bpm) {
        if (bpm <= 0) {
            // 兜底为最慢节奏，避免异常值导致动画时长无效。
            return 5000L;
        }
        int safeBpm = Math.max(1, bpm);
        return Math.round(60000f / safeBpm);
    }
    
    /**
     * 保存设置
     */
    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat(KEY_TEXT_SIZE, currentTextSize);
        editor.putFloat(KEY_BACKGROUND_TEXTURE_SIZE, backgroundTextureSize);
        editor.putInt(KEY_NORMAL_LYRICS_ALPHA, normalLyricsAlpha);
        editor.putInt(KEY_BACKGROUND_TEXTURE_ALPHA, backgroundTextureAlpha);
        editor.putBoolean(KEY_WORD_BY_WORD, wordByWordEnabled);
        editor.putBoolean(KEY_SHUFFLE_SPLIT_EFFECT, shuffleSplitEffectEnabled);
        editor.putBoolean(KEY_SHUFFLE_SPLIT_MULTICOLOR, shuffleSplitMulticolorEnabled);
        editor.putString(KEY_SHUFFLE_SPLIT_MODE, shuffleSplitMode);
        editor.putBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, shuffleSplitOnlyCurrentLine);
        editor.putBoolean(KEY_SHUFFLE_SPLIT_PERFORMANCE_GUARD,
                prefs.getBoolean(KEY_SHUFFLE_SPLIT_PERFORMANCE_GUARD, false));
        editor.putBoolean(KEY_MARQUEE_LIGHT, marqueeLightEnabled);
        editor.putBoolean(KEY_NEON_DISPLAY, neonDisplayEnabled);
        editor.putBoolean(KEY_NEON_BORDER, neonBorderEnabled);
        editor.putFloat(KEY_MARQUEE_LIGHT_SIZE, marqueeLightSize);
        editor.putInt(KEY_MARQUEE_LIGHT_DURATION_MS, marqueeLightDurationMs);
        editor.putBoolean(KEY_GESTURE_CONTROL, gestureControlEnabled);
        editor.putBoolean(KEY_BACKGROUND_TEXTURE, backgroundTextureEnabled);
        editor.putBoolean(KEY_ALBUM_ART_BACKGROUND, albumArtBackgroundEnabled);
        editor.putInt(KEY_ALBUM_ART_ALPHA_PERCENT, Math.max(0, Math.min(100, albumArtAlphaPercent)));
        editor.putFloat(KEY_ALBUM_ART_BLUR_RADIUS, Math.max(0f, albumArtBlurRadiusPx));
        editor.putString(KEY_LYRICS_SOURCE_MODE, normalizeLyricsSourceMode(lyricsSourceMode));
        editor.putBoolean(KEY_BORDER_PERFORMANCE_GUARD, borderPerformanceGuardEnabled);
        editor.putBoolean(KEY_BORDER_LIGHTWEIGHT_MODE, borderLightweightModeEnabled);
        editor.putBoolean(KEY_BREATHING_ENABLED, breathingEnabled);
        editor.putInt(KEY_BREATHING_BPM, breathingBpm);
        editor.putFloat(KEY_BREATHING_SCALE_VARIANCE, breathingScaleVariance);
        editor.putFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, breathingDisplacementStrength);
        editor.putInt(
                KEY_COLOR_CHANGE_INTERVAL_MS,
                Math.max(MIN_COLOR_CHANGE_INTERVAL_MS, Math.min(MAX_COLOR_CHANGE_INTERVAL_MS, colorChangeIntervalMs))
        );
        editor.putBoolean(KEY_RANDOM_COLOR_SWITCH_ENABLED, randomColorSwitchEnabled);
        editor.putInt(KEY_FIXED_COLOR, fixedColor);
        editor.apply();
        LogHelper.d(TAG, "💾 保存设置: 字体大小=" + currentTextSize + ", 底图大小=" + backgroundTextureSize + 
                  ", 未唱歌词透明度=" + normalLyricsAlpha + "%, 底图透明度=" + backgroundTextureAlpha + "%" +
                  ", 逐字显示=" + wordByWordEnabled + ", 跑马灯=" + marqueeLightEnabled + 
                  ", 跑马灯线条宽度=" + marqueeLightSize + "px, 霓虹灯边框=" + neonBorderEnabled + 
                  ", 手势控制=" + gestureControlEnabled +
                  ", 底图显示=" + backgroundTextureEnabled +
                  ", 专辑图背景=" + albumArtBackgroundEnabled);
    }
    
    /**
     * 在 [applySettings] 指纹短路之前同步「颜色变化节奏」到视图，避免 getAll().hashCode() 碰撞或指纹未变时背屏不跟随调试滑块。
     */
    private void applyColorChangeIntervalFromPrefsIfChanged() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int fromPrefs = Math.max(
                MIN_COLOR_CHANGE_INTERVAL_MS,
                Math.min(
                        MAX_COLOR_CHANGE_INTERVAL_MS,
                        prefs.getInt(KEY_COLOR_CHANGE_INTERVAL_MS, DEFAULT_COLOR_CHANGE_INTERVAL_MS)
                )
        );
        if (fromPrefs == colorChangeIntervalMs) {
            // 即使 interval 未变，也要同步 randomMode / fixedColor（设置页可能仅改了颜色开关）
            syncRandomModeAndFixedColorFromPrefs(prefs);
            return;
        }
        colorChangeIntervalMs = fromPrefs;
        syncRandomModeAndFixedColorFromPrefs(prefs);
        if (abyssalMirrorLyricsViewGroup != null) {
            abyssalMirrorLyricsViewGroup.setColorChangeIntervalMs(colorChangeIntervalMs);
        }
        if (lyricsView != null) {
            lyricsView.setColorChangeIntervalMs(colorChangeIntervalMs);
        }
        if (marqueeLightView != null) {
            marqueeLightView.setColorChangeIntervalMs(colorChangeIntervalMs);
        }
        LogHelper.d(TAG, "🎨 颜色变化节奏已从偏好同步: " + colorChangeIntervalMs + "ms");
    }

    private void syncRandomModeAndFixedColorFromPrefs(SharedPreferences prefs) {
        boolean newRandom = prefs.getBoolean(KEY_RANDOM_COLOR_SWITCH_ENABLED, true);
        int newFixed = prefs.getInt(KEY_FIXED_COLOR, 0xFFFFFFFF);
        boolean changed = (newRandom != randomColorSwitchEnabled) || (newFixed != fixedColor);
        randomColorSwitchEnabled = newRandom;
        fixedColor = newFixed;
        LyricsColorManager.INSTANCE.setRandomMode(randomColorSwitchEnabled);
        LyricsColorManager.INSTANCE.setFixedColor(fixedColor);
        if (changed) {
            if (abyssalMirrorLyricsViewGroup != null) {
                abyssalMirrorLyricsViewGroup.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
                if (!randomColorSwitchEnabled) {
                    abyssalMirrorLyricsViewGroup.setTextColor(fixedColor);
                }
            }
            if (lyricsView != null) {
                lyricsView.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
            }
        }
    }

    /**
     * 应用设置（从SharedPreferences读取并应用）
     */
    public void applySettings() {
        // 重新加载设置（确保获取最新值）
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applyColorChangeIntervalFromPrefsIfChanged();
        final String previousLyricsSourceMode = lyricsSourceMode;
        String newSettingsFingerprint = String.valueOf(
                prefs.getAll().hashCode()
        ) + "|" + isMainScreenLandscapeMode;
        if (newSettingsFingerprint.equals(lastAppliedSettingsFingerprint)) {
            return;
        }
        lastAppliedSettingsFingerprint = newSettingsFingerprint;
        float originalTextSize = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
        float originalBackgroundTextureSize = prefs.getFloat(KEY_BACKGROUND_TEXTURE_SIZE, DEFAULT_BACKGROUND_TEXTURE_SIZE);
        float originalMarqueeLightSize = prefs.getFloat(KEY_MARQUEE_LIGHT_SIZE, 18f);  // 默认跑马灯线条宽度18px
        marqueeLightDurationMs = prefs.getInt(KEY_MARQUEE_LIGHT_DURATION_MS, DEFAULT_MARQUEE_LIGHT_DURATION_MS);
        normalLyricsAlpha = prefs.getInt(KEY_NORMAL_LYRICS_ALPHA, DEFAULT_NORMAL_LYRICS_ALPHA);
        backgroundTextureAlpha = prefs.getInt(KEY_BACKGROUND_TEXTURE_ALPHA, DEFAULT_BACKGROUND_TEXTURE_ALPHA);
        wordByWordEnabled = prefs.getBoolean(KEY_WORD_BY_WORD, false);  // 默认关闭逐字显示
        shuffleSplitEffectEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_EFFECT, false);
        shuffleSplitMulticolorEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_MULTICOLOR, false);
        shuffleSplitMode = prefs.getString(KEY_SHUFFLE_SPLIT_MODE, "WORD");
        shuffleSplitOnlyCurrentLine = prefs.getBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, true);
        if (shuffleSplitEffectEnabled) {
            // 分词显示开启时固定为：仅当前一行 + 词库分词（WORD）
            shuffleSplitMode = "WORD";
            shuffleSplitOnlyCurrentLine = true;
            // 分词显示与逐字高亮互斥，强制关闭逐字
            wordByWordEnabled = false;
        }
        boolean newAbyssalMirrorEnabled = prefs.getBoolean(KEY_ABYSSAL_MIRROR, false);  // 默认关闭深渊镜效果
        marqueeLightEnabled = prefs.getBoolean(KEY_MARQUEE_LIGHT, true);
        neonDisplayEnabled = prefs.getBoolean(KEY_NEON_DISPLAY, prefs.getBoolean(KEY_LYRICS_NEON_GLOW_LEGACY, true));
        neonBorderEnabled = prefs.getBoolean(KEY_NEON_BORDER, true);  // 默认开启霓虹灯边框
        gestureControlEnabled = prefs.getBoolean(KEY_GESTURE_CONTROL, true);
        if (shuffleSplitEffectEnabled) {
            // 分词显示模式与可滑动歌词互斥；偏好里可能残留旧组合，运行时强制关闭手势
            gestureControlEnabled = false;
        }
        if (newAbyssalMirrorEnabled) {
            wordByWordEnabled = false;
            shuffleSplitEffectEnabled = false;
        }
        backgroundTextureEnabled = prefs.getBoolean(KEY_BACKGROUND_TEXTURE, false);  // 默认关闭歌词底图显示
        albumArtBackgroundEnabled = prefs.getBoolean(KEY_ALBUM_ART_BACKGROUND, false);
        albumArtAlphaPercent = Math.max(0, Math.min(100, prefs.getInt(KEY_ALBUM_ART_ALPHA_PERCENT, DEFAULT_ALBUM_ART_ALPHA_PERCENT)));
        albumArtBlurRadiusPx = Math.max(0f, prefs.getFloat(KEY_ALBUM_ART_BLUR_RADIUS, DEFAULT_ALBUM_ART_BLUR_RADIUS_PX));
        projectionSyncOffsetMs = prefs.getInt(KEY_PROJECTION_SYNC_OFFSET_MS, DEFAULT_PROJECTION_SYNC_OFFSET_MS);
        lyricsSourceMode = normalizeLyricsSourceMode(
            prefs.getString(KEY_LYRICS_SOURCE_MODE, VALUE_LYRICS_SOURCE_MIXED)
        );
        final boolean lyricsSourceModeChanged = !TextUtils.equals(previousLyricsSourceMode, lyricsSourceMode);
        breathingEnabled = prefs.getBoolean(KEY_BREATHING_ENABLED, false);
        breathingBpm = prefs.getInt(KEY_BREATHING_BPM, DEFAULT_BREATHING_BPM);
        breathingScaleVariance = prefs.getFloat(KEY_BREATHING_SCALE_VARIANCE, DEFAULT_BREATHING_SCALE_VARIANCE);
        breathingDisplacementStrength = prefs.getFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, DEFAULT_BREATHING_DISPLACEMENT_STRENGTH);
        colorChangeIntervalMs = Math.max(
                MIN_COLOR_CHANGE_INTERVAL_MS,
                Math.min(
                        MAX_COLOR_CHANGE_INTERVAL_MS,
                        prefs.getInt(KEY_COLOR_CHANGE_INTERVAL_MS, DEFAULT_COLOR_CHANGE_INTERVAL_MS)
                )
        );
        randomColorSwitchEnabled = prefs.getBoolean(KEY_RANDOM_COLOR_SWITCH_ENABLED, true);
        fixedColor = prefs.getInt(KEY_FIXED_COLOR, 0xFFFFFFFF);
        // 同步到全局颜色管理器
        LyricsColorManager.INSTANCE.setRandomMode(randomColorSwitchEnabled);
        LyricsColorManager.INSTANCE.setColorChangeIntervalMs(colorChangeIntervalMs);
        LyricsColorManager.INSTANCE.setFixedColor(fixedColor);
        powerSavingModeEnabled = prefs.getBoolean(KEY_POWER_SAVING_MODE, false);
        borderPerformanceGuardEnabled = prefs.getBoolean(KEY_BORDER_PERFORMANCE_GUARD, false);
        borderLightweightModeEnabled = prefs.getBoolean(KEY_BORDER_LIGHTWEIGHT_MODE, false);
        // 省电模式：运行时强制“普通整行歌词”。
        // 注意：不要在这里改写各开关变量（它们应继续代表用户偏好），
        // 具体启用/禁用由 applySettings 时根据 powerSavingModeEnabled 计算 effective flags。
        loadLyricsFontFieldsFromPrefs(prefs);
        
        // 主屏横屏模式：字体大小、跑马灯大小调整
        // 注意：底图字体大小倍数保持设置值不变，会自动跟随字体大小（字体x2，底图也会x2）
        if (isMainScreenLandscapeMode) {
            currentTextSize = originalTextSize * 2f;
            backgroundTextureSize = originalBackgroundTextureSize;  // 保持设置值不变，跟随字体大小
            marqueeLightSize = originalMarqueeLightSize;  // 背屏跟随设置，无倍率
            LogHelper.d(TAG, "🖥️ 主屏横屏模式：字体大小调整为 " + currentTextSize + "px (原值x2), " +
                      "底图字体大小倍数保持设置值 " + backgroundTextureSize + " (跟随字体大小), " +
                      "跑马灯线条宽度保持设置值 " + marqueeLightSize + "px");
        } else {
            currentTextSize = originalTextSize;
            backgroundTextureSize = originalBackgroundTextureSize;
            marqueeLightSize = originalMarqueeLightSize;
        }
        
        LogHelper.d(TAG, "📋 重新加载设置: 字体大小=" + currentTextSize + " (原始值=" + originalTextSize + "), 底图大小=" + backgroundTextureSize + 
                  ", 未唱歌词透明度=" + normalLyricsAlpha + "%, 底图透明度=" + backgroundTextureAlpha + "%" +
                  ", 逐字显示=" + wordByWordEnabled + ", 跑马灯=" + marqueeLightEnabled + 
                  ", 跑马灯线条宽度=" + marqueeLightSize + "px, 霓虹效果=" + neonDisplayEnabled + ", 边框显示=" + neonBorderEnabled + 
                  ", 省电模式=" + powerSavingModeEnabled + ", 手势控制=" + gestureControlEnabled +
                  ", 底图显示=" + backgroundTextureEnabled +
                  ", 专辑图背景=" + albumArtBackgroundEnabled +
                  ", 深渊镜效果=" + newAbyssalMirrorEnabled +
                  ", 边框护栏=" + borderPerformanceGuardEnabled +
                  ", 边框轻量模式=" + borderLightweightModeEnabled);
        
        // 如果深渊镜开关状态发生变化，必须重启 Activity 才能切换视图层次
        //（深渊镜使用 AbyssalMirrorLyricsViewGroup，其他模式使用 ModernLyricsView，
        //  二者在 createUI() 中二选一创建，applySettings() 无法原地切换视图类型。）
        if (newAbyssalMirrorEnabled != abyssalMirrorEnabled) {
            abyssalMirrorEnabled = newAbyssalMirrorEnabled;
            LogHelper.d(TAG, "🔄 深渊镜模式切换(" + (abyssalMirrorEnabled ? "ON" : "OFF") + ")，重启Activity重建视图");
            recreate();
            return;
        }
        abyssalMirrorEnabled = newAbyssalMirrorEnabled;
        if (!supportsWordByWordLyricsFeatures()) {
            wordFusionGeneration.incrementAndGet();
        }

        // 更新歌词视图
        applyProjectionBackgroundMode();
        if (albumArtBackgroundView != null) {
            albumArtBackgroundView.setAlpha(albumArtAlphaPercent / 100f);
        }
        updateAlbumArtBackground(mediaController != null ? mediaController.getMetadata() : null);
        if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
            // 新的ViewGroup版本：更新字体大小、颜色、手势开关、陀螺仪倍数与可移动范围
            float gyroSensitivity = prefs.getFloat(KEY_ABYSSAL_GYRO_SENSITIVITY, DEFAULT_ABYSSAL_GYRO_SENSITIVITY);
            float movableRange = prefs.getFloat(KEY_ABYSSAL_MOVABLE_RANGE, DEFAULT_ABYSSAL_MOVABLE_RANGE);
            abyssalMirrorLyricsViewGroup.setTextSize(currentTextSize);
            abyssalMirrorLyricsViewGroup.setTextColor(randomColorSwitchEnabled ? randomHighSaturationColor() : fixedColor);
            abyssalMirrorLyricsViewGroup.setEnableGesture(gestureControlEnabled);
            abyssalMirrorLyricsViewGroup.setGyroSensitivityMultiplier(gyroSensitivity);
            abyssalMirrorLyricsViewGroup.setMovableRangeMultiplier(movableRange);
            abyssalMirrorLyricsViewGroup.setColorChangeIntervalMs(colorChangeIntervalMs);
            abyssalMirrorLyricsViewGroup.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
            abyssalMirrorLyricsViewGroup.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
            abyssalMirrorLyricsViewGroup.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
            abyssalMirrorLyricsViewGroup.setLyricsTypeface(LyricsFontHelper.resolveTypeface(this, projectionLyricsFontId, projectionLyricsCustomPath));
            LogHelper.d(TAG, "✅ 深渊镜歌词视图设置已更新（ViewGroup版本）");
        } else if (abyssalMirrorEnabled && abyssalMirrorLyricsView != null) {
            // 旧版3D旋转版本（兼容）
            abyssalMirrorLyricsView.setTextSize(currentTextSize);
            abyssalMirrorLyricsView.postInvalidateOnAnimation();
            LogHelper.d(TAG, "✅ 深渊镜歌词视图设置已更新（旧版）");
        } else if (lyricsView != null) {
            final boolean effectiveShuffleSplitEnabled = !powerSavingModeEnabled && shuffleSplitEffectEnabled;
            lyricsView.setEnableWordByWord(effectiveWordByWordForSuperLyric(null));
            lyricsView.setEnableShuffleSplitEffect(effectiveShuffleSplitEnabled);
            lyricsView.setShuffleSplitMode(effectiveShuffleSplitEnabled ? "WORD" : shuffleSplitMode);
            lyricsView.setShuffleOnlyCurrentLine(effectiveShuffleSplitEnabled || shuffleSplitOnlyCurrentLine);
            lyricsView.setShuffleSplitTiltRatio(prefs.getFloat("shuffleSplitTiltRatio", 5f));
            LyricsProjectionColorSync.bindLyricsView(lyricsView, randomColorSwitchEnabled, colorChangeIntervalMs);
            float scaleVariance = prefs.contains(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE)
                    ? prefs.getFloat(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE, 0.22f)
                    : adaptiveShuffleSplitScaleVariance(currentTextSize);
            lyricsView.setShuffleSplitScaleVariance(scaleVariance);
            lyricsView.setShufflePerformanceGuardEnabled(
                    prefs.getBoolean(KEY_SHUFFLE_SPLIT_PERFORMANCE_GUARD, false)
            );
            if (!isMainScreenLandscapeMode) {
                lyricsView.clearFixedLineSpacing();
            }
            lyricsView.setTextSize(currentTextSize);
            // 主屏横屏模式：固定行间距（1.5×默认）；背屏已在 setTextSize 内按字高自动算行距
            if (isMainScreenLandscapeMode) {
                float defaultLineSpacing = 160f;
                float mainScreenLineSpacing = defaultLineSpacing * 1.5f;
                lyricsView.setLineSpacing(mainScreenLineSpacing);
            }
            
            lyricsView.setBackgroundTextureSize(backgroundTextureSize);
            lyricsView.setNormalLyricsAlpha(normalLyricsAlpha);
            lyricsView.setBackgroundTextureAlpha(backgroundTextureAlpha);
            lyricsView.setEnableGesture(gestureControlEnabled);
            lyricsView.setShowBackgroundTexture(backgroundTextureEnabled);
            lyricsView.setPowerSavingModeEnabled(powerSavingModeEnabled);
            lyricsView.setBreathingRhythmMs(powerSavingModeEnabled ? 5000 : breathingBpmToRhythmMs(breathingBpm));
            lyricsView.setBreathingScaleVariance(
                (powerSavingModeEnabled || !breathingEnabled) ? 0f : breathingScaleVariance
            );
            lyricsView.setBreathingDisplacementStrength(
                (powerSavingModeEnabled || !breathingEnabled) ? 0f : breathingDisplacementStrength
            );
            lyricsView.setBreathingScaleEnabled(breathingEnabled && !powerSavingModeEnabled);
            lyricsView.setColorChangeIntervalMs(colorChangeIntervalMs);
            lyricsView.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
            lyricsView.setShuffleLayoutRebuildIntervalMs(0L);
            lyricsView.setShuffleSplitMulticolorEnabled(effectiveShuffleSplitEnabled && shuffleSplitMulticolorEnabled);
            lyricsView.setNeonLyricsEnabled(neonDisplayEnabled);
            lyricsView.setLyricsFont(projectionLyricsFontId, projectionLyricsCustomPath);
            lyricsView.setTimeAdjustOffset(projectionSyncOffsetMs);
            lyricsView.setLyricsHorizontalInsetPx(isMainScreenLandscapeMode ? 0 : REAR_LYRICS_LEFT_CLEAR_PX);
            applyFallbackRenderingModeIfNeeded();
            applyRenderFrameRateBudget(lyricsView);
            lyricsView.postInvalidateOnAnimation();
            LogHelper.d(TAG, "✅ 普通歌词视图设置已更新");
        }
        
        // 更新跑马灯
        if (marqueeLightView != null) {
            boolean shouldBeVisible = marqueeLightEnabled || neonBorderEnabled;
            setVisibilityIfChanged(marqueeLightView, shouldBeVisible ? View.VISIBLE : View.GONE);
            marqueeLightView.setBorderFrameEnabled(neonBorderEnabled);
            marqueeLightView.setNeonEffectsEnabled(neonDisplayEnabled);
            marqueeLightView.setMarqueeLightEnabled(marqueeLightEnabled);  // 设置跑马灯是否启用
            marqueeLightView.setLightSize(marqueeLightSize);  // 设置跑马灯线条宽度
            marqueeLightView.setAnimationDuration(marqueeLightDurationMs);
            marqueeLightView.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
            marqueeLightView.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
            marqueeLightView.setColorChangeIntervalMs(colorChangeIntervalMs);
            LyricsProjectionColorSync.bindMarqueeLight(marqueeLightView, randomColorSwitchEnabled, colorChangeIntervalMs);
            LogHelper.d(TAG, "✅ 跑马灯设置已更新: " + (marqueeLightEnabled ? "显示" : "隐藏") + 
                      ", 线条宽度=" + marqueeLightSize + "px, 动画时长=" + marqueeLightDurationMs + "ms" +
                      ", 霓虹效果=" + neonDisplayEnabled + ", 边框显示=" + neonBorderEnabled +
                      ", 视图可见: " + shouldBeVisible);
        }
        
        applySongTitleSafeInsets();

        if (lyricsSourceModeChanged) {
            LogHelper.d(TAG, "🔄 歌词来源模式已切换: " + previousLyricsSourceMode + " -> " + lyricsSourceMode);
            if (shouldUseNetworkApiSource()) {
                stopSuperLyricRealtimeTicker();
            } else {
                SuperLyricApi.ensureReceiverRegistered();
                startSuperLyricRealtimeTicker();
            }
            if (isSuperLyricOnlySourceMode()) {
                enhancedLyricLines = new ArrayList<>();
                superLyricFallbackModeActive = false;
                lastSuperLyricAppliedTextNormalized = "";
                lastSuperLyricAppliedLineTimeMs = -1L;
            }
            String title = stableTitle != null ? stableTitle : "";
            if (!title.isEmpty() && !isLikelyLyricLine(title)) {
                // 来源策略改变后需要强制重拉当前曲目，避免被同曲防重逻辑拦截导致UI不刷新。
                String artist = stableArtist != null ? stableArtist : "";
                currentTrackKey = "";
                inflightApiTrackKey = "";
                apiAttemptedTrackKey = "";
                lastLoadLyricsTrackKey = "";
                lastLoadLyricsTime = 0L;
                loadLyrics(title, artist);
            } else {
                LogHelper.d(TAG, "ℹ 来源模式已更新，等待下一次有效曲目信息触发歌词重载");
            }
        }
        
        LogHelper.d(TAG, "✅ 所有设置已应用");
    }

    private void applyProjectionBackgroundMode() {
        if (mainFrameLayout == null) return;
        final boolean useAlbumArtBackground = !powerSavingModeEnabled && albumArtBackgroundEnabled && !abyssalMirrorEnabled;
        if (contentRootLayout != null) {
            contentRootLayout.setBackgroundColor(useAlbumArtBackground ? 0x00000000 : 0xFF000000);
        }
        if (lyricsView != null) {
            lyricsView.setOpaqueBackgroundEnabled(!useAlbumArtBackground);
        }
        if (albumArtBackgroundView != null) {
            if (!useAlbumArtBackground) {
                setVisibilityIfChanged(albumArtBackgroundView, View.GONE);
                clearAlbumArtBitmap();
                clearAlbumArtBlurEffect();
            }
        }
    }

    private void updateAlbumArtBackground(MediaMetadata metadata) {
        if (albumArtBackgroundView == null) return;
        if (powerSavingModeEnabled || !albumArtBackgroundEnabled || abyssalMirrorEnabled) {
            setVisibilityIfChanged(albumArtBackgroundView, View.GONE);
            clearAlbumArtBitmap();
            clearAlbumArtBlurEffect();
            return;
        }
        Bitmap raw = extractAlbumArtBitmap(metadata);
        if (raw == null) {
            // 切歌中 metadata 短暂无封面时，始终保留旧图，避免底图黑闪。
            return;
        }
        if (raw == currentAlbumArtBitmap) {
            if (albumArtBackgroundView.getVisibility() != View.VISIBLE) {
                setVisibilityIfChanged(albumArtBackgroundView, View.VISIBLE);
            }
            return;
        }
        // 下采样至 ImageView 显示尺寸，减少原生堆内存峰值
        Bitmap sampled = downsampleAlbumArt(raw);
        if (sampled != raw) {
            // raw 来自 metadata.getBitmap() — 由 MediaMetadata 持有，不 recycle
        }
        if (currentAlbumArtBitmap != null && currentAlbumArtBitmap != sampled) {
            currentAlbumArtBitmap.recycle();
        }
        currentAlbumArtBitmap = sampled;
        albumArtBackgroundView.setImageBitmap(sampled);
        applyAlbumArtBlurEffectIfSupported();
        setVisibilityIfChanged(albumArtBackgroundView, View.VISIBLE);
    }

    /** 将专辑图下采样至不超过屏幕宽度的高度，减少原生堆内存占用。 */
    private Bitmap downsampleAlbumArt(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        int maxW = dp(360); // 背屏 720px 宽，半屏足矣
        int maxH = dp(480);
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxW && h <= maxH) return src;
        float scale = Math.min((float) maxW / w, (float) maxH / h);
        int sw = Math.round(w * scale);
        int sh = Math.round(h * scale);
        try {
            return Bitmap.createScaledBitmap(src, sw, sh, true);
        } catch (Exception e) {
            LogHelper.w(TAG, "downsampleAlbumArt failed: " + e.getMessage());
            return src;
        }
    }

    private void clearAlbumArtBitmap() {
        if (albumArtBackgroundView != null) {
            albumArtBackgroundView.setImageDrawable(null);
        }
        if (currentAlbumArtBitmap != null && !currentAlbumArtBitmap.isRecycled()) {
            currentAlbumArtBitmap.recycle();
        }
        currentAlbumArtBitmap = null;
    }

    private Bitmap extractAlbumArtBitmap(MediaMetadata metadata) {
        if (metadata == null) return null;
        Bitmap bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bmp != null) return bmp;
        bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp != null) return bmp;
        bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp != null) return bmp;
        try {
            if (metadata.getDescription() != null) {
                return metadata.getDescription().getIconBitmap();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void applyAlbumArtBlurEffectIfSupported() {
        if (albumArtBackgroundView == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (albumArtBlurRadiusPx <= 0f) {
                albumArtBackgroundView.setRenderEffect(null);
                return;
            }
            albumArtBackgroundView.setRenderEffect(
                RenderEffect.createBlurEffect(
                    albumArtBlurRadiusPx,
                    albumArtBlurRadiusPx,
                    Shader.TileMode.CLAMP
                )
            );
        }
    }

    private void clearAlbumArtBlurEffect() {
        if (albumArtBackgroundView == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            albumArtBackgroundView.setRenderEffect(null);
        }
    }

    private float adaptiveShuffleSplitScaleVariance(float textSize) {
        float minSize = 40f;
        float maxSize = 140f;
        float t = (textSize - minSize) / (maxSize - minSize);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        float maxVariance = 0.30f;
        float minVariance = 0.16f;
        return maxVariance - (maxVariance - minVariance) * t;
    }

    
    /**
     * 隐藏系统UI（状态栏和导航栏），实现全屏显示
     * 只在背屏时隐藏，主屏时显示状态栏（避免画面闪烁）
     */
    private void hideSystemUI() {
        // 检查当前所在的屏幕
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displayId = getDisplay().getDisplayId();
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }
        
        // 只在背屏时隐藏状态栏，主屏时显示状态栏（避免画面闪烁）
        if (displayId != 1) {
            LogHelper.d(TAG, "📍 在主屏(displayId=" + displayId + ")，显示状态栏");
            // 在主屏时显示状态栏
            showSystemUI();
            return;
        }
        
        LogHelper.d(TAG, "📍 在背屏(displayId=1)，仅隐藏状态栏（保留导航栏/手势 inset，避免边缘滑动被系统当成「拉出系统栏」而非返回）");
        View decorView = getWindow().getDecorView();
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.navigationBars());
                controller.hide(android.view.WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
            }
        } else {
            // API 29 及以下：尽量不隐藏导航区，减轻与副屏手势返回冲突
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }
    
    /**
     * 显示系统UI（状态栏和导航栏）
     * 在主屏时使用，避免画面闪烁
     */
    private void showSystemUI() {
        View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ 使用新的API
            getWindow().setDecorFitsSystemWindows(true);
            android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            }
        } else {
            // Android 10 及以下使用旧API
            int uiOptions = View.SYSTEM_UI_FLAG_VISIBLE;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }
    
    /**
     * 隐藏系统UI（状态栏和导航栏），用于主屏横屏模式全屏显示
     * 强制隐藏，不检查displayId
     */
    private void hideSystemUIForMainScreen() {
        LogHelper.d(TAG, "🖥️ 主屏横屏模式：隐藏系统UI（全屏显示）");
        View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ 使用新的API
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Android 10 及以下使用旧API
            int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }
    
    /**
     * 注册系统UI可见性变化监听器（主屏横屏模式专用）
     * 当用户退出全屏（系统UI显示）时，自动销毁Activity，避免占用屏幕
     */
    private void registerSystemUIVisibilityListener() {
        if (!isMainScreenLandscapeMode) {
            return; // 非主屏横屏模式，不需要监听
        }
        
        View decorView = getWindow().getDecorView();
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ 使用定期检查 + WindowInsets 监听
            // 启动定期检查任务，检查系统UI是否显示
            systemUICheckHandler = uiHandler;
            systemUICheckRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || !isMainScreenLandscapeMode || !isInForeground) {
                        return; // Activity已销毁或不是主屏横屏模式，停止检查
                    }
                    
                    try {
                        android.view.WindowInsets insets = decorView.getRootWindowInsets();
                        if (insets != null) {
                            // 检查状态栏和导航栏是否可见
                            boolean statusBarVisible = insets.isVisible(android.view.WindowInsets.Type.statusBars());
                            boolean navBarVisible = insets.isVisible(android.view.WindowInsets.Type.navigationBars());
                            
                            if (statusBarVisible || navBarVisible) {
                                // 系统UI显示（退出全屏），延迟销毁Activity
                                LogHelper.d(TAG, "🖥️ 主屏横屏模式：检测到系统UI显示（statusBar=" + statusBarVisible + ", navBar=" + navBarVisible + "），准备销毁Activity");
                                if (systemUiExitConfirmRunnable != null) {
                                    systemUICheckHandler.removeCallbacks(systemUiExitConfirmRunnable);
                                }
                                systemUiExitConfirmRunnable = () -> {
                                    if (!isFinishing() && isMainScreenLandscapeMode) {
                                        // 再次确认系统UI仍然显示
                                        android.view.WindowInsets recheckInsets = decorView.getRootWindowInsets();
                                        if (recheckInsets != null) {
                                            boolean stillStatusVisible = recheckInsets.isVisible(android.view.WindowInsets.Type.statusBars());
                                            boolean stillNavVisible = recheckInsets.isVisible(android.view.WindowInsets.Type.navigationBars());
                                            if (stillStatusVisible || stillNavVisible) {
                                                LogHelper.d(TAG, "🖥️ 主屏横屏模式：确认退出全屏，销毁Activity");
                                                finishProjectionTask();
                                                return; // 销毁后不再继续检查
                                            }
                                        }
                                    }
                                };
                                systemUICheckHandler.postDelayed(systemUiExitConfirmRunnable, 500); // 延迟500ms，避免误触发（用户可能只是短暂显示系统UI）
                            }
                        }
                    } catch (Exception e) {
                        LogHelper.w(TAG, "检查系统UI状态失败: " + e.getMessage());
                    }
                    
                    // 继续定期检查（每500ms检查一次）
                    if (!isFinishing() && isMainScreenLandscapeMode && isInForeground) {
                        systemUICheckHandler.postDelayed(this, 500);
                    }
                }
            };
            // 延迟500ms后开始第一次检查
            systemUICheckHandler.postDelayed(systemUICheckRunnable, 500);
            
        } else {
            // Android 10 及以下使用 OnSystemUiVisibilityChangeListener
            systemUIVisibilityChangeListener = new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    if (isFinishing() || !isMainScreenLandscapeMode) {
                        return;
                    }
                    
                    // 检查系统UI是否显示
                    // SYSTEM_UI_FLAG_FULLSCREEN 和 SYSTEM_UI_FLAG_HIDE_NAVIGATION 被清除时，系统UI显示
                    boolean systemUIVisible = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 
                                           || (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
                    
                    if (systemUIVisible) {
                        LogHelper.d(TAG, "🖥️ 主屏横屏模式：检测到系统UI显示（退出全屏），准备销毁Activity");
                        // 延迟销毁，避免误触发
                        if (legacySystemUiExitConfirmRunnable != null) {
                            uiHandler.removeCallbacks(legacySystemUiExitConfirmRunnable);
                        }
                        legacySystemUiExitConfirmRunnable = () -> {
                            if (!isFinishing() && isMainScreenLandscapeMode) {
                                // 再次检查系统UI状态，确认真的退出全屏了
                                int currentVisibility = decorView.getSystemUiVisibility();
                                boolean stillVisible = (currentVisibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 
                                                    || (currentVisibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
                                if (stillVisible) {
                                    LogHelper.d(TAG, "🖥️ 主屏横屏模式：确认退出全屏，销毁Activity");
                                    finishProjectionTask();
                                }
                            }
                        };
                        uiHandler.postDelayed(legacySystemUiExitConfirmRunnable, 500); // 延迟500ms，避免误触发
                    }
                }
            };
            decorView.setOnSystemUiVisibilityChangeListener(systemUIVisibilityChangeListener);
        }
        
        LogHelper.d(TAG, "✅ 已注册系统UI可见性变化监听器（主屏横屏模式）");
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            cancelRearStopExitGrace();
            // 主屏横屏模式：隐藏系统UI（全屏）；背屏模式：隐藏系统UI
            if (isMainScreenLandscapeMode) {
                hideSystemUIForMainScreen();
                LogHelper.d(TAG, "🖥️ 主屏横屏模式：窗口获得焦点，隐藏系统UI（全屏）");
            } else {
                RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
                if (getDisplayIdSafe() == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
                    RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
                }
                hideSystemUI();
            }
            // 如果窗口获得焦点，检查是否需要初始化UI（从主屏移动到背屏的情况）
            if (!isFinishing()) {
                // 焦点恢复时：系统 marquee 偶发停止，强制重触发一次（仅当需要滚动时）
                try {
                    if (songTitleText != null) {
                        int vw = songTitleLayoutParams != null ? songTitleLayoutParams.width : songTitleText.getWidth();
                        if (vw > 0) {
                            applySongTitleOverflowMode(vw);
                        } else {
                            // width 可能尚未布局完成，延迟触发
                            songTitleText.postDelayed(() -> {
                                try {
                                    int w = songTitleText.getWidth();
                                    if (w > 0) applySongTitleOverflowMode(w);
                                } catch (Exception ignored) {}
                            }, 120);
                        }
                    }
                } catch (Exception ignored) {
                }
                int displayId = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        displayId = getDisplay().getDisplayId();
                    } catch (Exception e) {
                        LogHelper.e(TAG, "获取displayId失败", e);
                    }
                }

                if (displayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
                    cancelMainScreenPlaceholderTimeout();
                }
                if (enforceProjectionDisplayPolicy("onWindowFocusChanged")) {
                    return;
                }
                if (finishIfIllegalProjectionSurface("onWindowFocusChanged")) {
                    return;
                }
                
                // 在背屏时，立即重新应用常亮flags（防止双击息屏后屏幕熄灭）
                if (displayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
                    RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
                    // 检查背屏常亮开关（与功能页一致）
                    boolean keepScreenOnEnabledFocus = isProjectionKeepScreenOnEnabled();

                    if (keepScreenOnEnabledFocus) {
                        // 立即重新应用常亮flags（与未投放应用时的实现方式一致）
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        );
                        
                        // 适配新API：确保flags持续生效
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true);
                            setTurnScreenOn(true);
                        }
                        
                        // 立即发送唤醒命令（防止双击息屏后屏幕熄灭）
                        if (taskService != null) {
                            try {
                                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                LogHelper.d(TAG, "✅ 已发送唤醒命令（onWindowFocusChanged）");
                            } catch (Exception e) {
                                LogHelper.w(TAG, "发送唤醒命令失败", e);
                            }
                        }
                        
                        LogHelper.d(TAG, "✅ onWindowFocusChanged: 已重新应用常亮flags（防止双击息屏）");
                    }
                }
                
                if (displayId == 1 && lyricsView == null) {
                    initLyricsUiOnRearIfNeeded("onWindowFocusChanged");
                } else if (displayId == 1 && hasLyricsView()) {
                    // UI已创建，重新应用常亮flags（与功能页「投屏常亮」一致）
                    boolean keepScreenOnEnabledFocus2 = isProjectionKeepScreenOnEnabled();

                    if (keepScreenOnEnabledFocus2) {
                        // 与未投放应用时的实现方式一致
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        );
                        LogHelper.d(TAG, "✅ onWindowFocusChanged: 已重新应用常亮flags（UI已创建）");
                    } else {
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        );
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                    
                    // 适配新API：确保flags持续生效
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        setShowWhenLocked(true);
                        setTurnScreenOn(true);
                    }
                }
            }
        }
    }
    
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LogHelper.d(TAG, "🔄 onConfigurationChanged");
        
        // 判断当前所在的屏幕
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displayId = getDisplay().getDisplayId();
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }
        LogHelper.d(TAG, "📍 onConfigurationChanged时displayId=" + displayId + ", initialDisplayId=" + initialDisplayId);

        int mainMode = RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                displayId, false, hasLyricsView());
        if (mainMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
            enforceProjectionDisplayPolicy("onConfigurationChanged");
            return;
        }
        if (RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainMode)) {
            return;
        }

        // 与车控一致：任务已从首次创建 UI 的显示屏迁走则销毁，避免占错主屏
        if (initialDisplayId != -1 && displayId != initialDisplayId) {
            LogHelper.w(TAG, "🛑 onConfigurationChanged检测到displayId变化（从 " + initialDisplayId + " 到 " + displayId + "），立即清理并销毁Activity");
            finishProjectionFromUser("display-changed-onConfig");
            return;
        }
        
        // 背屏且 UI 未创建：调度初始化
        if (displayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID && lyricsView == null) {
            LogHelper.d(TAG, "🎯 onConfigurationChanged：背屏且 UI 未创建，调度初始化");
            if (deferredCreateUiRunnable != null) {
                uiHandler.removeCallbacks(deferredCreateUiRunnable);
            }
            deferredCreateUiRunnable = () -> {
                deferredCreateUiRunnable = null;
                if (!isFinishing() && !isDestroyed()
                        && getDisplayIdSafe() == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
                    initLyricsUiOnRearIfNeeded("onConfigurationChanged");
                }
            };
            uiHandler.postDelayed(deferredCreateUiRunnable, 100);
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // 检查是否是点击通知结束投屏
        if (intent != null && "ACTION_STOP_PROJECTION".equals(intent.getAction())) {
            LogHelper.d(TAG, "🛑 onNewIntent中收到结束投屏请求，立即销毁Activity");
            try {
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                // 立即隐藏窗口，避免显示内容
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                
                finishProjectionFromUser("onNewIntent-stop-intent");
                // 确保立即返回，不执行后续代码
                return;
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理结束投屏请求时发生异常", e);
                // 即使发生异常，也尝试清理资源并销毁Activity
                try {
                    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                    finishProjectionFromUser("onNewIntent-stop-intent-error");
                } catch (Exception e2) {
                    LogHelper.e(TAG, "❌ 销毁Activity时发生异常", e2);
                }
                return;
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        isInForeground = true;
        uiAnimationsCancelled = false;
        handleProjectionForegroundAfterStopGrace();
        if (enforceProjectionDisplayPolicy("onStart")) {
            return;
        }
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        if (getDisplayIdSafe() == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        isInForeground = true;
        uiAnimationsCancelled = false;
        handleProjectionForegroundAfterStopGrace();
        LogHelper.d(TAG, "🟢 onResume");

        if (RearMirootProjectionLifecycle.shouldSkipProjectionResume(
                isFinishing(), projectionExitFlowStarted, finishRequestedByMiRoot)) {
            return;
        }
        
        // 检查是否是点击通知结束投屏
        if (getIntent() != null && "ACTION_STOP_PROJECTION".equals(getIntent().getAction())) {
            LogHelper.d(TAG, "🛑 onResume中收到结束投屏请求");
            finishProjectionFromUser("onResume-stop-intent");
            return;
        }
        
        // 判断当前所在的屏幕
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        LogHelper.d(TAG, "📍 onResume时displayId=" + displayId);

        if (enforceProjectionDisplayPolicy("onResume")) {
            return;
        }

        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        if (getDisplayIdSafe() == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
        }

        if (displayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            cancelMainScreenPlaceholderTimeout();
        }

        if (finishIfIllegalProjectionSurface("onResume")) {
            return;
        }

        // 背屏且 UI 未创建：统一走 initLyricsUiOnRearIfNeeded（占位迁屏 / onCreate 直启兜底）
        if (lyricsView == null && displayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            if (initLyricsUiOnRearIfNeeded("onResume")) {
                resumeSuperLyricLyricsPipeline();
                return;
            }
        } else if (hasLyricsView()) {
            // UI已创建，只需更新状态
            // 再次确保Window flags（与功能页「投屏常亮」一致）
            boolean keepScreenOnEnabledResume2 = isProjectionKeepScreenOnEnabled();

            if (keepScreenOnEnabledResume2) {
                // 与未投放应用时的实现方式一致
                getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                );
                LogHelper.d(TAG, "✅ 背屏常亮已开启（onResume更新），保持屏幕常亮");
            } else {
                getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );
                // 清除常亮标志（如果之前设置了）
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                LogHelper.d(TAG, "⏸️ 背屏常亮已关闭（onResume更新），不保持屏幕常亮");
            }
            
            // 确保锁屏显示设置持续生效（与未投放应用时的实现方式一致）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
            
            // 确保全屏显示（参考充电动画实现：只在背屏时隐藏状态栏）
            // 使用统一的hideSystemUI()方法，它会检查displayId
            RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
            hideSystemUI();
            
            // 应用设置（同步主屏幕设置界面的修改）
            applySettings();

            if (mediaSessionInitializedThisCycle) {
                // 本轮 onCreate / 迁屏 init 已完成 MediaSession 初始化，跳过重复绑定与元数据加载，
                // 避免异步歌词请求序号被递增导致首轮 API 结果被误丢弃，造成歌词显示不对/不更新。
                mediaSessionInitializedThisCycle = false;
            } else {
                // 从后台回到前台或切换播放器后，重新解析活跃会话并刷新元数据/歌词
                setupMediaController();
                updateMediaInfo();
            }
            registerActiveSessionsChangedListener();

            // 重新同步播放状态
            updatePlaybackState();
            resumeLyricsAnimatorAfterForeground();
            resumeSuperLyricLyricsPipeline();

            // 确认在背屏且UI已创建后，才显示通知（界面显示成功后）
            int resumeDisplayId = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    resumeDisplayId = getDisplay().getDisplayId();
                } catch (Exception e) {
                    LogHelper.e(TAG, "获取displayId失败", e);
                }
            }
            // 严格检查：必须在背屏(displayId==1)且UI已创建(lyricsView!=null)且Activity未销毁
            if (resumeDisplayId == 1 && lyricsView != null && !isFinishing()) {
                LogHelper.d(TAG, "✅ 投屏成功（onResume），界面显示成功");
                // 启动音乐投屏背屏常亮服务（确认在背屏且UI已创建）
                startMusicProjectionWakeService();
                notifyMainActivityProjectionStarted();
            } else {
                // 已有歌词 UI 时：不因 resume 瞬间 displayId≠1 停止服务（否则通知栏「正在投屏」被误撤）
                LogHelper.d(TAG, "⏳ 投屏状态检查 (onResume): displayId=" + resumeDisplayId + ", hasLyricsView=" + hasLyricsView() + ", isFinishing=" + isFinishing());
                if (isFinishing()) {
                    stopMusicProjectionWakeService();
                }
            }
        }
        
        // 注册深渊镜视图的传感器监听器（如果存在）
        if (abyssalMirrorLyricsViewGroup != null) {
            abyssalMirrorLyricsViewGroup.registerSensorListener();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        isInForeground = false;
        cancelUiAnimationsIdempotent();
        LogHelper.d(TAG, "🟡 onPause");
        // 锁屏/息屏时仍可能在背屏显示：不要因为 onPause 就停掉 SuperLyric 实时刷新，否则会出现锁屏后歌词跟不上。
        if (!shouldRunSuperLyricRealtimeWhilePaused()) {
            stopSuperLyricRealtimeTicker();
        }
        // 检查是否应该清理（如果Activity被系统回收或进入后台，确保资源被清理）
        // 注意：这里不主动销毁Activity，只是检查状态，真正的清理在onDestroy中进行
        // 但如果检测到异常情况（比如在主屏且正在finishing），可以提前清理
        try {
            int displayId = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    displayId = getDisplay().getDisplayId();
                } catch (Exception e) {
                    LogHelper.e(TAG, "onPause中获取displayId失败", e);
                }
            }
            
            // 如果Activity正在finishing，确保清理资源
            if (isFinishing()) {
                LogHelper.d(TAG, "⚠️ onPause时Activity正在finishing，确保资源清理");
                try {
                    stopMusicProjectionWakeService();
                } catch (Exception e) {
                    LogHelper.w(TAG, "onPause中停止服务失败", e);
                }
            } else if (displayId == 0 && hasLyricsView()) {
                LogHelper.w(TAG, "onPause：背屏歌词 UI 落在主屏，结束投屏");
                finishProjectionFromUser("rear-ui-on-main-onPause");
            } else if (displayId == 1) {
                // 在背屏且没有finishing时，继续维持常亮flags（防止系统清除）
                // 与未投屏时常亮实现方式一致：在onPause时重新应用flags，确保屏幕不会熄灭
                try {
                    boolean keepScreenOnEnabledPause = isProjectionKeepScreenOnEnabled();

                    if (keepScreenOnEnabledPause && getWindow() != null) {
                        // 重新应用常亮flags，防止系统在onPause时清除（与未投屏时常亮实现方式一致）
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        );
                        
                        // 适配新API：确保flags持续生效
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true);
                            setTurnScreenOn(true);
                        }
                        LogHelper.d(TAG, "✅ onPause: 在背屏时重新应用常亮flags，防止屏幕熄灭（与未投屏时常亮一致）");
                    }
                } catch (Exception e) {
                    LogHelper.w(TAG, "onPause中重新应用常亮flags失败", e);
                }
            }
            
            // 注销深渊镜视图的传感器监听器（如果存在）
            if (abyssalMirrorLyricsViewGroup != null) {
                abyssalMirrorLyricsViewGroup.unregisterSensorListener();
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "onPause中检查状态失败", e);
        }

        if (!isFinishing() && !isChangingConfigurations() && !isMainScreenLandscapeMode
                && hasLyricsView() && getDisplayIdSafe() == 1) {
            scheduleRearStopExitGraceIfNeeded("onPause");
        }
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        isInForeground = false;
        cancelUiAnimationsIdempotent();
        LogHelper.d(TAG, "🟡 onStop");
        if (!shouldRunSuperLyricRealtimeWhilePaused()) {
            stopSuperLyricRealtimeTicker();
        }

        if (isFinishing()) {
            return;
        }

        int displayId = getDisplayIdSafe();
        // 与车控一致：仅当已从「首次创建 UI 的显示屏」迁走时才结束投屏；背屏双击等导致的短暂 onStop 仍处于同一 display，不 finish
        boolean movedToOtherDisplay = (initialDisplayId != -1 && displayId != initialDisplayId);
        if (movedToOtherDisplay) {
            // 充电覆盖期间允许短暂迁屏抖动，不主动结束下层投屏。
            if (isChargingOverlayActive()) {
                LogHelper.d(TAG, "⏸️ onStop 检测到充电覆盖迁屏，保留投屏会话，不触发退出");
                return;
            }
            LogHelper.w(TAG, "⚠️ 界面已从初始显示屏移动(displayId=" + displayId + ", initialDisplayId=" + initialDisplayId + ")，结束音乐投屏并销毁Activity");
            finishProjectionFromUser("moved-to-other-display-onStop");
            return;
        }

        // 仍在初始显示屏（或尚未记录 initialDisplayId 的占位阶段）：在背屏时继续维持常亮 flags（防止系统清除）
        try {
            if (!isFinishing() && displayId == 1) {
                // 在背屏且没有finishing时，继续维持常亮flags（与未投屏时常亮实现方式一致）
                try {
                    boolean keepScreenOnEnabledStop = isProjectionKeepScreenOnEnabled();

                    if (keepScreenOnEnabledStop && getWindow() != null) {
                        // 重新应用常亮flags，防止系统在onStop时清除（与未投屏时常亮实现方式一致）
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        );
                        
                        // 适配新API：确保flags持续生效
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true);
                            setTurnScreenOn(true);
                        }
                        LogHelper.d(TAG, "✅ onStop: 在背屏时重新应用常亮flags，防止屏幕熄灭（与未投屏时常亮一致）");
                    }
                } catch (Exception e) {
                    LogHelper.w(TAG, "onStop中重新应用常亮flags失败", e);
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "onStop中检查状态失败", e);
        }

        if (!isFinishing() && !isChangingConfigurations()
                && hasLyricsView() && displayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID) {
            scheduleRearStopExitGraceIfNeeded("onStop");
        } else if (!isFinishing() && !isChangingConfigurations()
                && !hasLyricsView() && displayId == 0 && initialDisplayId == -1) {
            LogHelper.w(TAG, "onStop：主屏占位阶段切到后台，销毁 Activity");
            finishProjectionFromUser("placeholder-onstop");
        } else if (!isFinishing() && !isChangingConfigurations()
                && hasLyricsView() && displayId == 0) {
            LogHelper.w(TAG, "onStop：背屏歌词 UI 落在主屏，销毁 Activity");
            finishProjectionFromUser("rear-ui-on-main-onStop");
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 不在此 finish：背屏双击等操作会误触发 onUserLeaveHint，导致投屏被关。
        // 真正切离背屏界面仍由 onStop（如返回桌面）处理；用户也可用返回键、底部左右滑等结束投屏。
    }
    
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // 用户交互时重新应用常亮flags（与未投屏时常亮实现方式一致）
        // 这可以确保在锁屏时如果用户有任何交互，也能保持屏幕点亮
        try {
            int displayId = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    displayId = getDisplay().getDisplayId();
                } catch (Exception e) {
                    // 忽略错误
                }
            }
            
            if (!isFinishing() && displayId == 1 && getWindow() != null) {
                boolean keepScreenOnEnabled = isProjectionKeepScreenOnEnabled();

                if (keepScreenOnEnabled) {
                    // 重新应用常亮flags（与未投屏时常亮实现方式一致）
                    getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    );
                    
                    // 适配新API：确保flags持续生效
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        setShowWhenLocked(true);
                        setTurnScreenOn(true);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误，避免影响用户交互
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getDisplayIdSafe() == 1) {
            if (bottomSwipeHandler == null) {
                bottomSwipeHandler = new BottomSwipeExitHelper.Handler(
                    this, () -> finishProjectionFromUser("bottom-swipe-exit"));
            }
            bottomSwipeHandler.handleTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void finish() {
        if (hasLyricsView()) {
            RearMirootProjectionLifecycle.hideWindowBeforeProjectionFinish(this);
        }
        final boolean removeTask = hasLyricsView()
                || initialDisplayId == RearMirootProjectionLifecycle.REAR_DISPLAY_ID
                || finishRequestedByMiRoot
                || projectionExitFlowStarted;
        if (removeTask) {
            RearMirootProjectionLifecycle.prepareRearDisplayBeforeFinish(getDisplayIdSafe(), taskService);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
                    } catch (Exception ignored) {
                    }
                }
                finishAndRemoveTask();
                overridePendingTransition(0, 0);
                return;
            }
        }
        super.finish();
        overridePendingTransition(0, 0);
    }

    /**
     * 异常/门禁路径可 finishAndRemoveTask；用户主动结束走 {@link #finish()}（背屏 UI 已建立时用 removeTask 防主屏露 UI）。
     */
    private void finishProjectionTask() {
        if (finishRequestedByMiRoot || projectionExitFlowStarted) {
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                finishAndRemoveTask();
                overridePendingTransition(0, 0);
                return;
            } catch (RuntimeException e) {
                LogHelper.w(TAG, "finishAndRemoveTask 失败，回退 finish: " + e.getMessage());
            }
        }
        finish();
    }

    private void registerLyricsProjectionBackPressedCallback() {
        if (lyricsBackPressedCallbackRegistered) {
            return;
        }
        lyricsBackPressedCallbackRegistered = true;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                LogHelper.d(TAG, "🔙 返回键结束音乐投屏");
                finishProjectionFromUser("back-pressed");
            }
        });
    }
    
    /**
     * 资源清理；用户手势/返回退出时在 {@link #onDestroy()} 调用，不在 finish 前 restore 官方背屏。
     *
     * @param restoreOfficial 是否同步恢复官方背屏（异常/非 MiRoot 主动结束）
     */
    private void performProjectionResourceCleanup(boolean restoreOfficial) {
        if (projectionCleanupDone) {
            return;
        }
        projectionCleanupDone = true;
        try {
            LogHelper.d(TAG, "🧹 音乐投屏资源清理 (restoreOfficial=" + restoreOfficial + ")");

            cancelMainScreenPlaceholderTimeout();
            cancelRearLyricsUiInitPoll();
            cancelRearStopExitGrace();
            mainScreenPolicyHandler.removeCallbacksAndMessages(null);
            uiHandler.removeCallbacksAndMessages(null);
            pendingNoLyricsRunnable = null;

            unregisterActiveSessionsChangedListener();

            LyricsTaskTracking.clearLastTask();
            
            
            // 停止音乐投屏背屏常亮服务（内部会停止唤醒循环）
            try {
                stopMusicProjectionWakeService(true);
            } catch (Exception e) {
                LogHelper.w(TAG, "停止背屏常亮服务失败", e);
            }
            try {
                ProjectionOnlyNotificationHelper.cancelMusic(this);
                ProjectionOngoingNotifications.cancelAll(getApplicationContext());
            } catch (Exception ignored) {
            }
            releaseProjectionProximitySession();

            if (!projectionExitFlowStarted) {
                try {
                    cleanupWindow(false);
                } catch (Exception e) {
                    LogHelper.w(TAG, "清理窗口资源失败", e);
                }
            }

            if (restoreOfficial && !finishRequestedByMiRoot) {
                try {
                    ITaskService tsSnapshot = taskService;
                    OfficialSubscreenMiRootProjectionSession.release(getApplicationContext(), tsSnapshot);
                } catch (Exception e) {
                    LogHelper.w(TAG, "恢复官方背屏服务（Session）失败", e);
                }
            }
            
            // 解绑TaskService（在恢复官方服务之后）
            try {
                unbindTaskService();
            } catch (Exception e) {
                LogHelper.w(TAG, "解绑TaskService失败", e);
            }
            
            // 停止歌词动画器
            if (lyricsAnimator != null) {
                try {
                    lyricsAnimator.stop();
                    lyricsAnimator = null;
                    LogHelper.d(TAG, "✅ 歌词动画器已停止");
                } catch (Exception e) {
                    LogHelper.w(TAG, "停止歌词动画器失败", e);
                }
            }
            
            // 注销MediaController回调
            if (mediaController != null && mediaControllerCallback != null) {
                try {
                    mediaController.unregisterCallback(mediaControllerCallback);
                    mediaControllerCallback = null;
                    LogHelper.d(TAG, "✅ MediaController回调已注销");
                } catch (Exception e) {
                    LogHelper.w(TAG, "注销MediaController回调失败", e);
                }
            }
            mediaController = null;
            if (kuwoCarMediaSessionHelper != null) {
                kuwoCarMediaSessionHelper.disconnect();
                kuwoCarMediaSessionHelper = null;
            }
            kuwoCarLyricsSessionActive = false;

            // 停止位置同步
            try {
                stopPositionSync();
            } catch (Exception e) {
                LogHelper.w(TAG, "停止位置同步失败", e);
            }
            
            // 注销锁屏监听
            if (screenReceiver != null) {
                try {
                    unregisterReceiver(screenReceiver);
                    screenReceiver = null;
                    LogHelper.d(TAG, "✅ 已注销锁屏监听");
                } catch (Exception e) {
                    LogHelper.w(TAG, "注销锁屏监听失败", e);
                }
            }
            // 与 SuperLyricApi ModuleDemo 一致：投屏结束只移除 UI 监听，不 unregisterReceiver，
            // 避免下次「仅 SuperLyric」整段无 onLyric 推送。
            // 停止系统UI可见性检查（主屏横屏模式）
            if (systemUICheckHandler != null && systemUICheckRunnable != null) {
                try {
                    systemUICheckHandler.removeCallbacks(systemUICheckRunnable);
                    systemUICheckHandler = null;
                    systemUICheckRunnable = null;
                    LogHelper.d(TAG, "✅ 已停止系统UI可见性检查");
                } catch (Exception e) {
                    LogHelper.w(TAG, "停止系统UI可见性检查失败", e);
                }
            }
            
            // 注销系统UI可见性变化监听器（Android 10及以下）
            if (systemUIVisibilityChangeListener != null) {
                try {
                    View decorView = getWindow().getDecorView();
                    if (decorView != null) {
                        decorView.setOnSystemUiVisibilityChangeListener(null);
                    }
                    systemUIVisibilityChangeListener = null;
                    LogHelper.d(TAG, "✅ 已注销系统UI可见性变化监听器");
                } catch (Exception e) {
                    LogHelper.w(TAG, "注销系统UI可见性变化监听器失败", e);
                }
            }
            
            // 清理资源：断开视图与歌词数据引用，便于 GC 回收大布局/纹理
            if (abyssalMirrorLyricsViewGroup != null) {
                try {
                    abyssalMirrorLyricsViewGroup.setOnPrevNextGestureListener(null);
                } catch (Exception e) {
                    LogHelper.w(TAG, "解除深渊镜手势监听失败", e);
                }
                abyssalMirrorLyricsViewGroup = null;
            }
            abyssalMirrorLyricsView = null;
            marqueeLightView = null;
            buttonLayout = null;
            mediaButtonBarLayoutParams = null;
            songTitleText = null;
            artistText = null;
            prevButton = null;
            playPauseButton = null;
            nextButton = null;
            albumArtBackgroundView = null;
            clearAlbumArtBitmap();
            contentRootLayout = null;
            mainFrameLayout = null;
            enhancedLyricLines.clear();
            if (lyricsView != null) {
                try {
                    lyricsView.setTrackLoading(true);
                } catch (Exception ignored) {
                }
                lyricsView = null;
            }
            
            // 通知MainActivity更新Flutter端状态（确保状态同步）
            try {
                notifyMainActivityProjectionStopped();
            } catch (Exception e) {
                LogHelper.w(TAG, "通知MainActivity失败", e);
            }
            
            // 首先清除静态实例，避免被其他逻辑重新使用
            if (currentInstance == this) {
                currentInstance = null;
                LogHelper.d(TAG, "✅ 已清除静态实例");
            } else if (currentInstance != null) {
                // 如果静态实例不是当前实例，也清除（可能是旧的实例）
                LogHelper.w(TAG, "⚠️ 检测到静态实例不是当前实例，强制清除");
                currentInstance = null;
            }
            LogHelper.d(TAG, "✅ 音乐投屏资源清理完成");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 执行清理并退出时发生异常", e);
            // 即使发生异常，也确保静态实例被清除和状态通知
            currentInstance = null;
            try {
                notifyMainActivityProjectionStopped();
            } catch (Exception e2) {
                LogHelper.e(TAG, "❌ 异常情况下通知MainActivity也失败", e2);
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        isInForeground = false;
        cancelMainScreenPlaceholderTimeout();
        cancelRearStopExitGrace();
        stopSuperLyricRealtimeTicker();
        try {
            if (kuwoBroadcastLyricBridge != null) {
                kuwoBroadcastLyricBridge.stop();
                kuwoBroadcastLyricBridge = null;
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "停止酷我广播歌词桥失败: " + e.getMessage());
        }
        try {
            lyricsParseExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        try {
            superLyricWordFusionExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        performProjectionResourceCleanup(!finishRequestedByMiRoot);

        super.onDestroy();

        if (finishRequestedByMiRoot) {
            RearMirootProjectionLifecycle.scheduleOfficialSubscreenRestoreAfterDestroy(
                    getApplicationContext(), null);
        }

        LogHelper.d(TAG, "🔴 onDestroy开始");
        updateHookSourceStatusText(LyricsRuntimeSource.IDLE);

        // 立即清除静态实例，避免在检查时误判为正在运行
        if (currentInstance == this) {
            currentInstance = null;
            setBroadcastStarted(false); // 清除广播启动标记
            LogHelper.d(TAG, "✅ 已在onDestroy开始时清除静态实例和广播启动标记");
        } else if (currentInstance != null) {
            LogHelper.w(TAG, "⚠️ 检测到静态实例不是当前实例，强制清除");
            currentInstance = null;
            setBroadcastStarted(false); // 清除广播启动标记
        }
        
        // 注销背屏常亮开关状态变化监听
        if (rearAssistProximityPrefsListener != null) {
            try {
                RearAssistPrefs.INSTANCE.prefs(this).unregisterOnSharedPreferenceChangeListener(rearAssistProximityPrefsListener);
                rearAssistProximityPrefsListener = null;
                LogHelper.d(TAG, "✅ 已注销背屏遮盖偏好监听");
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 注销背屏遮盖偏好监听失败", e);
            }
        }
        releaseProjectionProximitySession();
        if (keepScreenOnPreferenceListener != null) {
            try {
                SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                prefs.unregisterOnSharedPreferenceChangeListener(keepScreenOnPreferenceListener);
                keepScreenOnPreferenceListener = null;
                LogHelper.d(TAG, "✅ 已注销背屏常亮开关状态变化监听");
            } catch (Exception e) {
                LogHelper.w(TAG, "注销背屏常亮开关状态变化监听失败", e);
            }
        }
        
        // 再次尝试通知MainActivity（确保状态同步）
        // 这样可以处理双击息屏等系统强制销毁的情况
        try {
            notifyMainActivityProjectionStopped();
            LogHelper.d(TAG, "✅ 已通知MainActivity音乐投屏已停止（onDestroy）");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 通知MainActivity失败", e);
            // 如果通知失败，尝试延迟重试一次
            try {
                if (notifyStopRetryRunnable != null) {
                    uiHandler.removeCallbacks(notifyStopRetryRunnable);
                }
                notifyStopRetryRunnable = () -> {
                    try {
                        notifyMainActivityProjectionStopped();
                        LogHelper.d(TAG, "✅ 延迟重试：已通知MainActivity音乐投屏已停止");
                    } catch (Exception e2) {
                        LogHelper.e(TAG, "❌ 延迟重试通知MainActivity也失败", e2);
                    }
                };
                uiHandler.postDelayed(notifyStopRetryRunnable, 500);
            } catch (Exception e3) {
                LogHelper.e(TAG, "❌ 创建延迟重试失败", e3);
            }
        }
        
        // 音乐投屏结束不再启动官方副屏 Launcher（不调用 restoreOfficialLauncherInBackground）
    }
    
    /**
     * 清理窗口资源（清除窗口标志和属性；可选恢复系统栏布局）。
     *
     * @param restoreDecorAndBars 在即将 {@link #finish()} 时应为 {@code false}：透明主题下若
     *                            {@code setDecorFitsSystemWindows(true)} 会改变内容 insets，透明窗口新露出的区域会透出副屏底层，用户会看到一闪空白/白屏。
     */
    private void cleanupWindow(boolean restoreDecorAndBars) {
        try {
            LogHelper.d(TAG, "🧹 开始清理窗口资源 (restoreDecorAndBars=" + restoreDecorAndBars + ")");
            
            if (getWindow() == null) {
                LogHelper.w(TAG, "⚠️ Window为null，跳过清理");
                return;
            }
            
            // 清除窗口标志（保持常亮、锁屏显示等），避免一直耗电
            getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            );
            
            // 清除新API设置，确保完全关闭常亮
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    setShowWhenLocked(false);
                    setTurnScreenOn(false);
                    LogHelper.d(TAG, "✅ 已清除新API常亮设置");
                } catch (Exception e) {
                    LogHelper.w(TAG, "清除新API常亮设置失败", e);
                }
            }

            if (!restoreDecorAndBars) {
                LogHelper.d(TAG, "✅ 窗口资源清理完成（跳过恢复系统栏，避免退出投屏闪白）");
                return;
            }
            
            // 恢复系统UI可见性（显示状态栏和导航栏）
            View decorView = getWindow().getDecorView();
            if (decorView != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ 使用新的API
                    try {
                        getWindow().setDecorFitsSystemWindows(true);
                        android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
                        if (controller != null) {
                            controller.show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                        }
                    } catch (Exception e) {
                        LogHelper.w(TAG, "恢复系统UI可见性失败（Android 11+）", e);
                    }
                } else {
                    // Android 10 及以下使用旧API
                    try {
                        int uiOptions = View.SYSTEM_UI_FLAG_VISIBLE;
                        decorView.setSystemUiVisibility(uiOptions);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "恢复系统UI可见性失败（Android 10-）", e);
                    }
                }
            }
            
            LogHelper.d(TAG, "✅ 窗口资源清理完成");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 清理窗口资源时发生异常", e);
        }
    }

    private void cancelUiAnimationsIdempotent() {
        if (uiAnimationsCancelled) return;
        // 充电覆盖层在前台时歌词 Activity 仍会 onPause，但底层投屏需保持位置推进，避免结束后逐字高亮卡顿。
        if (isChargingOverlayActive()) {
            if (BuildConfig.DEBUG) {
                LogHelper.d(TAG, "充电覆盖层在前台，保持歌词动画器运行");
            }
            return;
        }
        uiAnimationsCancelled = true;
        try {
            if (lyricsAnimator != null) {
                lyricsAnimator.pause();
            }
        } catch (Exception ignored) {
        }
        try {
            if (lyricsView != null) {
                lyricsView.animate().cancel();
            }
        } catch (Exception ignored) {
        }
        try {
            if (abyssalMirrorLyricsViewGroup != null) {
                abyssalMirrorLyricsViewGroup.animate().cancel();
            }
        } catch (Exception ignored) {
        }
        try {
            if (marqueeLightView != null) {
                marqueeLightView.animate().cancel();
            }
        } catch (Exception ignored) {
        }
    }
    
    /**
     * 恢复官方Launcher（在背屏时）
     * 确保用户手动退出投屏时正确恢复
     * 注意：enableOfficialGesture() 已经调用了 enableSubScreenLauncher()，但这里仍然显式调用以确保恢复
     * enableSubScreenLauncher() 是幂等的，多次调用不会出错
     * 
     * @deprecated 使用 restoreOfficialLauncherInBackground() 代替，确保在后台线程执行
     */
    @Deprecated
    private void restoreOfficialLauncher() {
        // 检查是否在背屏
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displayId = getDisplay().getDisplayId();
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }
        
        // 只有在背屏时才恢复Launcher
        if (displayId == 1 && taskService != null) {
            AppExecutors.runInBackground(() -> {
                try {
                    LogHelper.d(TAG, "🔄 开始恢复官方Launcher（退出投屏）");

                    // 确保恢复官方Launcher
                    // 注意：enableOfficialGesture() 可能已经调用了 enableSubScreenLauncher()
                    // 但这里仍然显式调用以确保恢复（enableSubScreenLauncher() 是幂等的）
                    boolean success = taskService.enableSubScreenLauncher();
                    if (success) {
                        LogHelper.d(TAG, "✅ 已恢复官方Launcher（退出投屏）");
                    } else {
                        LogHelper.w(TAG, "⚠️ 恢复官方Launcher失败（enableSubScreenLauncher返回false）");
                    }

                    // 等待一下，确保Launcher启动
                    Thread.sleep(200);
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 恢复官方Launcher失败", e);
                }
            });
        } else {
            if (displayId != 1) {
                LogHelper.d(TAG, "ℹ️ 不在背屏（displayId=" + displayId + "），跳过恢复Launcher");
            } else if (taskService == null) {
                LogHelper.w(TAG, "⚠️ TaskService未连接，无法恢复官方Launcher");
                // TaskService未连接时，尝试重新绑定（延迟执行，避免阻塞）
                if (restoreLauncherRetryRunnable != null) {
                    uiHandler.removeCallbacks(restoreLauncherRetryRunnable);
                }
                restoreLauncherRetryRunnable = () -> {
                    if (taskService == null) {
                        bindTaskService();
                        // 延迟后再次尝试恢复
                        uiHandler.postDelayed(this::restoreOfficialLauncherInBackground, 500);
                    }
                };
                uiHandler.postDelayed(restoreLauncherRetryRunnable, 100);
            }
        }
    }
    
    /**
     * 在后台线程恢复官方Launcher（使用shell命令，避免显示切换动画）
     * 参考充电动画的实现方式
     */
    private void restoreOfficialLauncherInBackground() {
        try {
            if (taskService != null) {
                // 使用shell命令启动Launcher，避免显示转场动画
                taskService.executeShellCommand(
                    "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity"
                );
                LogHelper.d(TAG, "✅ 已通过shell命令恢复官方Launcher（音乐投屏结束，无动画）");
            } else {
                LogHelper.w(TAG, "⚠️ TaskService未连接，无法恢复官方Launcher");
                // 回退到MainActivity
                MainActivity mainActivity = MainActivity.getCurrentInstance();
                if (mainActivity != null) {
                    mainActivity.executeShellCommand(
                        "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity"
                    );
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 恢复官方Launcher失败", e);
        }
    }
    
    /**
     * 通知MainActivity音乐投屏已启动（广播启动或按钮启动后，MiRoot 主页按钮可显示「正在投屏」并可点击结束）
     */
    private void notifyMainActivityProjectionStarted() {
        try {
            Runnable send = () -> {
                try {
                    MainActivity.sendMusicProjectionStateBroadcast(RearScreenLyricsActivity.this, true);
                    LogHelper.d(TAG, "✅ 已广播音乐投屏已启动");
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 广播投屏已启动失败", e);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                send.run();
            } else {
                uiHandler.post(send);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 通知投屏已启动失败", e);
        }
    }

    /**
     * 通知MainActivity音乐投屏已停止
     */
    private void notifyMainActivityProjectionStopped() {
        try {
            Runnable send = () -> {
                try {
                    MainActivity.sendMusicProjectionStateBroadcast(RearScreenLyricsActivity.this, false);
                    LogHelper.d(TAG, "✅ 已广播音乐投屏已停止");
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 广播投屏已停止失败", e);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                send.run();
            } else {
                uiHandler.post(send);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 通知投屏已停止失败", e);
        }
    }
    
    /**
     * 查找内容区 rootLayout（LinearLayout、黑色背景），用于 applySafeAreaPadding
     * 结构：R.id.content -> 我们 createUI 的 FrameLayout -> rootLayout(LinearLayout 黑底)
     */
    private android.view.View findRootLayoutForSafeArea() {
        android.view.View rootView = getWindow() != null ? getWindow().getDecorView() : null;
        if (rootView == null) return null;
        android.view.View content = rootView.findViewById(android.R.id.content);
        if (content == null || !(content instanceof android.view.ViewGroup) || ((android.view.ViewGroup) content).getChildCount() == 0) return null;
        android.view.View ourFrame = ((android.view.ViewGroup) content).getChildAt(0);
        if (!(ourFrame instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup our = (android.view.ViewGroup) ourFrame;
        for (int i = 0; i < our.getChildCount(); i++) {
            android.view.View ch = our.getChildAt(i);
            if (ch instanceof LinearLayout && ch.getBackground() instanceof android.graphics.drawable.ColorDrawable) {
                if (((android.graphics.drawable.ColorDrawable) ch.getBackground()).getColor() == 0xFF000000) {
                    return ch;
                }
            }
        }
        return null;
    }
    
    /**
     * 应用安全区域（避开摄像头）
     * 参考RearScreenCarControlActivity的实现
     */
    private void applySafeAreaPadding() {
        try {
            // 深渊镜模式：清掉所有水平/垂直内缩，让控件左右上下贴边全屏
            if (abyssalMirrorEnabled) {
                View decor = getWindow().getDecorView();
                if (decor != null) {
                    decor.setPadding(0, 0, 0, 0);
                    decor.setFitsSystemWindows(false);
                }
                View content = decor != null ? decor.findViewById(android.R.id.content) : null;
                if (content != null) {
                    content.setPadding(0, 0, 0, 0);
                    content.setFitsSystemWindows(false);
                }
                // ourFrame = setContentView 传入的 FrameLayout（content 的第一个子 View）
                View ourFrame = null;
                if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
                    ourFrame = ((ViewGroup) content).getChildAt(0);
                    if (ourFrame != null) {
                        ourFrame.setPadding(0, 0, 0, 0);
                        ourFrame.setFitsSystemWindows(false);
                    }
                }
                View rl = findRootLayoutForSafeArea();
                if (rl != null) {
                    rl.setPadding(0, 0, 0, 0);
                    rl.setFitsSystemWindows(false);
                }
                LogHelper.d(TAG, "✅ 深渊镜模式：DecorView + content + ourFrame 无内缩，上下左右贴边");
                if (content != null) content.requestLayout();
                if (ourFrame != null) ourFrame.requestLayout();
                if (decor != null) decor.requestLayout();
                // 首帧 layout 后打印诊断：screenW/screenH 与各层 w/h 对比，定位被缩小的层级
                getWindow().getDecorView().post(() -> {
                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int screenH = getResources().getDisplayMetrics().heightPixels;
                    Point realSize = new Point();
                    try {
                        if (getWindow() != null && getWindow().getDecorView() != null) {
                            android.view.Display d = getWindow().getDecorView().getDisplay();
                            if (d != null) {
                                d.getRealSize(realSize);
                                screenW = realSize.x;
                                screenH = realSize.y;
                            }
                        }
                    } catch (Exception e) { LogHelper.w("AbyssalLayout", "getRealSize failed", e); }
                    View dc = getWindow().getDecorView();
                    View c = dc.findViewById(android.R.id.content);
                    View our = (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() > 0) ? ((ViewGroup) c).getChildAt(0) : null;
                    LogHelper.d("AbyssalLayout", String.format("AbyssalLayout 首帧: screenW=%d screenH=%d", screenW, screenH));
                    if (dc != null) LogHelper.d("AbyssalLayout", String.format("  DecorView: w=%d h=%d padL/R=%d/%d", dc.getWidth(), dc.getHeight(), dc.getPaddingLeft(), dc.getPaddingRight()));
                    if (c != null) LogHelper.d("AbyssalLayout", String.format("  content: w=%d h=%d", c.getWidth(), c.getHeight()));
                    if (our != null) LogHelper.d("AbyssalLayout", String.format("  ourFrame: w=%d h=%d", our.getWidth(), our.getHeight()));
                    if (abyssalMirrorLyricsViewGroup != null) LogHelper.d("AbyssalLayout", String.format("  abyssal: w=%d h=%d (若w<screenW或h<screenH则在上层被缩小)", abyssalMirrorLyricsViewGroup.getWidth(), abyssalMirrorLyricsViewGroup.getHeight()));
                    applyMediaButtonBarInsets();
                });
                applyMediaButtonBarInsets();
                return;
            }
            
            // 如果缓存未初始化且TaskService可用，尝试初始化缓存
            if (!DisplayInfoCache.getInstance().isInitialized() && taskService != null) {
                try {
                    LogHelper.d(TAG, "🔄 缓存未初始化，尝试初始化以获取 cutout（仅用于垂直 inset）");
                    DisplayInfoCache.getInstance().initialize(taskService);
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 初始化缓存失败", e);
                }
            }

            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            View rl = findRootLayoutForSafeArea();
            if (rl != null) {
                int top = 0;
                int bottom = 0;
                if (info != null && info.hasCutout() && info.cutout != null) {
                    top = Math.max(0, info.cutout.top);
                    bottom = Math.max(0, info.cutout.bottom);
                }
                if (rl.getPaddingLeft() != 0 || rl.getPaddingRight() != 0
                    || rl.getPaddingTop() != top || rl.getPaddingBottom() != bottom) {
                    rl.setPadding(0, top, 0, bottom);
                    LogHelper.d(TAG, String.format(
                        "✅ 背屏内容区：左右无 padding（底图满宽），仅垂直安全区 top=%d bottom=%d",
                        top, bottom));
                } else {
                    LogHelper.d(TAG, "ℹ️ 内容区垂直 padding 已正确，跳过更新");
                }
            }
            applyMediaButtonBarInsets();

        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 应用安全区域失败", e);
        }
    }

    private int randomHighSaturationColor() {
        java.util.Random random = new java.util.Random();
        float hue = random.nextFloat() * 360f;
        float saturation = 0.85f + random.nextFloat() * 0.15f;
        float value = 0.85f + random.nextFloat() * 0.15f;
        return android.graphics.Color.HSVToColor(new float[]{hue, saturation, value});
    }
}



