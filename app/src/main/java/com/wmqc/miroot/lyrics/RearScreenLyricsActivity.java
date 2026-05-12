package com.wmqc.miroot.lyrics;

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
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Point;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
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
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate;
import com.wmqc.miroot.rear.ProjectionOngoingNotifications;
import com.wmqc.miroot.rear.ProjectionOnlyNotificationHelper;
import com.wmqc.miroot.rear.RearAssistPrefs;
import com.wmqc.miroot.rear.RearProjectionProximitySession;
import com.wmqc.miroot.mv.MvIntents;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.Lifecycle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

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
        ProjectionOngoingNotifications.cancelAll(a.getApplicationContext());
        try {
            if (a.getWindow() != null) {
                a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                a.getWindow().setFlags(
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "预备结束投屏窗口状态失败", e);
        }
        a.requestProjectionExitSequence("notification-static-stop");
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
    }

    private void scheduleRearStopExitGraceIfNeeded(String reason) {
        cancelRearStopExitGrace();
        if (isFinishing() || isChangingConfigurations() || isMainScreenLandscapeMode) {
            return;
        }
        // 充电动画会短暂顶替歌词投屏为前台；对齐 3.4：此时不应触发 800ms 宽限自毁，
        // 否则歌词清理流程会恢复官方手势/Launcher，进而把充电动画也冲掉（表现为“两边都立刻销毁”）。
        try {
            if (RearChargingAnimationCoordinator.getCurrentAnimation()
                == RearChargingAnimationCoordinator.AnimationType.CHARGING) {
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
            if (isFinishing()) {
                return;
            }
            // 用 RESUMED 判断：部分机型回到背屏桌面只走 onPause、不走 onStop，仍为 STARTED 但已不是 RESUMED。
            if (getLifecycle().getCurrentState() == Lifecycle.State.RESUMED) {
                return;
            }
            LogHelper.w(TAG, reason + "：背屏投屏已不可见且宽限(" + REAR_STOP_EXIT_GRACE_MS + "ms)内未恢复前台，销毁 Activity");
            requestProjectionExitSequence("rear-stop-exit-grace");
        };
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
        if (isMainScreenLandscapeMode) {
            if (d == 0) {
                return false;
            }
            LogHelper.w(TAG, reason + "：磁贴模式仅允许主屏(display 0)，当前 displayId=" + d + "，销毁");
            finishIllegalProjectionSurface();
            return true;
        }
        if (d == 1) {
            return false;
        }
        if (hasLyricsView()) {
            LogHelper.w(TAG, reason + "：非磁贴时歌词界面只能在背屏，当前 displayId=" + d + "，销毁");
            finishIllegalProjectionSurface();
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && d != 0 && d != 1) {
            LogHelper.w(TAG, reason + "：非磁贴时不允许的非常规显示屏(displayId=" + d + ")，销毁");
            finishIllegalProjectionSurface();
            return true;
        }
        return false;
    }

    private void finishIllegalProjectionSurface() {
        try {
            if (getWindow() != null) {
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            }
        } catch (Exception ignored) {
        }
        requestProjectionExitSequence("illegal-surface");
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
                songTitleText.setSelected(true);
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

    /** 歌名显示区避开安全区并限制在可视区域内，超长文本才 marquee 滚动。 */
    private void applySongTitleSafeInsets() {
        if (songTitleText == null && hookSourceStatusText == null) return;
        if (isFinishing()) return;
        try {
            if (!isMainScreenLandscapeMode) {
                ensureDisplayInfoCacheForCutout();
            }
            final int baseLeft = dp(20);
            final int baseRight = dp(20);
            final int gutterAfterCutout = dp(14);
            final int topMargin = (int) (marqueeLightSize + dp(2));
            final int leftSystem = isMainScreenLandscapeMode ? 0 : computeMediaBarLeftInsetPx();
            final int rightSystem = isMainScreenLandscapeMode ? 0 : computeRightSafeInsetPx();
            int containerWidth = 0;
            if (mainFrameLayout != null && mainFrameLayout.getWidth() > 0) {
                containerWidth = mainFrameLayout.getWidth();
            }
            if (containerWidth <= 0) {
                containerWidth = getResources().getDisplayMetrics().widthPixels;
            }
            int contentPadL = contentRootLayout != null ? contentRootLayout.getPaddingLeft() : 0;
            int contentPadR = contentRootLayout != null ? contentRootLayout.getPaddingRight() : 0;
            // rootLayout 可能已经通过 applySafeAreaPadding 吃掉了 cutout inset，
            // 这里对标题边距做“去重”，避免左侧安全区被重复叠加导致过大。
            final int extraLeftInset = Math.max(0, leftSystem - contentPadL);
            final int extraRightInset = Math.max(0, rightSystem - contentPadR);
            final int leftMargin = baseLeft + (extraLeftInset > 0 ? extraLeftInset + gutterAfterCutout : 0);
            final int rightMargin = baseRight + extraRightInset;
            int visibleWidth = containerWidth - leftMargin - rightMargin - contentPadL - contentPadR;

            if (songTitleText != null) {
                LinearLayout.LayoutParams lp = songTitleLayoutParams;
                if (lp == null) {
                    ViewGroup.LayoutParams raw = songTitleText.getLayoutParams();
                    if (raw instanceof LinearLayout.LayoutParams) {
                        lp = (LinearLayout.LayoutParams) raw;
                    } else {
                        lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                    }
                }
                lp.setMargins(leftMargin, topMargin, rightMargin, 0);
                songTitleLayoutParams = lp;
                songTitleText.setLayoutParams(lp);
                if (visibleWidth > dp(120)) {
                    songTitleText.setMaxWidth(visibleWidth);
                }
                applySongTitleOverflowMode(visibleWidth);
            }

            if (hookSourceStatusText != null) {
                LinearLayout.LayoutParams hookLp = hookSourceStatusLayoutParams;
                if (hookLp == null) {
                    ViewGroup.LayoutParams raw = hookSourceStatusText.getLayoutParams();
                    if (raw instanceof LinearLayout.LayoutParams) {
                        hookLp = (LinearLayout.LayoutParams) raw;
                    } else {
                        hookLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                        hookLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                    }
                }
                hookLp.setMargins(leftMargin, 0, rightMargin, 0);
                hookSourceStatusLayoutParams = hookLp;
                hookSourceStatusText.setLayoutParams(hookLp);
                hookSourceStatusText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                if (visibleWidth > dp(120)) {
                    hookSourceStatusText.setMaxWidth(visibleWidth);
                }
            }

            LogHelper.d(TAG, "✅ 歌名安全区已更新: left=" + leftMargin + "px, right=" + rightMargin
                + "px, top=" + topMargin + "px, visibleWidth=" + Math.max(0, visibleWidth) + "px");
        } catch (Exception e) {
            LogHelper.w(TAG, "applySongTitleSafeInsets 失败", e);
        }
    }

    /** 根据系统左侧摄像头/cutout 与较大外边距，更新底部三键容器位置与内边距 */
    private void applyMediaButtonBarInsets() {
        if (buttonLayout == null || mediaButtonBarLayoutParams == null) return;
        if (isFinishing()) return;
        try {
            if (!isMainScreenLandscapeMode) {
                ensureDisplayInfoCacheForCutout();
            }
            final int innerPadH = dp(22);
            final int innerPadV = dp(14);
            final int marginRight = dp(22);
            final int marginBottom = dp(28);
            final int gutterAfterCutout = dp(14);
            if (isMainScreenLandscapeMode) {
                int m = dp(22);
                buttonLayout.setPadding(innerPadH, innerPadV, innerPadH, innerPadV);
                mediaButtonBarLayoutParams.setMargins(m, 0, m, marginBottom);
            } else {
                int leftSystem = computeMediaBarLeftInsetPx();
                buttonLayout.setPadding(innerPadH, innerPadV, innerPadH, innerPadV);
                mediaButtonBarLayoutParams.setMargins(
                    leftSystem + gutterAfterCutout,
                    0,
                    marginRight,
                    marginBottom);
                if (leftSystem > 0) {
                    LogHelper.d(TAG, "媒体控制条: 系统左侧避让 " + leftSystem + "px, 左外边距合计 "
                        + (leftSystem + gutterAfterCutout) + "px");
                }
            }
            buttonLayout.setLayoutParams(mediaButtonBarLayoutParams);
            applySongTitleSafeInsets();
        } catch (Exception e) {
            LogHelper.w(TAG, "applyMediaButtonBarInsets 失败", e);
        }
    }

    /**
     * 鎖屏啟動 +「主屏占位→移動到背屏」時，insets/圓角資訊可能短暫仍是主屏值，造成偶發圓角半徑不正確。
     * 在「背屏 + 鎖屏」情境下沿用固定 101px 的穩定策略（與廣播啟動一致）以避免競態。
     */
    private boolean shouldUseFixedCornerRadius101() {
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
    /** 调试来源文本布局参数缓存：用于按安全区实时刷新边距。 */
    private LinearLayout.LayoutParams hookSourceStatusLayoutParams;
    private ModernLyricsView lyricsView;

    // --- Rear MV mode (mutually exclusive with lyrics view) ---
    private static final String KEY_REAR_DISPLAY_MODE = "rearDisplayMode";
    private static final String VALUE_REAR_MODE_LYRICS = "LYRICS";
    private static final String VALUE_REAR_MODE_MV = "MV";
    private static final String INTENT_EXTRA_REAR_MODE = "rearMode";
    private static final String INTENT_EXTRA_MV_URL = "mvUrl";

    private boolean rearMvModePreferred = false;
    private String pendingMvUrl = "";
    private ExoPlayer mvPlayer;
    private PlayerView mvPlayerView;
    private TextView mvLyricLineView;
    private android.widget.FrameLayout mvLayer;

    private android.widget.FrameLayout mainFrameLayout;
    private LinearLayout contentRootLayout;

    private final Handler mvHandler = uiHandler;
    private Runnable mvNoUrlFallbackRunnable;

    private AbyssalMirrorLyricsView abyssalMirrorLyricsView; // 深渊镜歌词视图（旧版，3D旋转效果）
    private AbyssalMirrorLyricsViewGroup abyssalMirrorLyricsViewGroup; // 深渊镜歌词视图（新版，多层边框效果）
    private Button prevButton;
    private Button playPauseButton;
    private Button nextButton;
    
    private MediaController mediaController;
    private MediaController.Callback mediaControllerCallback;
    /** 酷我车载：MediaBrowser 直连 KwMediaSessionService */
    private KuwoCarMediaSessionHelper kuwoCarMediaSessionHelper;
    /** 当前是否使用酷我 MediaBrowser 会话（用于 extras 歌词与 loadLyrics 分支） */
    private boolean kuwoCarLyricsSessionActive;

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
        setupMediaController();
        updateMediaInfo();
        updatePlaybackState();
    };

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
    private Runnable superLyricRealtimeRunnable;
    private final java.util.concurrent.atomic.AtomicBoolean superLyricRealtimeInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private String lastSuperLyricRealtimeText = "";
    private SuperLyricApi.RealtimeListener superLyricRealtimeListener;
    /** 「仅切歌触发」的歌词加载门控：记录最近一次触发 loadLyrics 的曲目签名。 */
    private String lastLyricsLoadTrackSignature = "";
    /** 酷我 AUDIO_LYRIC 去重：同曲同 payload 不重复解析/设置。 */
    private String lastKuwoAudioLyricTrackKey = "";
    private String lastKuwoAudioLyricPayload = "";
    /** 仍无歌词时再显示「暂无」，避免切歌/异步未返回时闪一下暂无（第三方 API 已结束后的短防抖） */
    private static final long NO_LYRICS_UI_DELAY_MS = 2200L;
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
    private Runnable delayedGestureCheckRunnable;
    private Runnable systemUiExitConfirmRunnable;
    private Runnable deferredCreateUiRunnable;
    private Runnable notifyStopRetryRunnable;
    private Runnable restoreLauncherRetryRunnable;
    private boolean isInForeground = false;
    private boolean uiAnimationsCancelled = false;
    private boolean projectionExitFlowStarted = false;
    private boolean projectionCleanupDone = false;
    private boolean projectionExitUiLocked = false;
    private static final long EXIT_FADE_DURATION_MS = 200L;
    private static final long EXIT_HIDE_WAIT_ONE_FRAME_MS = 16L;
    // 限制背屏目标帧率，降低 DequeueBuffer timeout 风险
    private static final float REAR_RENDER_TARGET_FPS = 18f;
    private static final float REAR_RENDER_TARGET_FPS_SHUFFLE = 15f;
    
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
    private final ExecutorService lyricsParseExecutor = Executors.newSingleThreadExecutor();

    private void beginLyricsAcquisitionForTrack(String pkg, String trackKey, int requestSeq, String title, String artist) {
        currentLyricsSource = LyricsSource.NONE;
        currentLyricsSourcePkg = pkg != null ? pkg : "";
        superLyricFallbackModeActive = false;
        updateHookSourceStatusText("来源：网络API 获取中...");

        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "🎛️ LyricsSource begin: pkg=" + currentLyricsSourcePkg
                + " trackKey=" + trackKey);
        }

        // 切歌立即清空旧歌词，避免跨曲串词
        try {
            enhancedLyricLines = new ArrayList<>();
            lastAbyssalRenderedLineIndex = -1;
            lastAppliedLyricsTrackKey = "";
            lastAppliedLyricsFingerprint = "";
            if (lyricsView != null) {
                lyricsView.setTrackLoading(true);
                lyricsView.clearTokenizationCache();
            }
            LyricsWordTokenizer.clearCaches();
            setLyricsToView(null);
        } catch (Exception ignored) {}

        if (kuwoCarLyricsSessionActive) {
            currentLyricsSource = LyricsSource.KUWO_CAR;
            updateHookSourceStatusText("来源：酷我专用解析链");
            stopSuperLyricRealtimeTicker();
            return;
        }
        if (!shouldUseNetworkApiSource()) {
            updateHookSourceStatusText("来源：SuperLyric 专用");
            // SUPER_LYRIC_ONLY：SuperLyric 通常是逐句回调，需要持续刷新，否则会卡在首次命中那一行。
            startSuperLyricRealtimeTicker();
            fetchLyricsFromSuperLyricApi(title, artist, requestSeq, trackKey);
            return;
        }
        stopSuperLyricRealtimeTicker();
        fetchLyricsFromThirdPartyApi(title, artist, "网络API优先获取", requestSeq, trackKey);
    }

    private void startSuperLyricRealtimeTicker() {
        if (superLyricRealtimeListener != null) {
            return;
        }
        // 以回调驱动为主：只要 SuperLyric 推送，就立刻更新当前句（避免轮询导致耗电）。
        superLyricRealtimeListener = (publisher, title, artist, text, payload, atMs) -> {
            if (isFinishing() || !isInForeground) return;
            if (shouldUseNetworkApiSource()) return; // 不在 SUPER_LYRIC_ONLY
            if (payload == null || payload.text == null) return;
            final String t = payload.text.trim();
            if (t.isEmpty()) return;
            if (TextUtils.equals(t, lastSuperLyricRealtimeText)) return;
            lastSuperLyricRealtimeText = t;
            final int seq = lyricsRequestSeq;
            final String tk = currentTrackKey != null ? currentTrackKey : "";
            final String sourcePkg = resolveCurrentLyricsPackageName();
            uiHandler.post(() -> {
                if (BuildConfig.DEBUG) {
                    LogHelper.d(TAG, "🎤 SuperLyric(回调) recv: publisher=" + (publisher != null ? publisher : "")
                        + ", pos=" + getCurrentPlaybackPositionSafe()
                        + "ms, lineStart=" + payload.lineStartMs
                        + "ms, text=" + safeShort(t));
                }
                applySuperLyricFallbackPayload(payload, sourcePkg, seq, tk);
            });
        };
        SuperLyricApi.addRealtimeListener(superLyricRealtimeListener);

        // 低频兜底：极端情况下回调未到，仍允许很慢地拉一次缓存（避免“完全不更新”）。
        superLyricRealtimeInFlight.set(false);
        lastSuperLyricRealtimeText = "";
        superLyricRealtimeRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || !isInForeground) {
                    stopSuperLyricRealtimeTicker();
                    return;
                }
                if (shouldUseNetworkApiSource()) {
                    // 不在 SUPER_LYRIC_ONLY 模式：停止实时刷新。
                    stopSuperLyricRealtimeTicker();
                    return;
                }
                final String title = stableTitle != null ? stableTitle : "";
                if (title.isEmpty() || isLikelyLyricLine(title)) {
                    // 没有稳定曲名时不刷新（避免误用歌词行作为曲名）。
                    uiHandler.postDelayed(this, 2500L);
                    return;
                }
                final String artist = stableArtist != null ? stableArtist : "";
                final String trackKey = currentTrackKey != null ? currentTrackKey : "";
                final int requestSeq = lyricsRequestSeq;
                if (trackKey.isEmpty() || requestSeq <= 0) {
                    uiHandler.postDelayed(this, 2500L);
                    return;
                }
                if (!superLyricRealtimeInFlight.compareAndSet(false, true)) {
                    uiHandler.postDelayed(this, 2500L);
                    return;
                }
                lyricsParseExecutor.execute(() -> {
                    try {
                        final String sourcePkg = resolveCurrentLyricsPackageName();
                        SuperLyricApi.SuperLyricFallbackPayload payload =
                            SuperLyricApi.fetchFallbackPayload(title, artist, sourcePkg);
                        uiHandler.post(() -> {
                            try {
                                if (payload != null && payload.text != null) {
                                    String text = payload.text.trim();
                                    if (!text.isEmpty() && !TextUtils.equals(text, lastSuperLyricRealtimeText)) {
                                        if (BuildConfig.DEBUG) {
                                            LogHelper.d(TAG, "🎤 SuperLyric(单句) recv: pos="
                                                + getCurrentPlaybackPositionSafe()
                                                + "ms, lineStart=" + payload.lineStartMs
                                                + "ms, text=" + safeShort(text));
                                        }
                                        lastSuperLyricRealtimeText = text;
                                        applySuperLyricFallbackPayload(payload, sourcePkg, requestSeq, trackKey);
                                    }
                                }
                            } finally {
                                superLyricRealtimeInFlight.set(false);
                                if (superLyricRealtimeRunnable != null) {
                                    uiHandler.postDelayed(superLyricRealtimeRunnable, 5000L);
                                }
                            }
                        });
                        return;
                    } catch (Throwable ignored) {
                    }
                    uiHandler.post(() -> {
                        superLyricRealtimeInFlight.set(false);
                        if (superLyricRealtimeRunnable != null) {
                            uiHandler.postDelayed(superLyricRealtimeRunnable, 5000L);
                        }
                    });
                });
            }
        };
        uiHandler.postDelayed(superLyricRealtimeRunnable, 5000L);
    }

    private void stopSuperLyricRealtimeTicker() {
        try {
            if (superLyricRealtimeRunnable != null) {
                uiHandler.removeCallbacks(superLyricRealtimeRunnable);
                superLyricRealtimeRunnable = null;
            }
        } catch (Exception ignored) {
        }
        if (superLyricRealtimeListener != null) {
            try {
                SuperLyricApi.removeRealtimeListener(superLyricRealtimeListener);
            } catch (Exception ignored) {
            }
            superLyricRealtimeListener = null;
        }
        superLyricRealtimeInFlight.set(false);
        lastSuperLyricRealtimeText = "";
    }

    private boolean shouldAcceptApiResultForTrack(String pkg) {
        // 当前由 SuperLyric 兜底时，若 API 命中应允许覆盖以恢复完整 LRC。
        if (currentLyricsSource == LyricsSource.SUPER_LYRIC_FALLBACK) return true;
        // 若 API 来源包名与当前不一致，也不要覆盖
        if (pkg != null && !pkg.isEmpty() && currentLyricsSourcePkg != null && !currentLyricsSourcePkg.isEmpty()) {
            return pkg.equalsIgnoreCase(currentLyricsSourcePkg);
        }
        return true;
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

    private boolean shouldUseSuperLyricFallback() {
        return !VALUE_LYRICS_SOURCE_NETWORK_ONLY.equalsIgnoreCase(lyricsSourceMode);
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

    private void updateHookSourceStatusText(String text) {
        if (!BuildConfig.DEBUG) return;
        if (hookSourceStatusText == null) return;
        setTextIfChanged(hookSourceStatusText, text != null ? text : "");
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

    /** 与 {@link com.wmqc.miroot.car.RearScreenCarControlActivity} 一致：屏底窄条内左右滑结束投屏；raw→decor */
    private static final float BOTTOM_SWIPE_EXIT_ZONE_FRACTION = 0.10f;
    private static final float BOTTOM_SWIPE_EXIT_MIN_HORIZ_DP = 48f;
    /** 水平位移须明显大于垂直分量，避免与歌词上下滑动混淆 */
    private static final float BOTTOM_SWIPE_EXIT_HORIZONTAL_DOMINANCE = 1.35f;
    private boolean bottomSwipeExitPointerDownInZone;
    private float bottomSwipeExitStartY;
    private float bottomSwipeExitStartX;
    /** 已触发上滑退出，避免重复 performCleanup / finish */
    private boolean bottomSwipeExitPending;
    private boolean lyricsBackPressedCallbackRegistered;

    // TaskService连接回调
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            LogHelper.d(TAG, "✅ TaskService已连接（用于屏蔽官方手势服务，RootTaskService）");
            
            // 连接成功后，尝试屏蔽官方手势服务，但失败时不再影响投屏生命周期
            checkAndDisableOfficialGesture();
            uiHandler.post(() -> {
                if (!isFinishing()) {
                    applyMediaButtonBarInsets();
                }
            });
            
            // 延迟500ms再尝试一次，增加成功率
            if (taskServiceRecheckRunnable != null) {
                uiHandler.removeCallbacks(taskServiceRecheckRunnable);
            }
            taskServiceRecheckRunnable = () -> {
                if (taskService != null && !isFinishing()) {
                    checkAndDisableOfficialGesture();
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
    private static final String KEY_GESTURE_CONTROL = "gestureControl";
    private static final String KEY_POWER_SAVING_MODE = "powerSavingMode";
    private static final String KEY_BACKGROUND_TEXTURE = "backgroundTexture";
    private static final String KEY_AUTO_PROJECTION = "autoProjection";
    private static final String KEY_BREATHING_RHYTHM_MS = "breathingRhythmMs";
    private static final String KEY_BREATHING_SCALE_VARIANCE = "breathingScaleVariance";
    private static final String KEY_BREATHING_DISPLACEMENT_STRENGTH = "breathingDisplacementStrength";
    private static final String KEY_COLOR_CHANGE_INTERVAL_MS = "colorChangeIntervalMs";
    private static final String KEY_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS = "shuffleLayoutRebuildIntervalMs";
    private static final String KEY_PROJECTION_SYNC_OFFSET_MS = "projectionSyncOffsetMs";
    private static final String KEY_LYRICS_SOURCE_MODE = "lyricsSourceMode";
    private static final String VALUE_LYRICS_SOURCE_NETWORK_ONLY = "NETWORK_ONLY";
    private static final String VALUE_LYRICS_SOURCE_SUPER_ONLY = "SUPER_LYRIC_ONLY";
    private static final String VALUE_LYRICS_SOURCE_MIXED = "MIXED";
    /** 与 [com.wmqc.miroot.ui.music.LyricsUiSettings] 中默认值一致（毫秒）。 */
    private static final int DEFAULT_PROJECTION_SYNC_OFFSET_MS = 650;
    private static final String KEY_ABYSSAL_MIRROR = "abyssalMirror"; // 深渊镜效果开关
    private static final String KEY_ABYSSAL_GYRO_SENSITIVITY = "abyssalGyroSensitivity"; // 深渊镜陀螺仪跟随倍数
    private static final String KEY_ABYSSAL_MOVABLE_RANGE = "abyssalMovableRange"; // 深渊镜可移动范围倍率
    private static final String KEY_PROJECTION_LYRICS_FONT = "projectionLyricsFont";
    private static final String KEY_PROJECTION_LYRICS_CUSTOM_PATH = "projectionLyricsCustomPath";
    private static final String KEY_ABYSSAL_LYRICS_FONT = "abyssalLyricsFont";
    private static final String KEY_ABYSSAL_LYRICS_CUSTOM_PATH = "abyssalLyricsCustomPath";
    private static final float DEFAULT_ABYSSAL_GYRO_SENSITIVITY = 1.0f;
    private static final float DEFAULT_ABYSSAL_MOVABLE_RANGE = 2.5f;
    private static final float DEFAULT_TEXT_SIZE = 65f;  // 默认歌词文本大小65px
    private static final float DEFAULT_BACKGROUND_TEXTURE_SIZE = 1.3f;
    private static final int DEFAULT_NORMAL_LYRICS_ALPHA = 30;  // 30%透明度
    private static final int DEFAULT_BACKGROUND_TEXTURE_ALPHA = 20;  // 20%透明度
    private static final int DEFAULT_BREATHING_RHYTHM_MS = 2000;
    private static final float DEFAULT_BREATHING_SCALE_VARIANCE = 0.055f;
    private static final float DEFAULT_BREATHING_DISPLACEMENT_STRENGTH = 1f;
    private static final int DEFAULT_COLOR_CHANGE_INTERVAL_MS = 1500;
    private static final int DEFAULT_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS = 0;
    private float currentTextSize = DEFAULT_TEXT_SIZE;
    private float backgroundTextureSize = DEFAULT_BACKGROUND_TEXTURE_SIZE;
    private int normalLyricsAlpha = DEFAULT_NORMAL_LYRICS_ALPHA;
    private int backgroundTextureAlpha = DEFAULT_BACKGROUND_TEXTURE_ALPHA;
    private boolean wordByWordEnabled = false;  // 默认关闭逐字显示
    private boolean shuffleSplitEffectEnabled = false;
    private String shuffleSplitMode = "WORD";
    private boolean shuffleSplitOnlyCurrentLine = true;
    private boolean marqueeLightEnabled = true;  // 默认开启跑马灯
    private boolean neonDisplayEnabled = true;  // 霓虹总开关（歌词发光 + 边框受 neonBorder 约束）
    private boolean neonBorderEnabled = true;  // 「边框显示」：是否绘制边缘边框（无霓虹时为纯描边）
    private float marqueeLightSize = 17f;  // 默认跑马灯线条宽度17px
    private boolean gestureControlEnabled = false;  // 默认关闭手势控制
    private boolean powerSavingModeEnabled = false;  // 默认关闭省电模式
    private boolean backgroundTextureEnabled = false;  // 默认关闭歌词底图显示
    private int breathingRhythmMs = DEFAULT_BREATHING_RHYTHM_MS;
    private float breathingScaleVariance = DEFAULT_BREATHING_SCALE_VARIANCE;
    private float breathingDisplacementStrength = DEFAULT_BREATHING_DISPLACEMENT_STRENGTH;
    private int colorChangeIntervalMs = DEFAULT_COLOR_CHANGE_INTERVAL_MS;
    private int shuffleLayoutRebuildIntervalMs = DEFAULT_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS;
    /** 与 [com.wmqc.miroot.ui.music.LyricsSettingsRepository] 键名一致；正数表示歌词相对媒体进度提前显示（毫秒）。 */
    private int projectionSyncOffsetMs = DEFAULT_PROJECTION_SYNC_OFFSET_MS;
    private boolean abyssalMirrorEnabled = false;  // 默认关闭深渊镜效果
    private float abyssalGyroSensitivity = DEFAULT_ABYSSAL_GYRO_SENSITIVITY;  // 深渊镜陀螺仪跟随倍数
    private float abyssalMovableRange = DEFAULT_ABYSSAL_MOVABLE_RANGE;  // 深渊镜可移动范围倍率
    private String projectionLyricsFontId = LyricsFontHelper.DEFAULT_ID;
    private String projectionLyricsCustomPath;
    private String abyssalLyricsFontId = LyricsFontHelper.DEFAULT_ID;
    private String abyssalLyricsCustomPath;
    private String lyricsSourceMode = VALUE_LYRICS_SOURCE_MIXED;
    private String lastAppliedSettingsFingerprint = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 禁用转场动画（去掉背屏切换界面的动画）
        overridePendingTransition(0, 0);
        
        // 固定黑底，避免退出/切换瞬间透出系统底色导致闪白
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
        
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
                requestProjectionExitSequence("onCreate-stop-intent");
                // 确保立即返回，不执行后续代码
                return;
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理结束投屏请求时发生异常", e);
                // 即使发生异常，也尝试清理资源并销毁Activity
                try {
                    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                    requestProjectionExitSequence("onCreate-stop-intent-error");
                } catch (Exception e2) {
                    LogHelper.e(TAG, "❌ 销毁Activity时发生异常", e2);
                }
                return;
            }
        }

        // 兜底校验：防止磁贴/外部组件直接 am start 该 Activity 从而绕过 MainActivity 的激活门禁。
        if (!OfflineActivationRepository.INSTANCE.isActivated(this)) {
            try {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.activation_required_to_use),
                    android.widget.Toast.LENGTH_SHORT
                ).show();
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

        // 检查是否通过广播启动（通过Intent的extra参数判断）
        Intent intent = getIntent();
        if (intent != null) {
            boolean isBroadcast = intent.getBooleanExtra("isBroadcast", false);
            setBroadcastStarted(isBroadcast);
            if (isBroadcast) {
                LogHelper.d(TAG, "📻 检测到通过广播启动的音乐投屏，不受自动投屏开关影响");
            }
            // 检查是否在主屏横屏模式（通过磁贴快捷键启动）
            isMainScreenLandscapeMode = intent.getBooleanExtra("isMainScreenLandscape", false);
            if (isMainScreenLandscapeMode) {
                LogHelper.d(TAG, "🖥️ 检测到主屏横屏模式启动（磁贴快捷键）");
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

        // 磁贴快捷：仅允许在主屏(display 0)展示歌词；若系统错误把 Activity 落在背屏等其它屏则立即销毁
        if (isMainScreenLandscapeMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != 0) {
                LogHelper.w(TAG, "磁贴主屏模式启动时不在主屏(displayId=" + displayId + ")，销毁");
                finishIllegalProjectionSurface();
                return;
            }
            LogHelper.d(TAG, "🖥️ 在主屏横屏模式启动，创建歌词显示界面");

            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

            setupWindow();

            WindowManager.LayoutParams paramsMain = getWindow().getAttributes();
            paramsMain.width = WindowManager.LayoutParams.MATCH_PARENT;
            paramsMain.height = WindowManager.LayoutParams.MATCH_PARENT;
            getWindow().setAttributes(paramsMain);

            loadSettings();

            currentTextSize = currentTextSize * 2f;
            marqueeLightSize = marqueeLightSize * 2.3f;
            LogHelper.d(TAG, "🖥️ 主屏横屏模式：字体大小调整为 " + currentTextSize + "px (原值x2), " +
                    "底图字体大小倍数保持设置值 " + backgroundTextureSize + " (跟随字体大小), " +
                    "跑马灯线条宽度调整为 " + marqueeLightSize + "px (原值x2.3)");

            createUI();

            getWindow().getDecorView().post(() -> {
                hideSystemUIForMainScreen();
                LogHelper.d(TAG, "🖥️ 主屏横屏模式：已隐藏系统UI（全屏）");
            });

            initLyricsAnimator();
            setupMediaController();
            updateMediaInfo();
            updatePlaybackState();
            registerActiveSessionsChangedListener();

            bindTaskService();
            registerScreenReceiver();
            registerKeepScreenOnPreferenceListener();
            registerSystemUIVisibilityListener();

            LogHelper.d(TAG, "✅ 主屏横屏模式初始化完成");
            initialDisplayId = 0;
            return;
        }

        // 背屏：正常歌词投屏（唯一允许的非磁贴展示面）
        if (displayId == 1) {
            initialDisplayId = displayId;
            LogHelper.d(TAG, "🎯 在背屏执行，开始设置歌词显示");

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

            startMusicProjectionWakeService();
            bindTaskService();
            registerScreenReceiver();
            registerKeepScreenOnPreferenceListener();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != 0 && displayId != 1) {
            LogHelper.w(TAG, "非磁贴入口落在非常规显示屏(displayId=" + displayId + ")，销毁");
            finishIllegalProjectionSurface();
            return;
        }

        LogHelper.d(TAG, "💤 在主屏占位等待迁往背屏（最长 " + MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS + "ms）");
        scheduleMainScreenPlaceholderTimeout();
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
                        // 开关状态变化，实时响应
                        boolean keepScreenOnEnabled = sharedPreferences.getBoolean(key, true);
                        LogHelper.d(TAG, "🔆 背屏常亮开关状态变化: " + (keepScreenOnEnabled ? "开启" : "关闭"));
                        
                        // 在主线程中处理
                        uiHandler.post(() -> {
                            try {
                                // 检查是否在背屏
                                int displayId = 0;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        displayId = getDisplay().getDisplayId();
                                    } catch (Exception e) {
                                        // 忽略错误
                                    }
                                }
                                
                                // 检查UI是否已创建
                                boolean hasUI = hasLyricsView();
                                
                                if (displayId == 1 && hasUI && !isFinishing() && !isDestroyed()) {
                                    if (keepScreenOnEnabled) {
                                        ProjectionOnlyNotificationHelper.cancelMusic(RearScreenLyricsActivity.this);
                                        RearScreenWakeManager.getInstance().startWakeService(
                                                getApplicationContext(),
                                                RearScreenLyricsActivity.class);
                                        RearScreenWakeService.requestNotificationRefresh(RearScreenLyricsActivity.this);
                                    } else {
                                        RearScreenWakeManager.getInstance().stopWakeService(
                                                getApplicationContext(),
                                                RearScreenLyricsActivity.class);
                                        ProjectionOnlyNotificationHelper.showMusic(RearScreenLyricsActivity.this);
                                    }
                                    setupWindow();
                                }
                            } catch (Exception e) {
                                LogHelper.e(TAG, "❌ 处理背屏常亮开关状态变化失败", e);
                            }
                        });
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
        // 固定黑底，避免切换/退出瞬间闪白
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
        // 结束投屏等路径会临时加 FLAG_NOT_TOUCHABLE；正常显示时必须可触摸、可聚焦
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);

        // 音乐投屏时保持常亮（根据用户设置决定）
        boolean keepScreenOnEnabled = true; // 默认开启
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
        } catch (Exception e) {
            LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
        }
        
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
                            if (!isFinishing() && !isDestroyed() && getWindow() != null) {
                                // 检查背屏常亮开关
                                boolean keepScreenOnEnabled = true; // 默认开启
                                try {
                                    SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                                    keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                                } catch (Exception e) {
                                    // 忽略错误
                                }
                                
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
                            if (!isFinishing() && !isDestroyed() && getWindow() != null) {
                                // 检查背屏常亮开关
                                boolean keepScreenOnEnabled = true; // 默认开启
                                try {
                                    SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                                    keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                                } catch (Exception e) {
                                    // 忽略错误
                                }
                                
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
            // 深渊镜：尽早 setDecorFitsSystemWindows(false)，确保首帧布局即全屏铺满（避免 content 被 insets 内缩）
            if (abyssalMirrorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                LogHelper.d(TAG, "✅ 深渊镜：已尽早设置 setDecorFitsSystemWindows(false)，确保首帧全屏");
            }
            // 使用FrameLayout作为根布局，以便跑马灯可以覆盖在最上层
            android.widget.FrameLayout frameLayout = new android.widget.FrameLayout(this);
            mainFrameLayout = frameLayout;
            frameLayout.setBackgroundColor(0xFF000000); // 纯黑色背景
            applyRenderFrameRateBudget(frameLayout);
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
        songTitleText.setPadding(0, 8, 0, 15);
        
        // 启用滚动显示（marquee）
        songTitleText.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        songTitleText.setMarqueeRepeatLimit(-1); // 无限滚动
        songTitleText.setSingleLine(true);
        songTitleText.setSelected(true); // 必须设置为selected才能滚动
        
        // 为歌曲名称区域添加点击监听器，切换按钮显示/隐藏
        songTitleText.setOnClickListener(v -> {
            toggleButtonsVisibility();
        });
        
        // 长按歌曲名称切换跑马灯显示/隐藏
        songTitleText.setOnLongClickListener(v -> {
            toggleMarqueeLight();
            return true;
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
            setTextIfChanged(hookSourceStatusText, "来源：待机");
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
                LogHelper.d(TAG, "✅ 深渊镜模式：已隐藏歌词名称");
            }
            
            // 深渊镜模式（使用新的ViewGroup版本，多层边框效果）
            // 广播启动时通过构造函数传入固定 101px，在 init() 里最先设置，避免与 onSizeChanged 竞态导致圆角有时对有时错
            try {
                abyssalMirrorLyricsViewGroup = new AbyssalMirrorLyricsViewGroup(this, shouldUseFixedCornerRadius101());
                // 设置是否为主屏横屏模式（用于调整圆角半径）
                abyssalMirrorLyricsViewGroup.setMainScreenLandscapeMode(isMainScreenLandscapeMode);
                // 使用调整后的字体大小（主屏横屏模式已x2）
                abyssalMirrorLyricsViewGroup.setTextSize(currentTextSize);
                abyssalMirrorLyricsViewGroup.setTextColor(0xFFFFFFFF); // 白色
                abyssalMirrorLyricsViewGroup.setFitsSystemWindows(false); // 不避系统栏，真正全屏
                abyssalMirrorLyricsViewGroup.setGyroSensitivityMultiplier(abyssalGyroSensitivity);
                abyssalMirrorLyricsViewGroup.setMovableRangeMultiplier(abyssalMovableRange);
                abyssalMirrorLyricsViewGroup.setLyricsTypeface(LyricsFontHelper.resolveTypeface(this, abyssalLyricsFontId, abyssalLyricsCustomPath));
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
                }
                // 继续执行普通模式的创建
            }
            // 方案1：深渊镜手势上一首/下一首回调和开关（与 prevButton/nextButton 逻辑一致）
            if (abyssalMirrorLyricsViewGroup != null) {
                abyssalMirrorLyricsViewGroup.setOnPrevNextGestureListener(new AbyssalMirrorLyricsViewGroup.OnPrevNextGestureListener() {
                    @Override
                    public void onPrevious() {
                        if (!checkNotificationListenerPermission()) {
                            android.widget.Toast.makeText(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG).show();
                            openNotificationListenerSettings();
                            return;
                        }
                        // 与 prevButton 一致：勿在已有 controller 时重复 setup（酷我直连 KwMediaSession 为异步，重复 setup 会先清空导致此处恒为 null）
                        if (mediaController == null) {
                            setupMediaController();
                            if (mediaController == null) {
                                android.widget.Toast.makeText(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT).show();
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
                            android.widget.Toast.makeText(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG).show();
                            openNotificationListenerSettings();
                            return;
                        }
                        if (mediaController == null) {
                            setupMediaController();
                            if (mediaController == null) {
                                android.widget.Toast.makeText(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT).show();
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
        
        if (!abyssalMirrorEnabled) {
            // 普通模式
            lyricsView = new ModernLyricsView(this);
            applyRenderFrameRateBudget(lyricsView);
            lyricsView.setShowTranslation(true);     // 显示翻译
            lyricsView.setEnableWordByWord(wordByWordEnabled);    // 使用保存的设置
            lyricsView.setShowProgress(false);       // 不显示进度条
            lyricsView.setEnableGesture(gestureControlEnabled);  // 使用保存的手势控制设置
            lyricsView.setTextSize(currentTextSize);  // 使用保存的字体大小（主屏横屏模式已x2）
            lyricsView.setBreathingRhythmMs(breathingRhythmMs);
            lyricsView.setBreathingScaleVariance(breathingScaleVariance);
            lyricsView.setBreathingDisplacementStrength(breathingDisplacementStrength);
            lyricsView.setColorChangeIntervalMs(colorChangeIntervalMs);
            lyricsView.setShuffleLayoutRebuildIntervalMs(shuffleLayoutRebuildIntervalMs);
            
            // 主屏横屏模式：调大行间距（1.5倍，因为主屏幕可用分辨率大）
            if (isMainScreenLandscapeMode) {
                float defaultLineSpacing = 160f; // 默认行间距
                float mainScreenLineSpacing = defaultLineSpacing * 1.5f; // 主屏横屏模式：1.5倍
                lyricsView.setLineSpacing(mainScreenLineSpacing);
                LogHelper.d(TAG, "🖥️ 主屏横屏模式：行间距调整为 " + mainScreenLineSpacing + "px (默认值x1.5)");
            }
            
            lyricsView.setBackgroundTextureSize(backgroundTextureSize);  // 使用保存的底图字体大小倍数（跟随字体大小）
            lyricsView.setShowBackgroundTexture(backgroundTextureEnabled);  // 使用保存的底图显示设置
            
            // 歌词使用随机颜色，不跟随跑马灯
            lyricsView.setColorSyncEnabled(false);  // 禁用颜色联动，让歌词使用自己的随机颜色
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
            
            // 设置歌词行点击监听器（点击行可跳转）
            lyricsView.setOnLyricLineClickListener(new ModernLyricsView.OnLyricLineClickListener() {
                @Override
                public void onLyricLineClick(int lineIndex) {
                    if (!checkNotificationListenerPermission()) {
                        android.widget.Toast.makeText(getApplicationContext(), "需要通知监听权限才能跳转进度", android.widget.Toast.LENGTH_LONG).show();
                        openNotificationListenerSettings();
                        return;
                    }
                    if (mediaController == null) {
                        setupMediaController();
                        if (mediaController == null) {
                            android.widget.Toast.makeText(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT).show();
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
            
            LogHelper.d(TAG, "✅ 已创建普通歌词视图");
        }

        // MV layer: full-screen video + bottom 1-line lyric (only shown when MV url is available)
        mvLayer = new android.widget.FrameLayout(this);
        mvLayer.setBackgroundColor(0xFF000000);
        applyRenderFrameRateBudget(mvLayer);
        setVisibilityIfChanged(mvLayer, View.GONE);

        mvPlayerView = new PlayerView(this);
        mvPlayerView.setUseController(false);
        mvPlayerView.setKeepScreenOn(true);
        android.widget.FrameLayout.LayoutParams pvParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        );
        mvLayer.addView(mvPlayerView, pvParams);

        mvLyricLineView = new TextView(this);
        setTextIfChanged(mvLyricLineView, "");
        mvLyricLineView.setTextColor(0xFFFFFFFF);
        mvLyricLineView.setTextSize(18f);
        mvLyricLineView.setMaxLines(1);
        mvLyricLineView.setEllipsize(TextUtils.TruncateAt.END);
        mvLyricLineView.setPadding(dp(20), dp(10), dp(20), dp(22));
        mvLyricLineView.setBackgroundColor(0x66000000);
        android.widget.FrameLayout.LayoutParams mvLineParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        mvLineParams.gravity = android.view.Gravity.BOTTOM;
        mvLayer.addView(mvLyricLineView, mvLineParams);

        // tap to exit MV back to lyrics view
        mvLayer.setOnClickListener(v -> switchToLyricsMode(false));
        
        // 控制按钮容器：左侧避让由 applyMediaButtonBarInsets() 根据系统 cutout 计算；三键 weight 均分条内宽度
        buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(android.view.Gravity.CENTER);
        buttonLayout.setPadding(dp(22), dp(14), dp(22), dp(14));
        setVisibilityIfChanged(buttonLayout, View.GONE);
        buttonsVisible = false;

        int btnGap = dp(10);

        // 上一首按钮
        prevButton = new Button(this);
        prevButton.setText("⏮");
        android.graphics.drawable.GradientDrawable prevBg = new android.graphics.drawable.GradientDrawable();
        styleMediaTransportButton(prevButton, prevBg);
        LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
        prevParams.setMarginEnd(btnGap / 2);
        prevButton.setLayoutParams(prevParams);
        prevButton.setOnClickListener(v -> {
            if (!checkNotificationListenerPermission()) {
                android.widget.Toast.makeText(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG).show();
                openNotificationListenerSettings();
                return;
            }
            if (mediaController == null) {
                setupMediaController();
                if (mediaController == null) {
                    android.widget.Toast.makeText(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            android.media.session.MediaController.TransportControls controls = mediaController.getTransportControls();
            if (controls != null) {
                try {
                    controls.skipToPrevious();
                } catch (Exception e) {
                    LogHelper.e(TAG, "发送上一首命令失败", e);
                }
            }
        });
        buttonLayout.addView(prevButton);
        
        // 播放/暂停按钮
        playPauseButton = new Button(this);
        playPauseButton.setText("▶");
        android.graphics.drawable.GradientDrawable playBg = new android.graphics.drawable.GradientDrawable();
        styleMediaTransportButton(playPauseButton, playBg);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
        playParams.setMarginStart(btnGap / 2);
        playParams.setMarginEnd(btnGap / 2);
        playPauseButton.setLayoutParams(playParams);
        playPauseButton.setOnClickListener(v -> {
            if (!checkNotificationListenerPermission()) {
                android.widget.Toast.makeText(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG).show();
                openNotificationListenerSettings();
                return;
            }
            if (mediaController == null) {
                setupMediaController();
                if (mediaController == null) {
                    android.widget.Toast.makeText(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT).show();
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
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
        nextParams.setMarginStart(btnGap / 2);
        nextButton.setLayoutParams(nextParams);
        nextButton.setOnClickListener(v -> {
            if (!checkNotificationListenerPermission()) {
                android.widget.Toast.makeText(getApplicationContext(), "需要通知监听权限才能控制音乐", android.widget.Toast.LENGTH_LONG).show();
                openNotificationListenerSettings();
                return;
            }
            if (mediaController == null) {
                setupMediaController();
                if (mediaController == null) {
                    android.widget.Toast.makeText(getApplicationContext(), "无法获取音乐控制器", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            android.media.session.MediaController.TransportControls controls = mediaController.getTransportControls();
            if (controls != null) {
                try {
                    controls.skipToNext();
                } catch (Exception e) {
                    LogHelper.e(TAG, "发送下一首命令失败", e);
                }
            }
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

        // MV layer sits above content, below marquee overlay.
        frameLayout.addView(mvLayer, contentParams);
        
        mediaButtonBarLayoutParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        mediaButtonBarLayoutParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        frameLayout.addView(buttonLayout, mediaButtonBarLayoutParams);
        LogHelper.d(TAG, "✅ 控制按钮条已加入根布局（边距由 applyMediaButtonBarInsets 按系统 cutout 更新）");

        
        // 添加跑马灯视图（放在最上层，但设置不拦截触摸事件）
        // 广播启动时通过构造函数传入固定 101px，避免与 onSizeChanged 竞态导致圆角有时对有时错
        try {
            marqueeLightView = new MarqueeLightView(this, shouldUseFixedCornerRadius101());
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
            marqueeLightView.setClickable(false);
            marqueeLightView.setFocusable(false);
            
            // 建立颜色联动：跑马灯和霓虹灯边框颜色跟随歌词颜色
            if (marqueeLightView != null) {
                marqueeLightView.setColorSyncCallback(() -> {
                    // 返回歌词的当前颜色（用于跑马灯和霓虹灯边框颜色联动）
                    if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
                        // 新的ViewGroup版本：返回歌词颜色
                        return abyssalMirrorLyricsViewGroup.getTextColor();
                    } else if (abyssalMirrorEnabled && abyssalMirrorLyricsView != null) {
                        // 旧版3D旋转版本（兼容）
                        return abyssalMirrorLyricsView.getTextColor();
                    } else if (lyricsView != null) {
                        return lyricsView.getCurrentTextColor();
                    }
                    // 如果视图为null，返回默认颜色（白色）
                    return 0xFFFFFFFF;
                });
                marqueeLightView.setColorSyncEnabled(true);  // 启用颜色联动
                LogHelper.d(TAG, "✅ 跑马灯和霓虹灯边框颜色已设置为跟随歌词颜色");
            }
            
            LogHelper.d(TAG, "✅ 跑马灯视图已添加，跑马灯: " + (marqueeLightEnabled ? "启用" : "禁用") + 
                      ", 线条宽度=" + marqueeLightSize + "px, 霓虹效果=" + neonDisplayEnabled + ", 边框显示=" + neonBorderEnabled +
                      ", 视图可见: " + shouldBeVisible);
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 创建跑马灯视图失败", e);
            // 跑马灯失败不影响主界面显示
        }

        setContentView(frameLayout);
        LogHelper.d(TAG, "✅ UI布局创建完成，已设置ContentView");
        
        // 应用安全区域（避开摄像头）
        applySafeAreaPadding();
        applyMediaButtonBarInsets();
        getWindow().getDecorView().post(this::applyMediaButtonBarInsets);
        
        // 立即设置系统UI可见性（在setContentView之后，避免闪烁）
        // 使用post确保decorView已创建
        // 参考充电动画实现：根据当前所在屏幕决定是否隐藏状态栏（主屏显示，背屏隐藏）
        getWindow().getDecorView().post(() -> {
            hideSystemUI(); // 内部会检查displayId，只在背屏时隐藏
        });
        
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 创建UI布局时发生异常", e);
            // 即使出现异常，也显示一个基本的黑色背景，避免完全黑屏
            try {
                View errorView = new View(this);
                errorView.setBackgroundColor(0xFF000000);
                setContentView(errorView);
                LogHelper.d(TAG, "✅ 已设置错误占位视图");
                // 错误视图也需要设置系统UI可见性
                getWindow().getDecorView().post(() -> {
                    hideSystemUI();
                });
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
     * 背屏常亮总开关开启时注册 {@link RearScreenWakeService}（合并通知）；关闭时仅显示「投屏」通知。
     */
    private void startMusicProjectionWakeService() {
        registerRearAssistProximityPreferenceListener();
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
        boolean keepOn = true;
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            keepOn = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
        } catch (Exception e) {
            LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
        }
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
        updateProjectionProximitySession();
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
     * 仅当「禁用官方背屏服务」开关开启时才屏蔽 {@code com.xiaomi.subscreencenter}（{@link OfficialSubscreenServiceGate}）。
     */
    private void checkAndDisableOfficialGesture() {
        if (!OfficialSubscreenServiceGate.isDisableEnabled(this)) {
            return;
        }
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
        
        if (taskService == null) {
            LogHelper.w(TAG, "⚠️ TaskService未连接，暂无法屏蔽官方手势服务（不影响投屏本身）");
            return;
        }
        
        try {
            LogHelper.d(TAG, "🚫 开始屏蔽官方手势服务（com.xiaomi.subscreencenter）");
            // 使用disableSubScreenLauncher方法（与其他界面保持一致，使用am force-stop命令）
            boolean success = taskService.disableSubScreenLauncher();
            if (success) {
                isOfficialGestureDisabled = true;
                LogHelper.d(TAG, "✅ 已屏蔽官方手势服务（com.xiaomi.subscreencenter）");
                
                // 验证是否成功（延迟检查进程是否还在运行，仅记录日志，不显示Toast）
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
    private void setupMediaController() {
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

    /** 播放状态与「是否应对酷我走 MediaBrowser」不一致时重绑（如暂停、切应用）。 */
    private void maybeRefreshMediaControllerForKuwoPolicy() {
        KuwoCarLyricsPolicy.maybeRefreshIfNeeded(this, kuwoCarLyricsSessionActive, this::setupMediaController);
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
            maybeKuwoExtrasFallbackToApi("extras=null");
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
            enhancedLyricLines = pr.lines;
            cancelPendingNoLyrics();
            superLyricFallbackModeActive = false;
            applyFallbackRenderingModeIfNeeded();
            if (lyricsView != null) {
                setLyricsToView(enhancedLyricLines);
            }
            lastKuwoAudioLyricTrackKey = kuwoTrackKey;
            lastKuwoAudioLyricPayload = json != null ? json : "";
            LogHelper.d(TAG, "✅ 已应用酷我 AUDIO_LYRIC，行数=" + enhancedLyricLines.size());
        });
    }

    /**
     * 酷我 extras 无有效逐行歌词时，使用与 {@link #loadLyrics} 策略 3 相同的第三方 API 兜底（避免仅依赖 onExtrasChanged 时永不触发 API）。
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
                return;
            }
            String title = stableTitle;
            if (title == null || title.isEmpty() || "未知歌曲".contentEquals(title) || isLikelyLyricLine(title)) {
                LogHelper.d(TAG, "酷我 extras 无有效歌词，等待下一次 AUDIO_LYRIC（无有效曲名）: " + reason);
                return;
            }
            String artist = stableArtist != null ? stableArtist : "";
            LogHelper.d(TAG, "酷我歌词未就绪（" + reason + "），仅保留酷我专用解析链: " + title + " - " + artist);
        });
    }

    /**
     * 酷我 extras 等独立场景：自增请求序号后拉取第三方 API。
     */
    private void fetchLyricsFromSuperLyricApi(String title, String artist, int requestSeq, String trackKey) {
        if (title == null || title.isEmpty()) return;
        final String finalTitle = title;
        final String finalArtist = artist != null ? artist : "";
        final String sourcePkg = resolveCurrentLyricsPackageName();
        new Thread(() -> {
            try {
                SuperLyricApi.SuperLyricFallbackPayload payload =
                    SuperLyricApi.fetchFallbackPayload(finalTitle, finalArtist, sourcePkg);
                if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                    return;
                }
                if (payload != null && payload.text != null && !payload.text.trim().isEmpty()) {
                    runOnUiThread(() -> applySuperLyricFallbackPayload(payload, sourcePkg, requestSeq, trackKey));
                    return;
                }
                runOnUiThread(() -> {
                    if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                        return;
                    }
                    updateHookSourceStatusText("来源：SuperLyricApi未命中");
                    scheduleNoLyrics(requestSeq, trackKey);
                });
            } catch (Exception e) {
                LogHelper.w(TAG, "SuperLyricApi 读取失败: " + e.getMessage());
                runOnUiThread(() -> {
                    if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                        return;
                    }
                    updateHookSourceStatusText("来源：SuperLyricApi异常");
                    scheduleNoLyrics(requestSeq, trackKey);
                });
            }
        }).start();
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
        if (!shouldUseNetworkApiSource()) {
            if (shouldUseSuperLyricFallback()) {
                fetchLyricsFromSuperLyricApi(title, artist, requestSeq, trackKey);
            } else {
                scheduleNoLyrics(requestSeq, trackKey);
            }
            return;
        }
        if (trackKey != null && !trackKey.isEmpty() && trackKey.equals(inflightApiTrackKey)) {
            LogHelper.d(TAG, "🛡️ API 防重：同曲目请求进行中，跳过重复调用");
            return;
        }
        inflightApiTrackKey = trackKey != null ? trackKey : "";
        final String finalTitle = title;
        final String finalArtist = artist != null ? artist : "";
        final long finalDurationMs = stableDurationMs;
        final String finalLyrics = "";
        final boolean mixedMode = isMixedLyricsSourceMode();
        final boolean strictTitleArtistMatch = mixedMode;
        final boolean enableSecondaryNetworkFallback = isNetworkOnlyLyricsSourceMode();

        LogHelper.d(TAG, logTag + "，尝试从第三方API获取: " + finalTitle + " - " + finalArtist);

        new Thread(() -> {
            try {
                if (strictTitleArtistMatch && (finalArtist == null || finalArtist.trim().isEmpty())) {
                    LogHelper.w(TAG, "⚠ 混合模式要求歌名+歌手严格匹配，当前歌手为空，直接切 SuperLyric 兜底");
                    runOnUiThread(() -> {
                        if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                            return;
                        }
                        if (shouldUseSuperLyricFallback()) {
                            fetchLyricsFromSuperLyricApi(finalTitle, finalArtist, requestSeq, trackKey);
                        } else {
                            scheduleNoLyrics(requestSeq, trackKey);
                        }
                    });
                    return;
                }
                String apiLyrics = MusicInfoHelper.getLyricsFromAPI(
                    RearScreenLyricsActivity.this,
                    finalTitle,
                    finalArtist,
                    finalDurationMs,
                    null,
                    resolveCurrentLyricsPackageName(),
                    strictTitleArtistMatch,
                    enableSecondaryNetworkFallback
                );

                if (apiLyrics != null && !apiLyrics.isEmpty()) {
                    EnhancedLRCParser.ParseResult parsedResult = parseLyricsContent(apiLyrics);
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
                            if (parsedResult == null || parsedResult.lines == null || parsedResult.lines.isEmpty()) {
                                LogHelper.w(TAG, "⚠ 歌词解析后为空，原始歌词长度: " + apiLyrics.length() + " 字符");
                                if (shouldUseSuperLyricFallback()) {
                                    fetchLyricsFromSuperLyricApi(finalTitle, finalArtist, requestSeq, trackKey);
                                } else {
                                    scheduleNoLyrics(requestSeq, trackKey);
                                }
                                return;
                            }

                            enhancedLyricLines = parsedResult.lines;
                            cancelPendingNoLyrics();
                            // 深渊镜模式不创建 lyricsView，仍需同步到 abyssalMirrorLyricsViewGroup
                            setLyricsToView(enhancedLyricLines);
                            superLyricFallbackModeActive = false;
                            applyFallbackRenderingModeIfNeeded();
                            currentLyricsSource = LyricsSource.NETWORK_API;
                            currentLyricsSourcePkg = curPkg != null ? curPkg : "";
                            updateHookSourceStatusText("来源：网络API");

                            LogHelper.d(TAG, "✅ 从第三方API获取到歌词: " + enhancedLyricLines.size() + " 行");
                        } catch (Exception e) {
                            LogHelper.e(TAG, "解析API歌词时发生异常: " + e.getMessage(), e);
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                            return;
                        }
                        String curPkg = resolveCurrentLyricsPackageName();
                        if (!shouldAcceptApiResultForTrack(curPkg)) {
                            return;
                        }
                        if (finalLyrics == null || finalLyrics.isEmpty()) {
                            if (shouldUseSuperLyricFallback()) {
                                fetchLyricsFromSuperLyricApi(finalTitle, finalArtist, requestSeq, trackKey);
                                LogHelper.d(TAG, "⚠ 网络API未命中，尝试 SuperLyricApi 3.4 兜底");
                            } else {
                                updateHookSourceStatusText("来源：网络API未命中");
                                scheduleNoLyrics(requestSeq, trackKey);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "从第三方API获取歌词失败: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                        return;
                    }
                    String curPkg = resolveCurrentLyricsPackageName();
                    if (!shouldAcceptApiResultForTrack(curPkg)) {
                        return;
                    }
                    if (finalLyrics == null || finalLyrics.isEmpty()) {
                        if (shouldUseSuperLyricFallback()) {
                            fetchLyricsFromSuperLyricApi(finalTitle, finalArtist, requestSeq, trackKey);
                        } else {
                            updateHookSourceStatusText("来源：网络API异常");
                            scheduleNoLyrics(requestSeq, trackKey);
                        }
                    }
                });
            }
        }).start();
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
            return;
        }
        
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

        // 判斷是否「同一首歌」：簽名相同則視為同曲目
        final boolean sameTrack = signature != null && !signature.isEmpty() && signature.equals(stableTrackSignature);

        // 決定要用於 UI 顯示的 title/artist（stable）
        String titleForDisplay = rawTitle;
        String artistForDisplay = rawArtist;

        if (!sameTrack) {
            // 換歌：接受新 title/artist，但若 title 像歌詞行（車載藍牙常見）則拒用，顯示「未知歌曲」
            stableTrackSignature = signature != null ? signature : "";
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
        if (canLoadLyrics && !effectiveSignature.isEmpty() && !effectiveSignature.equals(lastLyricsLoadTrackSignature)) {
            lastLyricsLoadTrackSignature = effectiveSignature;
            loadLyrics(titleForDisplay, artistForDisplay);
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

            if (kuwoCarLyricsSessionActive) {
                // 酷我车载：仅使用直连会话 extras 中的 AUDIO_LYRIC JSON，不使用「系统第一个活跃会话」与通知歌词
                lyrics = "";
                if (mediaController != null) {
                    try {
                        Bundle ex = mediaController.getExtras();
                        String json = ex != null ? ex.getString(KuwoAudioLyricParser.EXTRA_AUDIO_LYRIC) : null;
                        EnhancedLRCParser.ParseResult kuwoPr = KuwoAudioLyricParser.parse(json);
                        if (kuwoPr != null && kuwoPr.lines != null && !kuwoPr.lines.isEmpty()) {
                            enhancedLyricLines = kuwoPr.lines;
                            cancelPendingNoLyrics();
                            superLyricFallbackModeActive = false;
                            applyFallbackRenderingModeIfNeeded();
                            setLyricsToView(enhancedLyricLines);
                            LogHelper.d(TAG, "✅ 酷我 AUDIO_LYRIC 已解析: " + enhancedLyricLines.size() + " 行");
                            return;
                        }
                    } catch (Exception e) {
                        LogHelper.w(TAG, "酷我歌词解析失败: " + e.getMessage());
                    }
                }
                // 酷我 JSON 无效时 lyrics 仍为空，等待下一次 extras 更新，不走酷狗通用 API
                if (lyrics == null || lyrics.isEmpty()) {
                    LogHelper.d(TAG, "酷我 AUDIO_LYRIC 无有效逐行歌词，保持酷我专用解析链");
                }
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
            if ((lyrics == null || lyrics.isEmpty()) && !title.isEmpty() && !kuwoCarLyricsSessionActive) {
                willFetchFromThirdPartyApi = true;
            }
            
            // 解析并显示歌词（如果已经获取到）
            if (lyrics != null && !lyrics.isEmpty()) {
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
                            enhancedLyricLines = result.lines;
                            cancelPendingNoLyrics();
                            setLyricsToView(enhancedLyricLines);
                            LogHelper.d(TAG, "🎤 歌词已解析: " + enhancedLyricLines.size() + " 行");
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
            && !willFetchFromThirdPartyApi) {
            long noLyricsDelayMs = kuwoCarLyricsSessionActive
                ? NO_LYRICS_WAIT_SESSION_EXTRAS_MS
                : NO_LYRICS_UI_DELAY_MS;
            scheduleNoLyrics(requestSeq, trackKey, noLyricsDelayMs);
        }
    }
    
    /**
     * 更新播放状态
     */
    private void updatePlaybackState() {
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
            maybeRefreshMediaControllerForKuwoPolicy();
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
                
                // 获取当前播放位置对应的歌词行
                long currentPosition = 0;
                if (mediaController != null && mediaController.getPlaybackState() != null) {
                    currentPosition = mediaController.getPlaybackState().getPosition();
                }
                
                if (lines != null && !lines.isEmpty()) {
                    int currentIndex = findCurrentLineIndexForAbyssal(lines, currentPosition);
                    if (currentIndex >= 0 && currentIndex < lines.size()) {
                        String currentLyric = lines.get(currentIndex).text;
                        abyssalMirrorLyricsViewGroup.setLyric(currentLyric);
                        broadcastCurrentLyricLine(currentLyric);
                        LogHelper.d(TAG, "✅ 已设置歌词数据到深渊镜视图: " + lineCount + " 行，当前行=" + currentIndex);
                    } else {
                        EnhancedLRCParser.EnhancedLyricLine first = lines.get(0);
                        String t = first != null ? first.text : "";
                        abyssalMirrorLyricsViewGroup.setLyric(
                            t != null ? t : "",
                            false);
                        broadcastCurrentLyricLine(t);
                    }
                } else {
                    String noLyrics = getString(R.string.music_no_lyrics);
                    abyssalMirrorLyricsViewGroup.setLyric(noLyrics);
                    broadcastCurrentLyricLine(noLyrics);
                }
            } else if (abyssalMirrorEnabled && abyssalMirrorLyricsView != null) {
                // 旧版3D旋转版本（兼容）
                int lineCount = lines != null ? lines.size() : 0;
                abyssalMirrorLyricsView.setLyricLines(lines);
                LogHelper.d(TAG, "✅ 已设置歌词数据到深渊镜视图（旧版）: " + lineCount + " 行");
            } else if (lyricsView != null) {
                lyricsView.setLyrics(lines);
                lyricsView.setTrackLoading(false);
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
        return lines.size() + "|" + firstTime + "|" + lastTime + "|" + firstHash + "|" + lastHash;
    }

    private String buildTrackKey(String title, String artist, String packageName, long durationMs) {
        String t = title == null ? "" : title.trim().toLowerCase();
        String a = artist == null ? "" : artist.trim().toLowerCase();
        String p = packageName == null ? "" : packageName.trim().toLowerCase();
        long durationBucket = durationMs > 0 ? (durationMs / 1000L) : 0L;
        return p + "||" + t + "||" + a + "||" + durationBucket;
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

    private void broadcastCurrentLyricLine(String line) {
        try {
            Intent i = new Intent(MvIntents.ACTION_CURRENT_LYRIC_LINE_CHANGED);
            i.putExtra(MvIntents.EXTRA_CURRENT_LYRIC_LINE, line != null ? line : "");
            getApplicationContext().sendBroadcast(i);
        } catch (Exception e) {
            LogHelper.w(TAG, "广播当前歌词失败: " + e.getMessage());
        }
    }

    private void ensureMvPlayer() {
        if (mvPlayer != null) return;
        try {
            mvPlayer = new ExoPlayer.Builder(this).build();
            if (mvPlayerView != null) {
                mvPlayerView.setPlayer(mvPlayer);
            }
        } catch (Throwable t) {
            LogHelper.e(TAG, "ensureMvPlayer failed", t);
        }
    }

    private void releaseMvPlayer() {
        try {
            if (mvPlayerView != null) {
                mvPlayerView.setPlayer(null);
            }
            if (mvPlayer != null) {
                mvPlayer.release();
            }
        } catch (Throwable ignored) {
        } finally {
            mvPlayer = null;
        }
    }

    private void switchToMvMode(String url, boolean fromNewIntent) {
        if (mvLayer == null) return;
        if (url == null || url.trim().isEmpty()) {
            switchToLyricsMode(true);
            return;
        }
        pendingMvUrl = url.trim();
        ensureMvPlayer();
        if (mvPlayer == null) {
            switchToLyricsMode(true);
            return;
        }
        try {
            setVisibilityIfChanged(mvLayer, View.VISIBLE);
            if (contentRootLayout != null) setVisibilityIfChanged(contentRootLayout, View.GONE);
            if (lyricsView != null) setVisibilityIfChanged(lyricsView, View.GONE);
            if (abyssalMirrorLyricsViewGroup != null) setVisibilityIfChanged(abyssalMirrorLyricsViewGroup, View.GONE);
            if (buttonLayout != null) {
                setVisibilityIfChanged(buttonLayout, View.GONE);
            }
            buttonsVisible = false;

            mvPlayer.setMediaItem(MediaItem.fromUri(pendingMvUrl));
            mvPlayer.prepare();
            mvPlayer.play();

            // If it fails quickly (no MV / blocked), fallback.
            mvHandler.removeCallbacks(mvNoUrlFallbackRunnable);
            mvNoUrlFallbackRunnable = () -> {
                if (mvPlayer == null) return;
                if (mvPlayer.getPlaybackState() == Player.STATE_IDLE) {
                    fallbackNoMv();
                }
            };
            mvHandler.postDelayed(mvNoUrlFallbackRunnable, 2500);

            LogHelper.d(TAG, "✅ switchToMvMode ok, fromNewIntent=" + fromNewIntent);
        } catch (Throwable t) {
            LogHelper.e(TAG, "switchToMvMode failed", t);
            fallbackNoMv();
        }
    }

    private void fallbackNoMv() {
        pendingMvUrl = "";
        if (songTitleText != null) {
            setTextIfChanged(songTitleText, "该歌曲暂无MV，已回退歌词");
            songTitleText.post(this::applySongTitleSafeInsets);
        }
        switchToLyricsMode(true);
    }

    private void switchToLyricsMode(boolean stopMv) {
        if (mvLayer != null) setVisibilityIfChanged(mvLayer, View.GONE);
        if (contentRootLayout != null) setVisibilityIfChanged(contentRootLayout, View.VISIBLE);
        if (lyricsView != null) setVisibilityIfChanged(lyricsView, View.VISIBLE);
        if (abyssalMirrorLyricsViewGroup != null) {
            setVisibilityIfChanged(abyssalMirrorLyricsViewGroup, abyssalMirrorEnabled ? View.VISIBLE : View.GONE);
        }
        if (stopMv) {
            mvHandler.removeCallbacks(mvNoUrlFallbackRunnable);
            if (mvPlayer != null) {
                try {
                    mvPlayer.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean isLyricsRequestCurrent(int requestSeq, String trackKey) {
        return requestSeq == lyricsRequestSeq && trackKey != null && trackKey.equals(currentTrackKey) && !isFinishing();
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

    private void applySuperLyricFallbackPayload(SuperLyricApi.SuperLyricFallbackPayload payload,
                                                String sourcePkg,
                                                int requestSeq,
                                                String trackKey) {
        if (!isLyricsRequestCurrent(requestSeq, trackKey) || payload == null) return;
        String text = payload.text != null ? payload.text.trim() : "";
        if (text.isEmpty()) {
            scheduleNoLyrics(requestSeq, trackKey);
            return;
        }
        long currentPlaybackPosition = getCurrentPlaybackPositionSafe();
        long payloadStart = Math.max(0L, payload.lineStartMs);
        // SuperLyric 常返回“当前句内局部时间”而非整首歌绝对时间；若直接喂给歌词视图，
        // 会出现“播放到 80s，但最后一行时间只有 3s”而被 ModernLyricsView 判回第 1 行。
        long anchoredLineTime = currentPlaybackPosition > 0L ? currentPlaybackPosition : payloadStart;
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "🎯 SuperLyric(单句) apply: trackKey=" + (trackKey != null ? trackKey : "")
                + ", seq=" + requestSeq
                + ", pos=" + currentPlaybackPosition + "ms"
                + ", payloadStart=" + payloadStart + "ms"
                + ", anchored=" + anchoredLineTime + "ms"
                + ", text=" + safeShort(text));
        }
        EnhancedLRCParser.EnhancedLyricLine line = reusableSuperLyricLine;
        line.time = anchoredLineTime;
        line.text = text;
        line.translation = null;
        if (payload.hasValidWords()) {
            reusableSuperLyricWords.clear();
            long shift = anchoredLineTime - payloadStart;
            for (EnhancedLRCParser.WordTimestamp word : payload.wordTimestamps) {
                if (word == null) continue;
                long start = Math.max(0L, word.startTime + shift);
                long end = Math.max(start, word.endTime + shift);
                reusableSuperLyricWords.add(new EnhancedLRCParser.WordTimestamp(word.word, start, end));
            }
            line.wordTimestamps = reusableSuperLyricWords;
        } else {
            reusableSuperLyricWords.clear();
            line.wordTimestamps = reusableSuperLyricWords;
        }
        reusableSingleLineLyrics.clear();
        reusableSingleLineLyrics.add(line);
        enhancedLyricLines = reusableSingleLineLyrics;
        cancelPendingNoLyrics();
        superLyricFallbackModeActive = true;
        applyFallbackRenderingModeIfNeeded();
        setLyricsToView(reusableSingleLineLyrics);
        currentLyricsSource = LyricsSource.SUPER_LYRIC_FALLBACK;
        currentLyricsSourcePkg = sourcePkg != null ? sourcePkg : "";
        updateHookSourceStatusText(payload.hasValidWords() ? "来源：SuperLyric兜底(逐字)" : "来源：SuperLyric兜底(静态)");
        LogHelper.d(TAG, "✅ SuperLyricApi 兜底命中: words=" + payload.hasValidWords());
    }

    private void applyFallbackRenderingModeIfNeeded() {
        if (lyricsView == null) return;
        if (superLyricFallbackModeActive) {
            lyricsView.setShowTranslation(false);
            lyricsView.setEnableShuffleSplitEffect(false);
            lyricsView.setShuffleOnlyCurrentLine(true);
            lyricsView.setEnableWordByWord(true);
            lyricsView.setTimeAdjustOffset(0L);
        } else {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            lyricsView.setShowTranslation(true);
            lyricsView.setEnableWordByWord(wordByWordEnabled);
            lyricsView.setEnableShuffleSplitEffect(shuffleSplitEffectEnabled);
            lyricsView.setShuffleSplitMode(shuffleSplitEffectEnabled ? "WORD" : shuffleSplitMode);
            lyricsView.setShuffleOnlyCurrentLine(shuffleSplitEffectEnabled || shuffleSplitOnlyCurrentLine);
            lyricsView.setEnableGesture(gestureControlEnabled);
            lyricsView.setTimeAdjustOffset(projectionSyncOffsetMs);
            lyricsView.setShuffleSplitTiltRatio(prefs.getFloat("shuffleSplitTiltRatio", 5f));
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

    private void scheduleNoLyrics(int requestSeq, String trackKey) {
        scheduleNoLyrics(requestSeq, trackKey, NO_LYRICS_UI_DELAY_MS);
    }

    private void scheduleNoLyrics(int requestSeq, String trackKey, long delayMs) {
        cancelPendingNoLyrics();
        pendingNoLyricsRunnable = () -> {
            if (!isLyricsRequestCurrent(requestSeq, trackKey)) {
                return;
            }
            // 只有在当前仍然没有歌词时才显示“未获取到歌词”
            if (enhancedLyricLines == null || enhancedLyricLines.isEmpty()) {
                setLyricsToView(null);
            }
        };
        // 延迟一点，避免短时间内“通知无歌词/随后API拿到歌词”导致闪烁；等 extras 时用更长 delay
        long d = Math.max(0L, delayMs);
        uiHandler.postDelayed(pendingNoLyricsRunnable, d);
    }
    
    /**
     * 更新歌词位置（同步到当前显示的视图）
     */
    private void updatePositionToView(long position) {
        try {
            long adjustedPosition = position + (long) projectionSyncOffsetMs;
            if (adjustedPosition < 0) {
                adjustedPosition = 0;
            }
            if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
                // 新的ViewGroup版本：根据播放位置更新当前显示的歌词文本
                if (enhancedLyricLines != null && !enhancedLyricLines.isEmpty()) {
                    int currentIndex = findCurrentLineIndexForAbyssal(enhancedLyricLines, adjustedPosition);
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
                    int currentIndex = findCurrentLineIndexForAbyssal(enhancedLyricLines, adjustedPosition);
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
        
        // 从后往前查找，找到第一个时间小于等于位置且位置小于下一行时间的行
        for (int i = lyricLines.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(i);
            
            // 计算这一行的有效结束时间
            long lineEndTime;
            if (i + 1 < lyricLines.size()) {
                lineEndTime = lyricLines.get(i + 1).time;
            } else {
                lineEndTime = line.time + 3000; // 默认3秒
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
        if (a.isFinishing()) {
            return false;
        }
        if (a.isMainScreenLandscapeLyricsActive()) {
            return false;
        }
        return true;
    }
    
    private void loadLyricsFontFieldsFromPrefs(SharedPreferences prefs) {
        projectionLyricsFontId = LyricsFontHelper.normalizeFontId(prefs.getString(KEY_PROJECTION_LYRICS_FONT, null));
        String pPath = prefs.getString(KEY_PROJECTION_LYRICS_CUSTOM_PATH, null);
        if (pPath != null && pPath.isEmpty()) {
            pPath = null;
        }
        projectionLyricsCustomPath = pPath;
        abyssalLyricsFontId = LyricsFontHelper.normalizeFontId(prefs.getString(KEY_ABYSSAL_LYRICS_FONT, null));
        String aPath = prefs.getString(KEY_ABYSSAL_LYRICS_CUSTOM_PATH, null);
        if (aPath != null && aPath.isEmpty()) {
            aPath = null;
        }
        abyssalLyricsCustomPath = aPath;
        if (!LyricsFontHelper.ID_CUSTOM.equals(projectionLyricsFontId)) {
            projectionLyricsCustomPath = null;
        } else if (projectionLyricsCustomPath == null || !(new java.io.File(projectionLyricsCustomPath).isFile())) {
            projectionLyricsFontId = LyricsFontHelper.DEFAULT_ID;
            projectionLyricsCustomPath = null;
        }
        if (!LyricsFontHelper.ID_CUSTOM.equals(abyssalLyricsFontId)) {
            abyssalLyricsCustomPath = null;
        } else if (abyssalLyricsCustomPath == null || !(new java.io.File(abyssalLyricsCustomPath).isFile())) {
            abyssalLyricsFontId = LyricsFontHelper.DEFAULT_ID;
            abyssalLyricsCustomPath = null;
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
        shuffleSplitMode = prefs.getString(KEY_SHUFFLE_SPLIT_MODE, "WORD");
        if (shuffleSplitMode == null) shuffleSplitMode = "WORD";
        shuffleSplitOnlyCurrentLine = prefs.getBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, true);
        marqueeLightEnabled = prefs.getBoolean(KEY_MARQUEE_LIGHT, true);  // 默认开启跑马灯
        neonDisplayEnabled = prefs.getBoolean(KEY_NEON_DISPLAY, prefs.getBoolean(KEY_LYRICS_NEON_GLOW_LEGACY, true));
        neonBorderEnabled = prefs.getBoolean(KEY_NEON_BORDER, true);  // 默认开启霓虹灯边框
        marqueeLightSize = prefs.getFloat(KEY_MARQUEE_LIGHT_SIZE, 18f);  // 默认跑马灯线条宽度18px
        gestureControlEnabled = prefs.getBoolean(KEY_GESTURE_CONTROL, false);  // 默认关闭手势控制
        if (shuffleSplitEffectEnabled) {
            // 分词模式与逐字/滑动互斥，进入页面时直接纠偏，避免旧偏好导致冲突
            wordByWordEnabled = false;
            gestureControlEnabled = false;
            shuffleSplitMode = "WORD";
            shuffleSplitOnlyCurrentLine = true;
        }
        backgroundTextureEnabled = prefs.getBoolean(KEY_BACKGROUND_TEXTURE, false);  // 默认关闭歌词底图显示
        powerSavingModeEnabled = prefs.getBoolean(KEY_POWER_SAVING_MODE, false);
        projectionSyncOffsetMs = prefs.getInt(KEY_PROJECTION_SYNC_OFFSET_MS, DEFAULT_PROJECTION_SYNC_OFFSET_MS);
        lyricsSourceMode = normalizeLyricsSourceMode(
            prefs.getString(KEY_LYRICS_SOURCE_MODE, VALUE_LYRICS_SOURCE_MIXED)
        );
        breathingRhythmMs = prefs.getInt(KEY_BREATHING_RHYTHM_MS, DEFAULT_BREATHING_RHYTHM_MS);
        breathingScaleVariance = prefs.getFloat(KEY_BREATHING_SCALE_VARIANCE, DEFAULT_BREATHING_SCALE_VARIANCE);
        breathingDisplacementStrength = prefs.getFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, DEFAULT_BREATHING_DISPLACEMENT_STRENGTH);
        colorChangeIntervalMs = prefs.getInt(KEY_COLOR_CHANGE_INTERVAL_MS, DEFAULT_COLOR_CHANGE_INTERVAL_MS);
        shuffleLayoutRebuildIntervalMs = prefs.getInt(KEY_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS, DEFAULT_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS);
        abyssalMirrorEnabled = prefs.getBoolean(KEY_ABYSSAL_MIRROR, false);  // 默认关闭深渊镜效果
        abyssalGyroSensitivity = prefs.getFloat(KEY_ABYSSAL_GYRO_SENSITIVITY, DEFAULT_ABYSSAL_GYRO_SENSITIVITY);
        abyssalMovableRange = prefs.getFloat(KEY_ABYSSAL_MOVABLE_RANGE, DEFAULT_ABYSSAL_MOVABLE_RANGE);
        loadLyricsFontFieldsFromPrefs(prefs);
        LogHelper.d(TAG, "📋 加载设置: 字体大小=" + currentTextSize + ", 底图大小=" + backgroundTextureSize + 
                  ", 未唱歌词透明度=" + normalLyricsAlpha + "%, 底图透明度=" + backgroundTextureAlpha + "%" +
                  ", 逐字显示=" + wordByWordEnabled + ", 跑马灯=" + marqueeLightEnabled + 
                  ", 跑马灯线条宽度=" + marqueeLightSize + "px, 霓虹效果=" + neonDisplayEnabled + ", 边框显示=" + neonBorderEnabled + 
                  ", 手势控制=" + gestureControlEnabled +
                  ", 底图显示=" + backgroundTextureEnabled +
                  ", 深渊镜效果=" + abyssalMirrorEnabled);
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
        editor.putString(KEY_SHUFFLE_SPLIT_MODE, shuffleSplitMode);
        editor.putBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, shuffleSplitOnlyCurrentLine);
        editor.putBoolean(KEY_SHUFFLE_SPLIT_PERFORMANCE_GUARD,
                prefs.getBoolean(KEY_SHUFFLE_SPLIT_PERFORMANCE_GUARD, false));
        editor.putBoolean(KEY_MARQUEE_LIGHT, marqueeLightEnabled);
        editor.putBoolean(KEY_NEON_DISPLAY, neonDisplayEnabled);
        editor.putBoolean(KEY_NEON_BORDER, neonBorderEnabled);
        editor.putFloat(KEY_MARQUEE_LIGHT_SIZE, marqueeLightSize);
        editor.putBoolean(KEY_GESTURE_CONTROL, gestureControlEnabled);
        editor.putBoolean(KEY_BACKGROUND_TEXTURE, backgroundTextureEnabled);
        editor.putString(KEY_LYRICS_SOURCE_MODE, normalizeLyricsSourceMode(lyricsSourceMode));
        editor.putInt(KEY_BREATHING_RHYTHM_MS, breathingRhythmMs);
        editor.putFloat(KEY_BREATHING_SCALE_VARIANCE, breathingScaleVariance);
        editor.putFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, breathingDisplacementStrength);
        editor.putInt(KEY_COLOR_CHANGE_INTERVAL_MS, colorChangeIntervalMs);
        editor.putInt(KEY_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS, shuffleLayoutRebuildIntervalMs);
        editor.apply();
        LogHelper.d(TAG, "💾 保存设置: 字体大小=" + currentTextSize + ", 底图大小=" + backgroundTextureSize + 
                  ", 未唱歌词透明度=" + normalLyricsAlpha + "%, 底图透明度=" + backgroundTextureAlpha + "%" +
                  ", 逐字显示=" + wordByWordEnabled + ", 跑马灯=" + marqueeLightEnabled + 
                  ", 跑马灯线条宽度=" + marqueeLightSize + "px, 霓虹灯边框=" + neonBorderEnabled + 
                  ", 手势控制=" + gestureControlEnabled +
                  ", 底图显示=" + backgroundTextureEnabled);
    }
    
    /**
     * 应用设置（从SharedPreferences读取并应用）
     */
    public void applySettings() {
        // 重新加载设置（确保获取最新值）
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String previousLyricsSourceMode = lyricsSourceMode;
        String newSettingsFingerprint = String.valueOf(
                prefs.getAll().hashCode()
        ) + "|" + isMainScreenLandscapeMode + "|" + rearMvModePreferred;
        if (newSettingsFingerprint.equals(lastAppliedSettingsFingerprint)) {
            return;
        }
        lastAppliedSettingsFingerprint = newSettingsFingerprint;
        String modeRaw = prefs.getString(KEY_REAR_DISPLAY_MODE, null);
        if (modeRaw == null || modeRaw.trim().isEmpty()) {
            // 兼容旧键 rearMvEnabled
            boolean legacyMv = prefs.getBoolean("rearMvEnabled", false);
            modeRaw = legacyMv ? VALUE_REAR_MODE_MV : VALUE_REAR_MODE_LYRICS;
        }
        rearMvModePreferred = VALUE_REAR_MODE_MV.equalsIgnoreCase(modeRaw);
        float originalTextSize = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
        float originalBackgroundTextureSize = prefs.getFloat(KEY_BACKGROUND_TEXTURE_SIZE, DEFAULT_BACKGROUND_TEXTURE_SIZE);
        float originalMarqueeLightSize = prefs.getFloat(KEY_MARQUEE_LIGHT_SIZE, 18f);  // 默认跑马灯线条宽度18px
        normalLyricsAlpha = prefs.getInt(KEY_NORMAL_LYRICS_ALPHA, DEFAULT_NORMAL_LYRICS_ALPHA);
        backgroundTextureAlpha = prefs.getInt(KEY_BACKGROUND_TEXTURE_ALPHA, DEFAULT_BACKGROUND_TEXTURE_ALPHA);
        wordByWordEnabled = prefs.getBoolean(KEY_WORD_BY_WORD, false);  // 默认关闭逐字显示
        shuffleSplitEffectEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_EFFECT, false);
        shuffleSplitMode = prefs.getString(KEY_SHUFFLE_SPLIT_MODE, "WORD");
        shuffleSplitOnlyCurrentLine = prefs.getBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, true);
        if (shuffleSplitEffectEnabled) {
            // 分词显示开启时固定为：仅当前一行 + 词库分词（WORD）
            shuffleSplitMode = "WORD";
            shuffleSplitOnlyCurrentLine = true;
            // 分词显示与逐字高亮互斥，强制关闭逐字
            wordByWordEnabled = false;
        }
        marqueeLightEnabled = prefs.getBoolean(KEY_MARQUEE_LIGHT, true);
        neonDisplayEnabled = prefs.getBoolean(KEY_NEON_DISPLAY, prefs.getBoolean(KEY_LYRICS_NEON_GLOW_LEGACY, true));
        neonBorderEnabled = prefs.getBoolean(KEY_NEON_BORDER, true);  // 默认开启霓虹灯边框
        gestureControlEnabled = prefs.getBoolean(KEY_GESTURE_CONTROL, true);
        if (shuffleSplitEffectEnabled) {
            // 分词显示模式与可滑动歌词互斥；偏好里可能残留旧组合，运行时强制关闭手势
            gestureControlEnabled = false;
        }
        backgroundTextureEnabled = prefs.getBoolean(KEY_BACKGROUND_TEXTURE, false);  // 默认关闭歌词底图显示
        projectionSyncOffsetMs = prefs.getInt(KEY_PROJECTION_SYNC_OFFSET_MS, DEFAULT_PROJECTION_SYNC_OFFSET_MS);
        lyricsSourceMode = normalizeLyricsSourceMode(
            prefs.getString(KEY_LYRICS_SOURCE_MODE, VALUE_LYRICS_SOURCE_MIXED)
        );
        final boolean lyricsSourceModeChanged = !TextUtils.equals(previousLyricsSourceMode, lyricsSourceMode);
        breathingRhythmMs = prefs.getInt(KEY_BREATHING_RHYTHM_MS, DEFAULT_BREATHING_RHYTHM_MS);
        breathingScaleVariance = prefs.getFloat(KEY_BREATHING_SCALE_VARIANCE, DEFAULT_BREATHING_SCALE_VARIANCE);
        breathingDisplacementStrength = prefs.getFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, DEFAULT_BREATHING_DISPLACEMENT_STRENGTH);
        colorChangeIntervalMs = prefs.getInt(KEY_COLOR_CHANGE_INTERVAL_MS, DEFAULT_COLOR_CHANGE_INTERVAL_MS);
        shuffleLayoutRebuildIntervalMs = prefs.getInt(KEY_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS, DEFAULT_SHUFFLE_LAYOUT_REBUILD_INTERVAL_MS);
        boolean newAbyssalMirrorEnabled = prefs.getBoolean(KEY_ABYSSAL_MIRROR, false);  // 默认关闭深渊镜效果
        powerSavingModeEnabled = prefs.getBoolean(KEY_POWER_SAVING_MODE, false);
        if (rearMvModePreferred) {
            // MV 模式与深渊镜互斥
            newAbyssalMirrorEnabled = false;
        }
        if (powerSavingModeEnabled) {
            // 省电模式：仅普通歌词；关闭霓虹、跑马灯、手势、深渊镜、逐字、分词、底图（偏好可能为旧组合）
            wordByWordEnabled = false;
            shuffleSplitEffectEnabled = false;
            shuffleSplitMode = "WORD";
            shuffleSplitOnlyCurrentLine = true;
            marqueeLightEnabled = false;
            neonDisplayEnabled = false;
            neonBorderEnabled = false;
            gestureControlEnabled = false;
            backgroundTextureEnabled = false;
            newAbyssalMirrorEnabled = false;
        }
        loadLyricsFontFieldsFromPrefs(prefs);
        
        // 主屏横屏模式：字体大小、跑马灯大小调整
        // 注意：底图字体大小倍数保持设置值不变，会自动跟随字体大小（字体x2，底图也会x2）
        if (isMainScreenLandscapeMode) {
            currentTextSize = originalTextSize * 2f;
            backgroundTextureSize = originalBackgroundTextureSize;  // 保持设置值不变，跟随字体大小
            marqueeLightSize = originalMarqueeLightSize * 2.3f;  // 跑马灯线条宽度 x2.3
            LogHelper.d(TAG, "🖥️ 主屏横屏模式：字体大小调整为 " + currentTextSize + "px (原值x2), " +
                      "底图字体大小倍数保持设置值 " + backgroundTextureSize + " (跟随字体大小), " +
                      "跑马灯线条宽度调整为 " + marqueeLightSize + "px (原值x2.3)");
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
                  ", 深渊镜效果=" + newAbyssalMirrorEnabled +
                  ", 背屏模式=" + (rearMvModePreferred ? "MV" : "LYRICS"));
        
        // 如果深渊镜设置发生变化，需要重新创建视图（这需要重启Activity，暂时只更新当前视图）
        // 注意：切换模式需要重启Activity才能生效
        abyssalMirrorEnabled = newAbyssalMirrorEnabled;
        
        // 更新歌词视图
        if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
            // 新的ViewGroup版本：更新字体大小、颜色、手势开关、陀螺仪倍数与可移动范围
            float gyroSensitivity = prefs.getFloat(KEY_ABYSSAL_GYRO_SENSITIVITY, DEFAULT_ABYSSAL_GYRO_SENSITIVITY);
            float movableRange = prefs.getFloat(KEY_ABYSSAL_MOVABLE_RANGE, DEFAULT_ABYSSAL_MOVABLE_RANGE);
            abyssalMirrorLyricsViewGroup.setTextSize(currentTextSize);
            abyssalMirrorLyricsViewGroup.setTextColor(0xFFFFFFFF); // 白色
            abyssalMirrorLyricsViewGroup.setEnableGesture(gestureControlEnabled);
            abyssalMirrorLyricsViewGroup.setGyroSensitivityMultiplier(gyroSensitivity);
            abyssalMirrorLyricsViewGroup.setMovableRangeMultiplier(movableRange);
            abyssalMirrorLyricsViewGroup.setLyricsTypeface(LyricsFontHelper.resolveTypeface(this, abyssalLyricsFontId, abyssalLyricsCustomPath));
            LogHelper.d(TAG, "✅ 深渊镜歌词视图设置已更新（ViewGroup版本）");
        } else if (abyssalMirrorEnabled && abyssalMirrorLyricsView != null) {
            // 旧版3D旋转版本（兼容）
            abyssalMirrorLyricsView.setTextSize(currentTextSize);
            abyssalMirrorLyricsView.postInvalidateOnAnimation();
            LogHelper.d(TAG, "✅ 深渊镜歌词视图设置已更新（旧版）");
        } else if (lyricsView != null) {
            lyricsView.setEnableWordByWord(wordByWordEnabled);
            lyricsView.setEnableShuffleSplitEffect(shuffleSplitEffectEnabled);
            lyricsView.setShuffleSplitMode(shuffleSplitEffectEnabled ? "WORD" : shuffleSplitMode);
            lyricsView.setShuffleOnlyCurrentLine(shuffleSplitEffectEnabled || shuffleSplitOnlyCurrentLine);
            lyricsView.setShuffleSplitTiltRatio(prefs.getFloat("shuffleSplitTiltRatio", 5f));
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
            lyricsView.setBreathingRhythmMs(powerSavingModeEnabled ? 5000 : breathingRhythmMs);
            lyricsView.setBreathingScaleVariance(powerSavingModeEnabled ? 0f : breathingScaleVariance);
            lyricsView.setBreathingDisplacementStrength(powerSavingModeEnabled ? 0f : breathingDisplacementStrength);
            lyricsView.setColorChangeIntervalMs(colorChangeIntervalMs);
            lyricsView.setShuffleLayoutRebuildIntervalMs(shuffleLayoutRebuildIntervalMs);
            lyricsView.setNeonLyricsEnabled(neonDisplayEnabled);
            lyricsView.setLyricsFont(projectionLyricsFontId, projectionLyricsCustomPath);
            lyricsView.setTimeAdjustOffset(projectionSyncOffsetMs);
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
            LogHelper.d(TAG, "✅ 跑马灯设置已更新: " + (marqueeLightEnabled ? "显示" : "隐藏") + 
                      ", 线条宽度=" + marqueeLightSize + "px, 霓虹效果=" + neonDisplayEnabled + ", 边框显示=" + neonBorderEnabled +
                      ", 视图可见: " + shouldBeVisible);
        }
        
        applySongTitleSafeInsets();

        checkAndDisableOfficialGesture();

        // Apply mode visibility (MV only shows when url is available)
        updateRearDisplayModeUi();

        if (lyricsSourceModeChanged) {
            LogHelper.d(TAG, "🔄 歌词来源模式已切换: " + previousLyricsSourceMode + " -> " + lyricsSourceMode);
            if (shouldUseNetworkApiSource()) {
                stopSuperLyricRealtimeTicker();
            }
            String title = stableTitle != null ? stableTitle : "";
            if (!title.isEmpty() && !isLikelyLyricLine(title)) {
                // 来源策略改变后需要强制重拉当前曲目，避免被同曲防重逻辑拦截导致UI不刷新。
                String artist = stableArtist != null ? stableArtist : "";
                currentTrackKey = "";
                inflightApiTrackKey = "";
                lastLoadLyricsTrackKey = "";
                lastLoadLyricsTime = 0L;
                loadLyrics(title, artist);
            } else {
                LogHelper.d(TAG, "ℹ 来源模式已更新，等待下一次有效曲目信息触发歌词重载");
            }
        }
        
        LogHelper.d(TAG, "✅ 所有设置已应用");
    }

    private void updateRearDisplayModeUi() {
        if (mvLayer == null) return;
        if (rearMvModePreferred && pendingMvUrl != null && !pendingMvUrl.isEmpty()) {
            switchToMvMode(pendingMvUrl, false);
        } else {
            // MV 偏好开启但暂无直链：仍显示歌词（兼容无 MV 歌曲）
            switchToLyricsMode(false);
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
                hideSystemUI();
            }
            
            // 如果窗口获得焦点，检查是否需要初始化UI（从主屏移动到背屏的情况）
            if (!isFinishing()) {
                int displayId = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        displayId = getDisplay().getDisplayId();
                    } catch (Exception e) {
                        LogHelper.e(TAG, "获取displayId失败", e);
                    }
                }

                if (displayId == 1 || (isMainScreenLandscapeMode && displayId == 0)) {
                    cancelMainScreenPlaceholderTimeout();
                }
                if (finishIfIllegalProjectionSurface("onWindowFocusChanged")) {
                    return;
                }
                
                // 主屏横屏模式：确保保持横屏方向
                if (isMainScreenLandscapeMode && displayId == 0) {
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    LogHelper.d(TAG, "🖥️ 主屏横屏模式：窗口获得焦点，确保保持横屏方向");
                    return; // 主屏横屏模式不需要后续的背屏逻辑
                }
                
                // 在背屏时，立即重新应用常亮flags（防止双击息屏后屏幕熄灭）
                if (displayId == 1) {
                    RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
                    // 检查背屏常亮开关
                    boolean keepScreenOnEnabledFocus = true; // 默认开启
                    try {
                        SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                        keepScreenOnEnabledFocus = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
                    }
                    
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
                
                // 如果在背屏但UI未创建，初始化UI
                if (displayId == 1 && lyricsView == null) {
                    LogHelper.d(TAG, "🎯 onWindowFocusChanged: 在背屏但UI未创建，开始初始化UI");
                    
                    // 保存静态实例
                    currentInstance = this;
                    initialDisplayId = displayId;
                    
                    // 保持常亮 + 锁屏显示（根据背屏常亮开关决定）
                    boolean keepScreenOnEnabledFocus = true; // 默认开启
                    try {
                        SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                        keepScreenOnEnabledFocus = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
                    }
                    
                    if (keepScreenOnEnabledFocus) {
                        // 与未投放应用时的实现方式一致
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        );
                        LogHelper.d(TAG, "✅ 背屏常亮已开启（onWindowFocusChanged创建UI），保持屏幕常亮");
                    } else {
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        );
                        // 清除常亮标志（如果之前设置了）
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        LogHelper.d(TAG, "⏸️ 背屏常亮已关闭（onWindowFocusChanged创建UI），不保持屏幕常亮");
                    }
                    
                    // 适配新API：锁屏时显示（与未投放应用时的实现方式一致）
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
                        LogHelper.d(TAG, "✅ 已设置窗口延伸至刘海区域（onWindowFocusChanged）");
                    }
                    
                    // 一次性设置窗口尺寸（确保全屏）
                    WindowManager.LayoutParams params = getWindow().getAttributes();
                    params.width = WindowManager.LayoutParams.MATCH_PARENT;
                    params.height = WindowManager.LayoutParams.MATCH_PARENT;
                    getWindow().setAttributes(params);
                    
                    // 加载设置
                    loadSettings();
                    
                    // 创建UI布局
                    createUI();
                    
                    // 初始化歌词动画器
                    initLyricsAnimator();
                    
                    // 获取MediaController
                    setupMediaController();
                    
                    // 更新UI
                    updateMediaInfo();
                    
                    // 立即同步一次播放状态
                    updatePlaybackState();
                    
                    // 绑定TaskService（用于屏蔽/恢复官方手势服务）
                    // 注意：如果Activity在主屏启动后移动到背屏，onCreate中不会执行bindTaskService，需要在这里绑定
                    bindTaskService();
                    
                    // 启动音乐投屏背屏常亮服务
                    startMusicProjectionWakeService();
                    // 通知 MiRoot 主页更新音乐投屏按钮状态（广播启动时也可点击结束投屏）
                    notifyMainActivityProjectionStarted();
                } else if (displayId == 1 && hasLyricsView()) {
                    // UI已创建，重新应用常亮flags（与未投放应用时的实现方式一致）
                    boolean keepScreenOnEnabledFocus2 = true; // 默认开启
                    try {
                        SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                        keepScreenOnEnabledFocus2 = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
                    }
                    
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

        // 与车控一致：任务已从首次创建 UI 的显示屏迁走则销毁，避免占错主屏
        if (initialDisplayId != -1 && displayId != initialDisplayId) {
            LogHelper.w(TAG, "🛑 onConfigurationChanged检测到displayId变化（从 " + initialDisplayId + " 到 " + displayId + "），立即清理并销毁Activity");
            requestProjectionExitSequence("display-changed-onConfig");
            return;
        }
        
        // 如果是主屏横屏模式，确保保持横屏方向并全屏
        if (isMainScreenLandscapeMode && displayId == 0) {
            LogHelper.d(TAG, "🖥️ 主屏横屏模式：确保保持横屏方向并全屏");
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            // 隐藏系统UI（全屏显示）
            hideSystemUIForMainScreen();
        }
        
        // 如果移动到背屏且UI未创建，创建UI
        if (displayId == 1 && lyricsView == null) {
            LogHelper.d(TAG, "🎯 在onConfigurationChanged中检测到移动到背屏且UI未创建，开始创建UI");
            // 使用Handler延迟创建，确保配置变更完成
            if (deferredCreateUiRunnable != null) {
                uiHandler.removeCallbacks(deferredCreateUiRunnable);
            }
            deferredCreateUiRunnable = () -> {
                // 再次检查displayId和lyricsView
                int currentDisplayId = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        currentDisplayId = getDisplay().getDisplayId();
                    } catch (Exception e) {
                        LogHelper.e(TAG, "再次获取displayId失败", e);
                    }
                }
                if (currentDisplayId == 1 && lyricsView == null) {
                    LogHelper.d(TAG, "✅ 确认在背屏且UI未创建，开始创建UI");
                    initialDisplayId = currentDisplayId;
                    // 保持常亮 + 锁屏显示（根据背屏常亮开关决定）
                    boolean keepScreenOnEnabledConfig = true; // 默认开启
                    try {
                        SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                        keepScreenOnEnabledConfig = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
                    }
                    
                    if (keepScreenOnEnabledConfig) {
                        // 与未投放应用时的实现方式一致
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        );
                        LogHelper.d(TAG, "✅ 背屏常亮已开启（onConfigurationChanged），保持屏幕常亮");
                    } else {
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        );
                        // 清除常亮标志（如果之前设置了）
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        LogHelper.d(TAG, "⏸️ 背屏常亮已关闭（onConfigurationChanged），不保持屏幕常亮");
                    }
                    
                    // 适配新API：锁屏时显示（与未投放应用时的实现方式一致）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        setShowWhenLocked(true);
                        setTurnScreenOn(true);
                    }
                    
                    // 一次性设置所有窗口属性，避免多次调用setAttributes导致闪烁
                    WindowManager.LayoutParams params = getWindow().getAttributes();
                    params.width = WindowManager.LayoutParams.MATCH_PARENT;
                    params.height = WindowManager.LayoutParams.MATCH_PARENT;
                    
                    // 让内容始终延伸到摄像头区域（Display Cutout）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        params.layoutInDisplayCutoutMode = 
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                    }
                    
                    // 一次性设置所有属性
                    getWindow().setAttributes(params);
                    
                    // 注意：系统UI可见性设置在setContentView之后，在createUI中的hideSystemUI()方法中处理
                    // 这样可以避免在窗口还未创建时就设置可见性，减少闪烁
                    
                    // 加载设置
                    loadSettings();
                    
                    // 创建UI布局
                    createUI();
                    
                    // 初始化歌词动画器
                    initLyricsAnimator();
                    
                    // 获取MediaController
                    setupMediaController();
                    
                    // 更新UI
                    updateMediaInfo();
                    
                    // 立即同步一次播放状态
                    updatePlaybackState();
                    
                    // 绑定TaskService（用于屏蔽/恢复官方手势服务）
                    // 注意：如果Activity在主屏启动后移动到背屏，onCreate中不会执行bindTaskService，需要在这里绑定
                    bindTaskService();
                    
                    // 确认在背屏且UI已创建后，才显示通知（界面显示成功后）
                    int configDisplayId = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            configDisplayId = getDisplay().getDisplayId();
                        } catch (Exception e) {
                            LogHelper.e(TAG, "获取displayId失败", e);
                        }
                    }
                    // 严格检查：必须在背屏(displayId==1)且UI已创建(lyricsView!=null)且Activity未销毁
                    if (configDisplayId == 1 && hasLyricsView() && !isFinishing()) {
                        LogHelper.d(TAG, "✅ 投屏成功（onConfigurationChanged），界面显示成功");
                        // 启动音乐投屏背屏常亮服务（确认在背屏且UI已创建）
                        startMusicProjectionWakeService();
                        notifyMainActivityProjectionStarted();
                        // 投屏显示正确后，延迟检查是否需要屏蔽官方手势服务（确保TaskService已连接）
                        if (delayedGestureCheckRunnable != null) {
                            uiHandler.removeCallbacks(delayedGestureCheckRunnable);
                        }
                        delayedGestureCheckRunnable = this::checkAndDisableOfficialGesture;
                        uiHandler.postDelayed(delayedGestureCheckRunnable, 1000);
                    } else {
                        LogHelper.w(TAG, "⚠️ 投屏未完全成功（onConfigurationChanged） (displayId=" + configDisplayId + ", lyricsView=" + (lyricsView != null) + ", isFinishing=" + isFinishing() + ")");
                        // 创建失败，关闭常亮服务
                        stopMusicProjectionWakeService();
                    }
                }
            };
            uiHandler.postDelayed(deferredCreateUiRunnable, 100); // 延迟100ms，确保配置变更完成
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // MV intent: start/switch to MV when preferred
        if (intent != null) {
            String mode = intent.getStringExtra(INTENT_EXTRA_REAR_MODE);
            String url = intent.getStringExtra(INTENT_EXTRA_MV_URL);
            if (VALUE_REAR_MODE_MV.equalsIgnoreCase(mode) && url != null && !url.trim().isEmpty()) {
                pendingMvUrl = url.trim();
                LogHelper.d(TAG, "onNewIntent: mvUrl received, len=" + pendingMvUrl.length());
                if (rearMvModePreferred) {
                    switchToMvMode(pendingMvUrl, true);
                }
            }
        }
        
        // 检查是否是点击通知结束投屏
        if (intent != null && "ACTION_STOP_PROJECTION".equals(intent.getAction())) {
            LogHelper.d(TAG, "🛑 onNewIntent中收到结束投屏请求，立即销毁Activity");
            try {
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                // 立即隐藏窗口，避免显示内容
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                
                requestProjectionExitSequence("onNewIntent-stop-intent");
                // 确保立即返回，不执行后续代码
                return;
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理结束投屏请求时发生异常", e);
                // 即使发生异常，也尝试清理资源并销毁Activity
                try {
                    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                    requestProjectionExitSequence("onNewIntent-stop-intent-error");
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
        cancelRearStopExitGrace();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        isInForeground = true;
        uiAnimationsCancelled = false;
        cancelRearStopExitGrace();
        LogHelper.d(TAG, "🟢 onResume");
        
        // 检查是否是点击通知结束投屏
        if (getIntent() != null && "ACTION_STOP_PROJECTION".equals(getIntent().getAction())) {
            LogHelper.d(TAG, "🛑 onResume中收到结束投屏请求，立即销毁Activity");
            try {
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                // 立即隐藏窗口，避免显示内容
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                
                requestProjectionExitSequence("onResume-stop-intent");
                // 确保立即返回，不执行后续代码
                return;
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理结束投屏请求时发生异常", e);
                // 即使发生异常，也尝试清理资源并销毁Activity
                try {
                    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                    requestProjectionExitSequence("onResume-stop-intent-error");
                } catch (Exception e2) {
                    LogHelper.e(TAG, "❌ 销毁Activity时发生异常", e2);
                }
                return;
            }
        }
        
        // 判断当前所在的屏幕
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        LogHelper.d(TAG, "📍 onResume时displayId=" + displayId);

        // 从其他路径遗留的 NOT_FOCUSABLE/NOT_TOUCHABLE 会在 onResume 清掉，避免背屏「全无手势」
        if (displayId == 1 && getWindow() != null) {
            RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        }

        if (displayId == 1 || (isMainScreenLandscapeMode && displayId == 0)) {
            cancelMainScreenPlaceholderTimeout();
        }

        if (finishIfIllegalProjectionSurface("onResume")) {
            return;
        }

        // 如果是主屏横屏模式，允许在主屏运行，不销毁
        if (isMainScreenLandscapeMode && displayId == 0) {
            LogHelper.d(TAG, "🖥️ 主屏横屏模式：允许在主屏运行，确保保持横屏方向并全屏");
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            hideSystemUIForMainScreen();
            return;
        }

        // 主屏透明占位、等待 am / move-stack 迁往背屏（超时由 onCreate 中 scheduleMainScreenPlaceholderTimeout 处理）
        if (displayId == 0 && lyricsView == null && !isMainScreenLandscapeMode) {
            LogHelper.d(TAG, "⏳ 主屏占位（无歌词 UI），等待迁屏或占位超时(" + MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS + "ms)");
            return;
        }
        
        // 如果在背屏，检查是否需要创建UI（可能是在主屏启动后移动到背屏）
        if (lyricsView == null) {
            LogHelper.d(TAG, "🎯 在背屏但UI未创建(lyricsView=null)，开始创建UI");
            if (initialDisplayId == -1) {
                initialDisplayId = getDisplayIdSafe();
            }
            // 保持常亮 + 锁屏显示（根据背屏常亮开关决定）
            boolean keepScreenOnEnabledResume = true; // 默认开启
            try {
                SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                keepScreenOnEnabledResume = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
            } catch (Exception e) {
                LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
            }
            
            if (keepScreenOnEnabledResume) {
                // 与未投放应用时的实现方式一致
                getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                );
                LogHelper.d(TAG, "✅ 背屏常亮已开启（onResume创建UI），保持屏幕常亮");
            } else {
                getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );
                // 清除常亮标志（如果之前设置了）
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                LogHelper.d(TAG, "⏸️ 背屏常亮已关闭（onResume创建UI），不保持屏幕常亮");
            }
            
            // 适配新API：锁屏时显示（与未投放应用时的实现方式一致）
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
                LogHelper.d(TAG, "✅ 已设置窗口延伸至刘海区域（onResume）");
            }
            
            // 一次性设置窗口尺寸（确保全屏）
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            getWindow().setAttributes(params);
            
            // 注意：系统UI可见性设置在setContentView之后，在createUI中的hideSystemUI()方法中处理
            // 这样可以避免在窗口还未创建时就设置可见性，减少闪烁
            
            // 加载设置
            loadSettings();
            
            // 创建UI布局
            createUI();
            
            // 初始化歌词动画器
            initLyricsAnimator();
            
            // 获取MediaController
            setupMediaController();
            
            // 更新UI
            updateMediaInfo();
            
            // 立即同步一次播放状态
            updatePlaybackState();
            registerActiveSessionsChangedListener();

            // 确认在背屏且UI已创建后，才显示通知（界面显示成功后）
            int currentDisplayId = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    currentDisplayId = getDisplay().getDisplayId();
                } catch (Exception e) {
                    LogHelper.e(TAG, "获取displayId失败", e);
                }
            }
            // 严格检查：必须在背屏(displayId==1)且UI已创建(lyricsView!=null)且Activity未销毁
            if (currentDisplayId == 1 && hasLyricsView() && !isFinishing()) {
                LogHelper.d(TAG, "✅ 投屏成功，界面显示成功");
                // 启动音乐投屏背屏常亮服务（确认在背屏且UI已创建）
                startMusicProjectionWakeService();
                notifyMainActivityProjectionStarted();
                // 投屏显示正确后，延迟检查是否需要屏蔽官方手势服务（确保TaskService已连接）
                if (delayedGestureCheckRunnable != null) {
                    uiHandler.removeCallbacks(delayedGestureCheckRunnable);
                }
                delayedGestureCheckRunnable = this::checkAndDisableOfficialGesture;
                uiHandler.postDelayed(delayedGestureCheckRunnable, 1000);
            } else {
                LogHelper.w(TAG, "⚠️ 投屏未完全成功 (displayId=" + currentDisplayId + ", hasLyricsView=" + hasLyricsView() + ", isFinishing=" + isFinishing() + ")");
                // 创建失败，关闭常亮服务
                stopMusicProjectionWakeService();
            }
        } else {
            // UI已创建，只需更新状态
            // 再次确保Window flags（保持常亮 + 锁屏显示，根据背屏常亮开关决定）
            boolean keepScreenOnEnabledResume2 = true; // 默认开启
            try {
                SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                keepScreenOnEnabledResume2 = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
            } catch (Exception e) {
                LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
            }
            
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

            // 从后台回到前台或切换播放器后，重新解析活跃会话并刷新元数据/歌词
            setupMediaController();
            updateMediaInfo();
            registerActiveSessionsChangedListener();

            // 重新同步播放状态
            updatePlaybackState();

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
                // 投屏显示正确后，延迟检查是否需要屏蔽官方手势服务（确保TaskService已连接）
                if (delayedGestureCheckRunnable != null) {
                    uiHandler.removeCallbacks(delayedGestureCheckRunnable);
                }
                delayedGestureCheckRunnable = this::checkAndDisableOfficialGesture;
                uiHandler.postDelayed(delayedGestureCheckRunnable, 1000);
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
        stopSuperLyricRealtimeTicker();
        
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
                // 不在这里执行完整清理，让onDestroy处理，但可以提前停止一些资源
                try {
                    stopMusicProjectionWakeService();
                } catch (Exception e) {
                    LogHelper.w(TAG, "onPause中停止服务失败", e);
                }
            } else if (displayId == 1) {
                // 在背屏且没有finishing时，继续维持常亮flags（防止系统清除）
                // 与未投屏时常亮实现方式一致：在onPause时重新应用flags，确保屏幕不会熄灭
                try {
                    boolean keepScreenOnEnabledPause = true; // 默认开启
                    try {
                        SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                        keepScreenOnEnabledPause = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
                    }
                    
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

        if (isFinishing()) {
            return;
        }

        int displayId = getDisplayIdSafe();
        // 与车控一致：仅当已从「首次创建 UI 的显示屏」迁走时才结束投屏；背屏双击等导致的短暂 onStop 仍处于同一 display，不 finish
        boolean movedToOtherDisplay = (initialDisplayId != -1 && displayId != initialDisplayId);
        if (movedToOtherDisplay) {
            LogHelper.w(TAG, "⚠️ 界面已从初始显示屏移动(displayId=" + displayId + ", initialDisplayId=" + initialDisplayId + ")，结束音乐投屏并销毁Activity");
            requestProjectionExitSequence("moved-to-other-display-onStop");
            return;
        }

        // 仍在初始显示屏（或尚未记录 initialDisplayId 的占位阶段）：在背屏时继续维持常亮 flags（防止系统清除）
        try {
            if (!isFinishing() && displayId == 1) {
                // 在背屏且没有finishing时，继续维持常亮flags（与未投屏时常亮实现方式一致）
                try {
                    boolean keepScreenOnEnabledStop = true; // 默认开启
                    try {
                        SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                        keepScreenOnEnabledStop = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "读取背屏常亮设置失败，使用默认值", e);
                    }
                    
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

        if (!isFinishing() && !isChangingConfigurations() && !isMainScreenLandscapeMode
                && hasLyricsView() && displayId == 1) {
            scheduleRearStopExitGraceIfNeeded("onStop");
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
                boolean keepScreenOnEnabled = true; // 默认开启
                try {
                    SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                    keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                } catch (Exception e) {
                    // 忽略错误
                }
                
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
        tryTrackBottomSwipeExit(ev);
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 背屏音乐投屏：在屏底窄条内向左或向右滑动结束投屏（清理与返回键/通知结束等一致）。
     * 滑动过程中达到阈值即触发，无需等手指抬起（与车控投屏一致）。
     */
    private void tryTrackBottomSwipeExit(MotionEvent ev) {
        if (getDisplayIdSafe() != 1 || isFinishing() || bottomSwipeExitPending) {
            return;
        }
        View decor = getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        float[] xy = new float[2];
        if (!BottomSwipeExitHelper.decorLocalXY(decor, ev, xy)) {
            return;
        }
        int action = ev.getActionMasked();
        float h = decor.getHeight();
        float y = xy[1];
        float x = xy[0];

        if (action == MotionEvent.ACTION_DOWN && ev.getPointerCount() == 1) {
            bottomSwipeExitPointerDownInZone = h > 0 && y > h * (1f - BOTTOM_SWIPE_EXIT_ZONE_FRACTION);
            bottomSwipeExitStartY = y;
            bottomSwipeExitStartX = x;
            return;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            bottomSwipeExitPointerDownInZone = false;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (bottomSwipeExitPointerDownInZone && ev.getPointerCount() == 1) {
                maybeFireBottomSwipeExit(xy[1], xy[0]);
            }
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (bottomSwipeExitPointerDownInZone && ev.getPointerCount() == 1) {
                maybeFireBottomSwipeExit(xy[1], xy[0]);
            }
            bottomSwipeExitPointerDownInZone = false;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            bottomSwipeExitPointerDownInZone = false;
        }
    }

    private void maybeFireBottomSwipeExit(float endY, float endX) {
        float horizDist = Math.abs(endX - bottomSwipeExitStartX);
        float vertDist = Math.abs(endY - bottomSwipeExitStartY);
        float touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        if (vertDist < touchSlop * 2.5f) {
            vertDist = 0f;
        }
        float minHoriz = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                BOTTOM_SWIPE_EXIT_MIN_HORIZ_DP,
                getResources().getDisplayMetrics());
        if (horizDist < minHoriz || horizDist < vertDist * BOTTOM_SWIPE_EXIT_HORIZONTAL_DOMINANCE) {
            return;
        }
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "👆 底部左右滑结束音乐投屏 (horizDist=" + horizDist + "px)");
        }
        bottomSwipeExitPointerDownInZone = false;
        bottomSwipeExitPending = true;
        Runnable exit = () -> {
            if (isFinishing()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
                return;
            }
            requestProjectionExitSequence("bottom-swipe-exit");
        };
        uiHandler.post(exit);
    }

    private void requestProjectionExitSequence(String reason) {
        Runnable starter = () -> {
            if (projectionExitFlowStarted) {
                return;
            }
            projectionExitFlowStarted = true;
            projectionExitUiLocked = true;
            cancelUiAnimationsIdempotent();
            if (BuildConfig.DEBUG) {
                LogHelper.d(TAG, "🚪 开始退出投屏流程: " + reason);
            }
            Runnable completeExit = () -> {
                if (projectionCleanupDone) {
                    return;
                }
                projectionCleanupDone = true;
                try {
                    if (getWindow() != null) {
                        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                    }
                } catch (Exception ignored) {
                }
                try {
                    performCleanupAndExit();
                } catch (Exception e) {
                    LogHelper.e(TAG, "退出流程清理失败", e);
                }
                try {
                    restoreOfficialLauncherInBackground();
                } catch (Exception e) {
                    LogHelper.w(TAG, "退出流程恢复官方背屏服务失败", e);
                }
                finishProjectionTask();
            };
            View decor = (getWindow() != null) ? getWindow().getDecorView() : null;
            if (decor != null) {
                decor.animate()
                        .cancel();
                decor.animate()
                        .alpha(0f)
                        .setDuration(EXIT_FADE_DURATION_MS)
                        .withEndAction(completeExit)
                        .start();
            } else {
                uiHandler.postDelayed(completeExit, EXIT_FADE_DURATION_MS);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            starter.run();
        } else {
            uiHandler.post(starter);
        }
    }
    
    @Override
    public void finish() {
        super.finish();
        // 禁用转场动画（去掉背屏切换界面的动画）
        overridePendingTransition(0, 0);
    }

    /**
     * 结束投屏并移除独立 task（Manifest：singleInstance + 独立 taskAffinity），避免栈与队列 Runnable 残留。
     */
    private void finishProjectionTask() {
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
                requestProjectionExitSequence("back-pressed");
            }
        });
    }
    
    /**
     * 执行清理并退出（统一处理所有退出场景，参考车控投屏的performCleanupAndExit）
     * 确保无论通过什么方式退出，都能正确清理资源
     */
    private void performCleanupAndExit() {
        try {
            LogHelper.d(TAG, "🧹 开始执行清理并退出（音乐投屏）");

            cancelMainScreenPlaceholderTimeout();
            cancelRearStopExitGrace();
            mainScreenPolicyHandler.removeCallbacksAndMessages(null);
            uiHandler.removeCallbacksAndMessages(null);
            pendingNoLyricsRunnable = null;

            unregisterActiveSessionsChangedListener();

            LyricsTaskTracking.clearLastTask();
            
            // 首先清除静态实例，避免被其他逻辑重新使用
            if (currentInstance == this) {
                currentInstance = null;
                LogHelper.d(TAG, "✅ 已清除静态实例");
            } else if (currentInstance != null) {
                // 如果静态实例不是当前实例，也清除（可能是旧的实例）
                LogHelper.w(TAG, "⚠️ 检测到静态实例不是当前实例，强制清除");
                currentInstance = null;
            }
            
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

            // 清理窗口资源（清除窗口标志和属性，恢复系统UI可见性）
            try {
                // false：退出/销毁前勿 setDecorFitsSystemWindows(true)，透明主题会因 insets 变化露出副屏底层，表现为闪白页
                cleanupWindow(false);
            } catch (Exception e) {
                LogHelper.w(TAG, "清理窗口资源失败", e);
            }
            
            // 恢复官方手势服务（如果已屏蔽）。Launcher 仍在 onDestroy 中延迟恢复。
            try {
                enableOfficialGesture();
            } catch (Exception e) {
                LogHelper.w(TAG, "恢复官方手势服务失败", e);
            }
            
            // 注意：恢复官方Launcher现在在onDestroy中延迟执行，确保Activity完全销毁后再恢复，避免显示切换动画
            
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
            try {
                SuperLyricApi.releaseReceiver();
            } catch (Exception e) {
                LogHelper.w(TAG, "释放 SuperLyric 接收器失败", e);
            }
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
            try {
                mvHandler.removeCallbacks(mvNoUrlFallbackRunnable);
            } catch (Exception ignored) {}
            releaseMvPlayer();
            mvPlayerView = null;
            mvLyricLineView = null;
            mvLayer = null;

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
            
            LogHelper.d(TAG, "✅ 清理并退出完成（音乐投屏）");
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
            lyricsParseExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        super.onDestroy();
        LogHelper.d(TAG, "🔴 onDestroy开始");

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
        
        // 使用统一的清理方法，确保所有退出场景都能正确清理
        // 这样可以处理双击息屏等系统强制销毁的情况，即使没有收到停止Intent也能正确清理
        performCleanupAndExit();
        
        // 再次尝试通知MainActivity（确保状态同步，即使performCleanupAndExit中已通知）
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
            new Thread(() -> {
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
            }).start();
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
                    LogHelper.d(TAG, "🔄 缓存未初始化，尝试初始化以获取cutout信息");
                    DisplayInfoCache.getInstance().initialize(taskService);
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 初始化缓存失败", e);
                }
            }
            
            // 从缓存获取背屏信息
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            
            // 如果没有cutout，不需要额外处理
            if (info == null || !info.hasCutout()) {
                LogHelper.d(TAG, "ℹ️ 背屏无Cutout，无需调整布局");
                applyMediaButtonBarInsets();
                return;
            }
            
            // 获取rootLayout（内容布局）
            android.view.View rootView = getWindow().getDecorView();
            if (rootView == null) {
                LogHelper.w(TAG, "⚠️ DecorView为空，无法应用安全区域");
                applyMediaButtonBarInsets();
                return;
            }
            
            // 查找FrameLayout（根布局）
            android.widget.FrameLayout frameLayout = null;
            if (rootView instanceof android.widget.FrameLayout) {
                frameLayout = (android.widget.FrameLayout) rootView;
            } else {
                // 尝试查找FrameLayout
                frameLayout = rootView.findViewById(android.R.id.content);
                if (frameLayout != null && frameLayout.getChildCount() > 0) {
                    android.view.View firstChild = frameLayout.getChildAt(0);
                    if (firstChild instanceof android.widget.FrameLayout) {
                        frameLayout = (android.widget.FrameLayout) firstChild;
                    }
                }
            }
            
            if (frameLayout == null) {
                LogHelper.w(TAG, "⚠️ 未找到FrameLayout，无法应用安全区域");
                applyMediaButtonBarInsets();
                return;
            }
            
            // 查找rootLayout（LinearLayout，内容布局）
            android.view.View rootLayout = null;
            for (int i = 0; i < frameLayout.getChildCount(); i++) {
                android.view.View child = frameLayout.getChildAt(i);
                if (child instanceof LinearLayout && child.getBackground() != null) {
                    // 找到黑色背景的LinearLayout
                    android.graphics.drawable.ColorDrawable bg = null;
                    try {
                        bg = (android.graphics.drawable.ColorDrawable) child.getBackground();
                    } catch (Exception e) {
                        // 忽略类型转换错误
                    }
                    if (bg != null && bg.getColor() == 0xFF000000) {
                        rootLayout = child;
                        break;
                    }
                }
            }
            
            if (rootLayout == null) {
                LogHelper.w(TAG, "⚠️ 未找到rootLayout，无法应用安全区域");
                applyMediaButtonBarInsets();
                return;
            }
            
            // 检查当前padding是否已经正确（避免重复设置导致的闪烁）
            if (rootLayout.getPaddingLeft() == info.cutout.left && 
                rootLayout.getPaddingTop() == info.cutout.top && 
                rootLayout.getPaddingRight() == info.cutout.right && 
                rootLayout.getPaddingBottom() == info.cutout.bottom) {
                LogHelper.d(TAG, "ℹ️ 安全区域padding已正确设置，跳过更新");
                applyMediaButtonBarInsets();
                return;
            }
            
            // 设置padding（避开cutout区域），背景会填充cutout区域
            rootLayout.setPadding(
                info.cutout.left,
                info.cutout.top,
                info.cutout.right,
                info.cutout.bottom
            );
            
            LogHelper.d(TAG, String.format("✅ 已应用安全区域padding（歌词内容避开摄像头）: left=%d, top=%d, right=%d, bottom=%d",
                info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom));
            applyMediaButtonBarInsets();
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 应用安全区域失败", e);
        }
    }
}



