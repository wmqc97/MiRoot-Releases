/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.wmqc.miroot.car;

import com.wmqc.miroot.display.MainDisplayUi;
import com.wmqc.miroot.R;
import com.wmqc.miroot.MainActivity;
import com.wmqc.miroot.BottomSwipeExitHelper;
import com.wmqc.miroot.RearDisplayInputHelper;
import com.wmqc.miroot.charging.ChargingService;
import com.wmqc.miroot.charging.RearChargingAnimationCoordinator;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.RootTaskService;
import com.wmqc.miroot.lyrics.RearScreenWakeManager;
import com.wmqc.miroot.lyrics.RearScreenWakeService;
import com.wmqc.miroot.rear.OfficialSubscreenMiRootProjectionSession;
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate;
import com.wmqc.miroot.rear.ProjectionOngoingNotifications;
import com.wmqc.miroot.rear.ProjectionOnlyNotificationHelper;
import com.wmqc.miroot.rear.RearAssistPrefs;
import com.wmqc.miroot.lyrics.RootTaskServiceConnector;
import com.wmqc.miroot.rear.RearMirootProjectionLifecycle;
import com.wmqc.miroot.rear.RearProjectionProximitySession;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.lifecycle.Lifecycle;

import android.graphics.drawable.Drawable;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 背屏汽车控制Activity
 * 显示车辆信息和控制按钮，仅在背屏显示
 */
public class RearScreenCarControlActivity extends androidx.fragment.app.FragmentActivity {
    private static final String TAG = "RearScreenCarControl";

    // 静态实例，用于外部调用
    private static RearScreenCarControlActivity currentInstance;
    
    /**
     * 获取当前Activity实例
     */
    public static RearScreenCarControlActivity getCurrentInstance() {
        return currentInstance;
    }

    /**
     * 通知栏结束投屏：广播触发，不拉起 Activity，仅在背屏实例上清理，避免主屏闪一下。
     */
    public static void finishProjectionFromNotificationTap(Context appContext) {
        RearScreenCarControlActivity a = getCurrentInstance();
        if (a == null) {
            bestEffortCleanupCarProjectionFromNotification(appContext);
            return;
        }
        Runnable r = () -> finishProjectionFromNotificationTapOnUiThread(a);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    private static void finishProjectionFromNotificationTapOnUiThread(RearScreenCarControlActivity a) {
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
        LogHelper.d(TAG, "🛑 通知广播：在背屏结束车控投屏");
        ProjectionOngoingNotifications.cancelAll(a.getApplicationContext());
        try {
            a.finishProjectionFromUser("notification");
        } catch (Exception e) {
            LogHelper.e(TAG, "通知结束车控投屏失败", e);
            try {
                currentInstance = null;
                if (!a.isFinishing()) {
                    a.finishProjectionTask();
                }
            } catch (Exception e2) {
                LogHelper.e(TAG, "强制 finish 失败", e2);
            }
        }
    }

    private static void bestEffortCleanupCarProjectionFromNotification(Context appContext) {
        try {
            ProjectionOngoingNotifications.cancelAll(appContext);
            ProjectionOnlyNotificationHelper.cancelCar(appContext);
            RearScreenWakeManager.getInstance().stopWakeService(appContext, RearScreenCarControlActivity.class);
            if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                appContext.stopService(new Intent(appContext, RearScreenWakeService.class));
            }
            LogHelper.d(TAG, "无 Activity 实例：已从通知路径 best-effort 清理车控投屏状态");
        } catch (Exception e) {
            LogHelper.e(TAG, "bestEffortCleanupCarProjectionFromNotification failed", e);
        }
    }

    // UI组件
    private RelativeLayout mainLayout;
    private RelativeLayout contentContainer; // 内容容器（用于应用安全区域）
    private LinearLayout leftInfoLayout;
    private ImageView carModelImage;
    /** 全屏底图（日夜间切换时更新） */
    private ImageView backgroundImageView;
    
    // 车辆信息
    private TextView rangeText;           // 续航里程（大号）
    private TextView rangeLabel;          // 「续航里程」小字
    private TextView fuelPercentText;     // 油量百分比
    private android.widget.ProgressBar fuelProgressBar; // 油量进度条
    private ImageView fuelIcon;           // 油量图标
    private TextView odometerText;        // 总里程
    private TextView interiorTempText;   // 车内温度
    private TextView exteriorTempText;    // 车外温度
    private ImageView refreshIcon;        // 刷新图标
    private TextView updateTimeText;      // 更新时间文本
    
    // 歌词系统（音乐投屏功能）
    private MediaController mediaController;
    private MediaController.Callback mediaControllerCallback;
    private List<EnhancedLRCParser.EnhancedLyricLine> enhancedLyricLines = new ArrayList<>();
    private Handler positionSyncHandler;
    private Runnable positionSyncRunnable;
    
    // 车模图片相关（已移除动态调整逻辑，使用固定大小）
    
    // 控制按钮（动态创建，最多8个）
    public Button[] controlButtons = new Button[8];
    private String[] buttonFunctions = new String[8];
    private CarButtonStateManager carButtonStateManager;
    
    // ViewPager相关
    private androidx.viewpager2.widget.ViewPager2 buttonPageContainer;
    /** 顶栏左侧车辆信息列（安全区变化时需与布局基线同步重算 screen 锚点边距） */
    private LinearLayout carInfoTopLayout;
    
    /** 用于深浅色切换后恢复油量红/橙/蓝，-1 表示尚未拉取过车辆信息 */
    private int cachedFuelPercentForUi = -1;

    // 后备箱状态（用于判断背景色）
    private boolean isTrunkOpen = false;
    // 车窗状态（用于判断开窗/关窗按钮）
    private boolean isWindowOpen = false;
    // 透气模式：车窗位置为一条缝（区别于全开），用于透气按钮背景色
    private boolean isVentMode = false;

    /** 车控圆形按钮底色 / 图标色（随 {@link #syncCarControlThemeColors()} 与系统深浅色变化） */
    private int carBtnBgPrimary = 0xFFB3E5FC;
    private int carBtnBgSecondary = 0xFFE8E8E8;
    private int carBtnIconPrimaryIdle = 0xFF0D47A1;
    private int carBtnIconPrimaryActive = 0xFF00838F;
    private int carBtnIconSecondary = 0xFF000000;
    /** 左栏主文案、次要文案、刷新时间、油量强调蓝 */
    private int colorCarTextPrimary = 0xFFFFFFFF;
    private int colorCarTextMuted = 0xFFE0E0E0;
    private int colorCarUpdateTime = 0xFFFFFFFF;
    private int colorCarFuelBlue = 0xFF2196F3;

    /**
     * dumpsys 未上报背屏 Cutout 时，左侧摄像头占位（px）。
     * 仅由 {@link #contentContainer} 外边距承担，子 View 使用小 padding，避免与 cutout 叠加把内容挤到右侧。
     */
    private static final int REAR_DISPLAY_CAMERA_FALLBACK_LEFT_PX = 200;

    /**
     * 车控界面坐标均指「整屏」左缘（含摄像头区），子 View 在 {@code contentContainer} 内需减去容器已应用的 leftMargin。
     */
    private static final int CAR_UI_SCREEN_LEFT_INFO_PX = 300;
    private static final int CAR_UI_SCREEN_LEFT_MODEL_PX = 540;
    private static final int CAR_MODEL_VIEW_WIDTH_PX = 405;
    private static final int CAR_MODEL_VIEW_HEIGHT_PX = 226;
    /** 底栏距 contentContainer 底边；相对基线 20px 再上移 15px */
    private static final int CAR_UI_BUTTON_BAR_BOTTOM_MARGIN_PX = 35;
    /** 深色模式车控投屏底（灰底，非纯黑） */
    private static final int CAR_PROJECTION_NIGHT_BACKGROUND = 0xFF1E1E1E;

    // 车辆信息刷新防重复标志
    private boolean isRefreshingCarInfo = false;
    /** 刷新图标旋转（无波纹，用旋转表示加载中） */
    private ObjectAnimator refreshIconRotationAnimator;
    
    // Handler和Runnable（用于避免内存泄漏）
    private Handler mainHandler;
    private Runnable retryDisableGestureRunnable; // 重试屏蔽手势服务
    private Runnable retryEnableGestureRunnable; // 重试恢复手势服务
    private Runnable delayedResumeCheckRunnable; // onResume延迟检查
    
    // TaskService（通过 RootTaskService 绑定）
    private ITaskService taskService;
    
    // 官方手势服务屏蔽状态
    private boolean isOfficialGestureDisabled = false;
    
    // 清理标志，防止重复清理导致二次动画
    private boolean isCleaningUp = false;

    // 双击手势检测器
    private GestureDetector gestureDetector;

    /** 屏底窄条内左右滑结束投屏：统一手势处理器 */
    private final BottomSwipeExitHelper.Handler bottomSwipeHandler =
        new BottomSwipeExitHelper.Handler(this, () -> finishProjectionFromUser("bottom-swipe"));

    // 屏幕关闭监听（防止双击息屏）
    private android.content.BroadcastReceiver screenReceiver;
    
    // 背屏常亮开关状态变化监听
    private SharedPreferences.OnSharedPreferenceChangeListener keepScreenOnPreferenceListener;

    private RearProjectionProximitySession projectionProximitySession;
    private SharedPreferences.OnSharedPreferenceChangeListener rearAssistProximityPrefsListener;

    // 记录首次成功创建UI时所在的displayId，用于检测后续是否被系统移动到其他屏幕
    private int initialDisplayId = -1;

    /**
     * 与 {@link com.wmqc.miroot.lyrics.RearScreenLyricsActivity} 一致：人仍在背屏 display 1 但界面已 onStop
     *（例如回到背屏桌面）时 displayId 不变；宽限内未恢复 RESUMED 则销毁，避免投屏空占栈。
     */
    private static final long REAR_STOP_EXIT_GRACE_MS = 800L;
    private Runnable rearStopExitGraceRunnable;
    private long rearStopExitGraceDeadlineElapsedMs = 0L;
    private long rearStopExitGraceRemainingMs = 0L;
    private boolean rearStopExitGracePausedForScreenOff;

    /** 主屏透明占位等待迁往背屏的最长时间（与歌词投屏一致） */
    private static final long MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS = 3500L;
    private Runnable mainScreenPlaceholderTimeoutRunnable;

    /** 迁屏后轮询背屏 displayId，尽快初始化 UI 并恢复触摸/系统返回（替代固定 2500ms 盲等）。 */
    private static final long REAR_UI_INIT_POLL_INTERVAL_MS = 40L;
    private static final int REAR_UI_INIT_POLL_MAX_ATTEMPTS = 45;
    private Runnable rearUiInitPollRunnable;
    private int rearUiInitPollAttempts;

    /** 与既有行为一致：返回键退出投屏（仅注册一次） */
    private boolean carBackPressedCallbackRegistered;

    /** 已走统一退出序列，避免重复清理。 */
    private boolean projectionExitFlowStarted;
    /** 对齐充电动画：MiRoot 主动结束（返回/滑退/通知），onDestroy 后再 restore 官方背屏。 */
    private boolean finishRequestedByMiRoot;
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            LogHelper.d(TAG, "✓ TaskService已连接");
            checkAndDisableOfficialGesture();
            RearMirootProjectionLifecycle.reinforceOfficialSubscreenDisabled(getApplicationContext());
            
            // TaskService连接后，初始化缓存并应用安全区域（如果尚未初始化）
            if (!DisplayInfoCache.getInstance().isInitialized()) {
                try {
                    DisplayInfoCache.getInstance().initialize(taskService);
                    LogHelper.d(TAG, "✅ 已初始化DisplayInfoCache");
                    // 如果contentContainer已创建，应用安全区域
                    if (contentContainer != null && mainHandler != null) {
                        mainHandler.post(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                applySafeAreaPadding();
                            }
                        });
                    }
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 初始化DisplayInfoCache失败", e);
                }
            }
            
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 禁用转场动画（去掉背屏切换界面的动画）
        overridePendingTransition(0, 0);
        LogHelper.d(TAG, "🔵 onCreate 开始");
        
        // 初始化Handler（复用实例，避免内存泄漏）
        mainHandler = new Handler(Looper.getMainLooper());
        registerCarControlBackPressedCallback();
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        RootTaskServiceConnector.prewarm(this);

        // 立即设置窗口背景为透明（在setContentView之前，避免闪烁）
        // 这样可以确保窗口从开始就是透明的，不会显示默认背景
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        
        // 处理停止Intent
        if (getIntent() != null && "ACTION_STOP_CAR_CONTROL".equals(getIntent().getAction())) {
            LogHelper.d(TAG, "🛑 onCreate中收到停止Intent，立即销毁Activity");
            try {
                // 先设置窗口为透明，避免销毁时闪烁
                getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                // 立即隐藏窗口，避免显示内容
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                
                // 使用统一的清理方法
                performCleanupAndExit();
                
                // 立即销毁Activity，不使用延迟
                finishProjectionTask();
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理停止Intent时发生异常", e);
                // 即使发生异常，也尝试清理资源并销毁Activity
                try {
                    // 确保静态实例被清除
                    currentInstance = null;
                    // 强制清理
                    if (!isFinishing()) {
                        performCleanupAndExit();
                        finishProjectionTask();
                    }
                } catch (Exception e2) {
                    LogHelper.e(TAG, "❌ 清理资源失败", e2);
                    // 最后的保障：强制清除静态实例
                    currentInstance = null;
                    try {
                        finishProjectionTask();
                    } catch (Exception e3) {
                        LogHelper.e(TAG, "❌ 强制销毁Activity失败", e3);
                    }
                }
            }
            return;
        }
        
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
        
        // ✅ 如果在主屏(displayId == 0)，什么都不做，等待被移动到背屏（参考充电动画的实现）
        if (displayId == 0) {
            LogHelper.d(TAG, "💤 在主屏启动，保持透明占位符，等待移动");
            currentInstance = this;
            RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(
                    this, RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_TRANSPARENT_PLACEHOLDER);
            scheduleMainScreenPlaceholderTimeout();
            schedulePollRearUiInitAfterMove();
            return;
        }
        
        // 保存静态实例（只有在背屏时才保存）
        currentInstance = this;
        // 记录首次成功创建UI时所在的displayId，后续如果被系统移动到其他屏幕就主动销毁
        initialDisplayId = displayId;
        
        // --- 以下代码只在背屏(displayId == 1)执行 ---
        LogHelper.d(TAG, "🎯 在背屏执行，开始设置汽车控制界面");
        RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
        
        setupWindow();
        
        // 创建UI布局（createUI内部会调用setContentView）
        createUI();
        
        // 加载车模图片
        loadCarModelImage();
        
        // 初始化车辆信息
        initCarInfo();
        
        // 设置按钮点击事件
        setupButtons();
        
        // 绑定TaskService（用于屏蔽/恢复官方手势服务）
        bindTaskService();
        
        // 注册屏幕关闭监听（防止双击息屏）
        registerScreenReceiver();
        
        // 注册背屏常亮开关状态变化监听
        registerKeepScreenOnPreferenceListener();
        
        // 启动汽车控制背屏常亮服务（前台Service）
        startCarControlWakeService();
        
        // 初始化音乐投屏歌词功能（已移除深渊镜歌词显示）
        // initMusicLyrics(); // 已移除
    }

    /**
     * 注册 Flutter 侧 {@code flutter.keep_screen_on_enabled} 监听（遗留入口；功能页与投屏以 [RearAssistPrefs] 为准）。
     */
    private void registerKeepScreenOnPreferenceListener() {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            keepScreenOnPreferenceListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if ("flutter.keep_screen_on_enabled".equals(key)) {
                        LogHelper.d(TAG, "🔆 背屏常亮(Flutter)偏好变化 -> 同步 Wake 注册");
                        if (mainHandler != null) {
                            mainHandler.post(RearScreenCarControlActivity.this::applyCarControlKeepScreenWakeFromPrefs);
                        } else {
                            new Handler(Looper.getMainLooper()).post(RearScreenCarControlActivity.this::applyCarControlKeepScreenWakeFromPrefs);
                        }
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
                        if (mainHandler != null) {
                            mainHandler.post(RearScreenCarControlActivity.this::updateProjectionProximitySession);
                        } else {
                            new Handler(Looper.getMainLooper()).post(RearScreenCarControlActivity.this::updateProjectionProximitySession);
                        }
                    } else if (RearAssistPrefs.KEY_KEEP_SCREEN_ON.equals(key)) {
                        LogHelper.d(TAG, "🔆 背屏常亮(miroot_rear_assist)变化 -> 同步 Wake 注册");
                        if (mainHandler != null) {
                            mainHandler.post(RearScreenCarControlActivity.this::applyCarControlKeepScreenWakeFromPrefs);
                        } else {
                            new Handler(Looper.getMainLooper()).post(RearScreenCarControlActivity.this::applyCarControlKeepScreenWakeFromPrefs);
                        }
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
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (getDisplay() != null) {
                    displayId = getDisplay().getDisplayId();
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }
        if (displayId != 1 || mainLayout == null || isFinishing()) {
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
     * 注册屏幕关闭监听广播（防止双击息屏）
     */
    private void registerScreenReceiver() {
        try {
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
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                pauseRearStopExitGraceForScreenOff();
                                if (!isFinishing() && !isDestroyed() && getWindow() != null) {
                                    // 检查背屏常亮开关（与功能页一致）
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
                                            // 避免阻塞主线程导致 ANR：放到后台线程
                                            new Thread(() -> {
                                                try {
                                                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                                    LogHelper.d(TAG, "✅ 已发送唤醒命令（双击息屏后）");
                                                } catch (Exception e) {
                                                    LogHelper.w(TAG, "发送唤醒命令失败", e);
                                                }
                                            }).start();
                                        }
                                        
                                        // 延迟再次发送唤醒命令，确保屏幕能够重新点亮（与未投屏时常亮实现方式一致）
                                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                                                        // 避免阻塞主线程导致 ANR：放到后台线程
                                                        new Thread(() -> {
                                                            try {
                                                                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                                                LogHelper.d(TAG, "✅ 已再次发送唤醒命令（双击息屏后延迟）");
                                                            } catch (Exception e) {
                                                                LogHelper.w(TAG, "再次发送唤醒命令失败", e);
                                                            }
                                                        }).start();
                                                    }
                                                } catch (Exception e) {
                                                    LogHelper.w(TAG, "延迟处理屏幕关闭事件失败", e);
                                                }
                                            }
                                        }, 100); // 100ms延迟
                                        
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
                        new Handler(Looper.getMainLooper()).post(() -> {
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
                                            // 避免阻塞主线程导致 ANR：放到后台线程
                                            new Thread(() -> {
                                                try {
                                                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                                    LogHelper.d(TAG, "✅ 已发送唤醒命令（屏幕点亮/解锁后）");
                                                } catch (Exception e) {
                                                    LogHelper.w(TAG, "发送唤醒命令失败", e);
                                                }
                                            }).start();
                                        }
                                        
                                        LogHelper.d(TAG, "✅ 已重新应用常亮flags（屏幕点亮/解锁后）");
                                    }
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
            filter.addAction(Intent.ACTION_SCREEN_ON); // 添加屏幕点亮监听
            filter.addAction(Intent.ACTION_USER_PRESENT); // 添加用户解锁监听
            registerReceiver(screenReceiver, filter);
            LogHelper.d(TAG, "✅ 已注册屏幕关闭监听（防止双击息屏）");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 注册屏幕关闭监听失败", e);
            screenReceiver = null; // 确保为null，避免后续注销时出错
        }
    }
    
    /**
     * 设置双击手势检测
     */
    private void setupDoubleTapGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                LogHelper.d(TAG, "👆 检测到双击背屏");
                handleDoubleTap();
                return true;
            }
        });
        
        // 在主布局上设置触摸监听
        if (mainLayout != null) {
            mainLayout.setOnTouchListener((v, event) -> {
                if (gestureDetector != null) {
                    gestureDetector.onTouchEvent(event);
                }
                return false; // 不拦截事件，让其他控件也能响应
            });
        }
    }
    
    /**
     * 处理双击事件
     */
    private void handleDoubleTap() {
        try {
            if (!CarControlDeviceGate.isAllowed(this)) {
                MainDisplayUi.showToast(this, "当前设备未授权使用车控", android.widget.Toast.LENGTH_SHORT);
                return;
            }
            // 检查是否已登录
            android.content.SharedPreferences prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE);
            String accessToken = prefs.getString("accessToken", "");
            String userId = prefs.getString("userId", "");
            
            if (accessToken.isEmpty() || userId.isEmpty()) {
                // 未登录，打开登录界面
                LogHelper.d(TAG, "🔓 未登录，打开登录界面");
                Intent intent = new Intent(this, CarControlLoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                // 已登录，打开设置界面
                LogHelper.d(TAG, "⚙️ 已登录，打开设置界面");
                Intent intent = new Intent(this, CarControlSettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 处理双击事件失败", e);
        }
    }
    
    /**
     * 设置窗口属性（参考充电动画的实现）
     */
    private void setupWindow() {
        int opaqueBg = isCarControlNightUi() ? CAR_PROJECTION_NIGHT_BACKGROUND : 0xFF000000;
        RearMirootProjectionLifecycle.applyRearOpaqueWindowBase(this, opaqueBg);

        // 车控投屏时保持常亮（与功能页「投屏常亮」一致）
        boolean keepScreenOnEnabled = isProjectionKeepScreenOnEnabled();

        if (keepScreenOnEnabled) {
            // 保持常亮 + 锁屏显示 + 点亮屏幕
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
            LogHelper.d(TAG, "✅ 车控投屏已启动，屏幕保持常亮（用户设置：开启）");
        } else {
            // 只设置锁屏显示和点亮屏幕，不保持常亮
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
            // 确保清除常亮标志（如果之前设置了）
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            LogHelper.d(TAG, "✅ 车控投屏已启动，屏幕不保持常亮（用户设置：关闭）");
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
        
        // 一次性设置窗口尺寸（确保全屏）
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        getWindow().setAttributes(params);
        
        // 注意：系统UI可见性设置在setContentView之后，在createUI中的hideSystemUI()方法中处理
        // 这样可以避免在窗口还未创建时就设置可见性，减少闪烁
    }
    
    /**
     * 背屏车控：仅隐藏状态栏，保留导航栏/手势 inset（与背屏桌面、音乐投屏一致），
     * 避免沉浸式隐藏导航栏导致边缘手势需等待或与系统返回争用。
     */
    private int hideSystemUiRetry = 0;

    private void hideSystemUI() {
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        View decorView = getWindow().getDecorView();
        if (decorView.getWidth() <= 0 || decorView.getHeight() <= 0) {
            // 迁屏/首帧阶段 decor 可能暂时为 0×0；延后一帧重试，避免 InsetsSource 警告刷屏。
            if (hideSystemUiRetry++ < 6) {
                decorView.postDelayed(this::hideSystemUI, 16L);
            }
            return;
        }
        hideSystemUiRetry = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.navigationBars());
                controller.hide(android.view.WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
            }
        } else {
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }

    private int getDisplayIdSafe() {
        int id = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (getDisplay() != null) {
                    id = getDisplay().getDisplayId();
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "getDisplayIdSafe 失败: " + e.getMessage());
            }
        }
        return id;
    }

    private boolean hasCarMainUi() {
        return mainLayout != null;
    }

    private void cancelMainScreenPlaceholderTimeout() {
        if (mainHandler != null && mainScreenPlaceholderTimeoutRunnable != null) {
            mainHandler.removeCallbacks(mainScreenPlaceholderTimeoutRunnable);
            mainScreenPlaceholderTimeoutRunnable = null;
        }
    }

    private void cancelRearStopExitGrace() {
        if (mainHandler != null && rearStopExitGraceRunnable != null) {
            mainHandler.removeCallbacks(rearStopExitGraceRunnable);
            rearStopExitGraceRunnable = null;
        }
        rearStopExitGraceDeadlineElapsedMs = 0L;
        rearStopExitGraceRemainingMs = 0L;
        rearStopExitGracePausedForScreenOff = false;
    }

    private void pauseRearStopExitGraceForScreenOff() {
        if (mainHandler == null || rearStopExitGraceRunnable == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (rearStopExitGraceDeadlineElapsedMs > now) {
            rearStopExitGraceRemainingMs = rearStopExitGraceDeadlineElapsedMs - now;
        }
        mainHandler.removeCallbacks(rearStopExitGraceRunnable);
        rearStopExitGraceRunnable = null;
        rearStopExitGraceDeadlineElapsedMs = 0L;
        rearStopExitGracePausedForScreenOff = true;
        LogHelper.d(TAG, "息屏：暂停车控退出宽限，剩余 " + rearStopExitGraceRemainingMs + "ms");
    }

    private void handleProjectionForegroundAfterStopGrace() {
        if (rearStopExitGracePausedForScreenOff) {
            rearStopExitGracePausedForScreenOff = false;
            cancelRearStopExitGrace();
            LogHelper.d(TAG, "车控已恢复可见，取消息屏期间暂停的退出宽限");
            return;
        }
        cancelRearStopExitGrace();
    }

    private void ensureProjectionWakeServiceAfterScreenOff() {
        if (!isProjectionKeepScreenOnEnabled()) {
            return;
        }
        if (getDisplayIdSafe() != 1 || isFinishing()) {
            return;
        }
        try {
            RearScreenWakeManager.getInstance().startWakeService(
                    getApplicationContext(), RearScreenCarControlActivity.class);
            RearScreenWakeService.requestNotificationRefresh(this);
        } catch (Exception e) {
            LogHelper.w(TAG, "息屏后确保 RearScreenWakeService 失败", e);
        }
    }

    private void scheduleRearStopExitGraceIfNeeded(String reason) {
        if (rearStopExitGracePausedForScreenOff) {
            LogHelper.d(TAG, reason + "：息屏暂停宽限中，跳过重新安排退出宽限");
            return;
        }
        cancelRearStopExitGrace();
        if (mainHandler == null || isFinishing() || isChangingConfigurations()) {
            return;
        }
        // 充电动画会短暂顶替车控投屏为前台；对齐 3.4：此时不触发宽限自毁/清理，
        // 避免车控清理流程恢复官方手势/Launcher，进而把充电动画也冲掉（表现为“两边都立刻销毁”）。
        try {
            if (RearChargingAnimationCoordinator.getCurrentAnimation()
                == RearChargingAnimationCoordinator.AnimationType.CHARGING) {
                LogHelper.d(TAG, reason + "：检测到充电动画播放中，跳过背屏 onStop 宽限自毁");
                return;
            }
        } catch (Throwable ignored) {
        }
        if (!hasCarMainUi()) {
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
            if (getLifecycle().getCurrentState() == Lifecycle.State.RESUMED) {
                return;
            }
            LogHelper.w(TAG, reason + "：背屏车控已不可见且宽限(" + REAR_STOP_EXIT_GRACE_MS + "ms)内未恢复前台，销毁 Activity");
            try {
                performCleanupAndExit();
            } catch (Exception e) {
                LogHelper.e(TAG, "背屏 onStop 宽限退出清理失败", e);
            }
            if (!isFinishing()) {
                finishProjectionTask();
            }
        };
        rearStopExitGraceDeadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + REAR_STOP_EXIT_GRACE_MS;
        mainHandler.postDelayed(rearStopExitGraceRunnable, REAR_STOP_EXIT_GRACE_MS);
    }

    /**
     * 主屏占位迁背屏后：短间隔轮询 displayId，一旦落背屏立即 {@link #initCarControlUiOnRearIfNeeded}，
     * 避免固定延迟 + 窗口仍带 {@link WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE} 导致系统边缘返回长时间无效。
     */
    private void schedulePollRearUiInitAfterMove() {
        cancelRearUiInitPoll();
        if (mainHandler == null || mainLayout != null) {
            return;
        }
        rearUiInitPollAttempts = 0;
        rearUiInitPollRunnable = this::pollRearUiInitStep;
        mainHandler.post(rearUiInitPollRunnable);
    }

    private void cancelRearUiInitPoll() {
        if (mainHandler != null && rearUiInitPollRunnable != null) {
            mainHandler.removeCallbacks(rearUiInitPollRunnable);
        }
        rearUiInitPollRunnable = null;
        rearUiInitPollAttempts = 0;
    }

    private void pollRearUiInitStep() {
        rearUiInitPollRunnable = null;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (mainLayout != null) {
            return;
        }
        if (getDisplayIdSafe() == 1) {
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
            LogHelper.d(TAG, "迁屏轮询：已到背屏，初始化车控 UI (attempt=" + (rearUiInitPollAttempts + 1) + ")");
            initCarControlUiOnRearIfNeeded("poll");
            return;
        }
        rearUiInitPollAttempts++;
        if (rearUiInitPollAttempts < REAR_UI_INIT_POLL_MAX_ATTEMPTS && mainHandler != null) {
            rearUiInitPollRunnable = this::pollRearUiInitStep;
            mainHandler.postDelayed(rearUiInitPollRunnable, REAR_UI_INIT_POLL_INTERVAL_MS);
        } else {
            LogHelper.w(TAG, "迁屏轮询超时仍未到背屏 (attempts=" + rearUiInitPollAttempts + ")");
        }
    }

    /**
     * 在背屏创建车控 UI（onCreate 直启 / 迁屏轮询 / onResume / onWindowFocusChanged 共用）。
     *
     * @return true 表示本次完成了初始化
     */
    private boolean initCarControlUiOnRearIfNeeded(String reason) {
        if (mainLayout != null || isFinishing()) {
            return false;
        }
        if (getDisplayIdSafe() != 1) {
            return false;
        }
        cancelMainScreenPlaceholderTimeout();
        cancelRearUiInitPoll();
        LogHelper.d(TAG, "🎯 背屏初始化车控 UI (" + reason + ")");
        currentInstance = this;
        initialDisplayId = 1;
        RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
        setupWindow();
        createUI();
        loadCarModelImage();
        initCarInfo();
        setupButtons();
        bindTaskService();
        if (screenReceiver == null) {
            registerScreenReceiver();
        }
        if (keepScreenOnPreferenceListener == null) {
            registerKeepScreenOnPreferenceListener();
        }
        startCarControlWakeService();
        android.view.View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor != null) {
            decor.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                RearDisplayInputHelper.ensureApplicationWindowReceivesInput(RearScreenCarControlActivity.this);
                if (getDisplayIdSafe() == 1) {
                    hideSystemUI();
                }
            });
        }
        return true;
    }

    private void scheduleMainScreenPlaceholderTimeout() {
        cancelMainScreenPlaceholderTimeout();
        if (mainHandler == null) {
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
            LogHelper.w(TAG, "主屏占位超时(" + MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS + "ms)仍未到背屏，销毁车控 Activity");
            finishIllegalCarProjectionSurface();
        };
        mainHandler.postDelayed(mainScreenPlaceholderTimeoutRunnable, MAIN_SCREEN_PLACEHOLDER_TIMEOUT_MS);
    }

    private void finishIllegalCarProjectionSurface() {
        try {
            if (getWindow() != null) {
                getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            }
        } catch (Exception ignored) {
        }
        try {
            performCleanupAndExit();
        } catch (Exception e) {
            LogHelper.e(TAG, "非法显示表面清理失败", e);
        }
        if (mainHandler != null) {
            mainHandler.postDelayed(() -> {
                if (!isFinishing()) {
                    finishProjectionTask();
                }
            }, 50);
        } else if (!isFinishing()) {
            finishProjectionTask();
        }
    }

    /**
     * @return true 表示已发起销毁，调用方应中止后续 UI 逻辑
     */
    private boolean finishIfIllegalCarProjectionSurface(String reason) {
        if (isFinishing()) {
            return true;
        }
        int d = getDisplayIdSafe();
        if (d == 1) {
            return false;
        }
        if (hasCarMainUi()) {
            LogHelper.w(TAG, reason + "：车控界面只能在背屏，当前 displayId=" + d + "，销毁");
            finishIllegalCarProjectionSurface();
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && d != 0 && d != 1) {
            LogHelper.w(TAG, reason + "：非常规显示屏(displayId=" + d + ")，销毁");
            finishIllegalCarProjectionSurface();
            return true;
        }
        return false;
    }
    
    /**
     * 车控内容容器左边距：优先系统/dumpsys 的 cutout.left；背屏且无数据时用 {@link #REAR_DISPLAY_CAMERA_FALLBACK_LEFT_PX}。
     * 子布局不得再使用「整屏坐标 − cutout」二次偏移。
     */
    private int effectiveCarControlContainerLeftMarginPx(int parsedCutoutLeft) {
        if (parsedCutoutLeft > 0) {
            return parsedCutoutLeft;
        }
        if (getDisplayIdSafe() == 1) {
            return REAR_DISPLAY_CAMERA_FALLBACK_LEFT_PX;
        }
        return 0;
    }

    private boolean isCarControlNightUi() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 根据系统深浅色更新文案色、按钮色、油量强调色等（不修改布局结构）。 */
    private void syncCarControlThemeColors() {
        if (isCarControlNightUi()) {
            // 深色模式：深灰底 + 黑白灰控件（无装饰底图）
            colorCarTextPrimary = 0xFFFFFFFF;
            colorCarTextMuted = 0xFFB3B3B3;
            colorCarUpdateTime = 0xFFCCCCCC;
            colorCarFuelBlue = 0xFFAAAAAA;
            carBtnBgPrimary = 0xFF5A5A5A;
            carBtnBgSecondary = 0xFF2E2E2E;
            carBtnIconPrimaryIdle = 0xFFFFFFFF;
            carBtnIconPrimaryActive = 0xFFF5F5F5;
            carBtnIconSecondary = 0xFFE8E8E8;
        } else {
            colorCarTextPrimary = 0xFF1A1A1A;
            colorCarTextMuted = 0xFF5C5C5C;
            colorCarUpdateTime = 0xFF424242;
            colorCarFuelBlue = 0xFF1565C0;
            carBtnBgPrimary = 0xFF81D4FA;
            carBtnBgSecondary = 0xFFE8E8E8;
            carBtnIconPrimaryIdle = 0xFF0D47A1;
            carBtnIconPrimaryActive = 0xFF006064;
            carBtnIconSecondary = 0xFF000000;
        }
    }

    private void bindCarControlBackground() {
        if (backgroundImageView == null) {
            return;
        }
        backgroundImageView.setImageDrawable(null);
        backgroundImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backgroundImageView.setAdjustViewBounds(false);
        if (isCarControlNightUi()) {
            backgroundImageView.setBackgroundColor(CAR_PROJECTION_NIGHT_BACKGROUND);
            LogHelper.d(TAG, "✓ 车控背景：深色模式灰底（随系统 uiMode）");
        } else {
            backgroundImageView.setBackgroundColor(0xFFFFFFFF);
            LogHelper.d(TAG, "✓ 车控背景：浅色模式白底（随系统 uiMode）");
        }
    }

    private void applyCarControlInfoTextColors() {
        if (rangeText != null) {
            rangeText.setTextColor(colorCarTextPrimary);
        }
        if (rangeLabel != null) {
            rangeLabel.setTextColor(colorCarTextMuted);
        }
        if (odometerText != null) {
            odometerText.setTextColor(colorCarTextMuted);
        }
        if (interiorTempText != null) {
            interiorTempText.setTextColor(colorCarTextMuted);
        }
        if (exteriorTempText != null) {
            exteriorTempText.setTextColor(colorCarTextMuted);
        }
        if (updateTimeText != null) {
            updateTimeText.setTextColor(colorCarUpdateTime);
        }
    }

    private void applyCarControlRefreshIconTint() {
        if (refreshIcon == null) {
            return;
        }
        int c = isCarControlNightUi() ? 0xFFFFFFFF : 0xFF424242;
        refreshIcon.setColorFilter(c, PorterDuff.Mode.SRC_IN);
    }

    /** 初始油量区配色（无接口数据时）；有 PNG 油量图标时尽量不加滤镜。 */
    private void applyCarControlFuelChromeDefaults(boolean fuelIconIsAssetPng) {
        if (fuelPercentText != null) {
            fuelPercentText.setTextColor(colorCarFuelBlue);
        }
        if (fuelProgressBar != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fuelProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(colorCarFuelBlue));
        }
        if (fuelIcon != null) {
            if (fuelIconIsAssetPng) {
                fuelIcon.clearColorFilter();
            } else {
                fuelIcon.setColorFilter(colorCarFuelBlue, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    /**
     * 按油量百分比更新油量文案/进度条/图标色（与 {@link #updateCarInfo} 规则一致，供主题切换复用）。
     * 文案：&lt;10% 红，10%–30% 主题蓝，≥30% 橙；进度条/图标：&lt;10% 红，10%–29% 橙，≥30% 主题蓝。
     */
    private void applyFuelPercentVisuals(int fuelPercent) {
        int fuelBarIconColor;
        int fuelTextColor;
        if (fuelPercent < 10) {
            fuelBarIconColor = 0xFFFF0000;
            fuelTextColor = 0xFFFF0000;
        } else if (isCarControlNightUi()) {
            // 深色模式：油量仅用灰阶区分档位（与黑白 UI 一致），低油量仍用红
            if (fuelPercent < 30) {
                fuelBarIconColor = 0xFF888888;
                fuelTextColor = 0xFFCCCCCC;
            } else {
                fuelBarIconColor = 0xFFCCCCCC;
                fuelTextColor = 0xFFAAAAAA;
            }
        } else if (fuelPercent < 30) {
            fuelBarIconColor = 0xFFFF9800;
            fuelTextColor = colorCarFuelBlue;
        } else {
            fuelBarIconColor = colorCarFuelBlue;
            fuelTextColor = 0xFFFF9800;
        }
        if (fuelPercentText != null) {
            fuelPercentText.setTextColor(fuelTextColor);
        }
        if (fuelProgressBar != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fuelProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(fuelBarIconColor));
        }
        if (fuelIcon != null) {
            fuelIcon.setColorFilter(fuelBarIconColor, PorterDuff.Mode.SRC_IN);
        }
    }

    private void applyCarControlAllButtonThemes() {
        for (int i = 0; i < controlButtons.length && i < buttonFunctions.length; i++) {
            if (controlButtons[i] != null && buttonFunctions[i] != null) {
                updateButtonBackground(i, buttonFunctions[i]);
            }
        }
    }

    /** 系统深浅色或重建后刷新车控视觉（背景、左栏、刷新区、按钮）。 */
    private void applyCarControlUiMode() {
        if (mainLayout == null) {
            return;
        }
        syncCarControlThemeColors();
        bindCarControlBackground();
        applyCarControlInfoTextColors();
        if (cachedFuelPercentForUi < 0) {
            boolean assetYou = CarControlAssets.exists(this, CarControlAssets.pngPath("you"));
            applyCarControlFuelChromeDefaults(assetYou);
        } else {
            applyFuelPercentVisuals(cachedFuelPercentForUi);
        }
        applyCarControlRefreshIconTint();
        applyCarControlAllButtonThemes();
    }

    /**
     * 创建UI布局
     * 背屏常见 976×596：contentContainer 避让摄像头；车模/信息列/底栏按整屏左缘 px 锚点（300 / 540）。
     * 背景与文字跟随系统 uiMode：夜间深灰底+黑白灰控件，日间白底+原有蓝灰按钮风格。
     */
    private void createUI() {
        // 防止重复创建UI，避免车模图片被多次调整导致闪烁
        if (mainLayout != null) {
            LogHelper.d(TAG, "⚠️ UI已创建，跳过重复创建");
            return;
        }

        syncCarControlThemeColors();

        mainLayout = new RelativeLayout(this);
        backgroundImageView = new ImageView(this);
        bindCarControlBackground();
        RelativeLayout.LayoutParams bgParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        mainLayout.addView(backgroundImageView, bgParams);
        
        // 在创建contentContainer之前，先获取cutout信息并计算安全区域margin（避免闪烁）
        int cutoutLeft = 0, cutoutTop = 0, cutoutRight = 0, cutoutBottom = 0;
        try {
            // 优先从缓存获取（如果已初始化）
            RearDisplayHelper.RearDisplayInfo cachedInfo = DisplayInfoCache.getInstance().getCachedInfo();
            if (cachedInfo != null && cachedInfo.hasCutout()) {
                cutoutLeft = cachedInfo.cutout.left;
                cutoutTop = cachedInfo.cutout.top;
                cutoutRight = cachedInfo.cutout.right;
                cutoutBottom = cachedInfo.cutout.bottom;
                LogHelper.d(TAG, String.format("✓ 从缓存获取cutout信息: left=%d, top=%d, right=%d, bottom=%d",
                    cutoutLeft, cutoutTop, cutoutRight, cutoutBottom));
            } else if (taskService != null) {
                // 如果缓存未初始化且有TaskService，尝试实时获取（但会阻塞UI线程，不推荐）
                // 这里先使用默认值0，后续在applySafeAreaPadding()中更新
                LogHelper.d(TAG, "⚠️ 缓存未初始化或cutout为空，contentContainer初始margin为0，后续在applySafeAreaPadding()中更新");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 获取cutout信息失败，使用默认值0", e);
        }
        
        // 创建内容容器（背景延伸到摄像头区域，内容通过安全区域避开摄像头）
        contentContainer = new RelativeLayout(this);
        RelativeLayout.LayoutParams containerParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        );
        int containerLeft = effectiveCarControlContainerLeftMarginPx(cutoutLeft);
        containerParams.setMargins(containerLeft, cutoutTop, cutoutRight, cutoutBottom);
        if (containerLeft > 0 || cutoutTop > 0 || cutoutRight > 0 || cutoutBottom > 0) {
            LogHelper.d(TAG, String.format("✅ contentContainer 安全区 margin: left=%d(parsed=%d), top=%d, right=%d, bottom=%d",
                containerLeft, cutoutLeft, cutoutTop, cutoutRight, cutoutBottom));
        }
        mainLayout.addView(contentContainer, containerParams);
        
        // ========== 顶部信息栏 ==========
        RelativeLayout topBar = new RelativeLayout(this);
        topBar.setId(View.generateViewId()); // 生成唯一ID
        RelativeLayout.LayoutParams topBarParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        topBarParams.setMargins(0, 10, 0, 0);
        contentContainer.addView(topBar, topBarParams);
        
        // 获取屏幕密度（在整个方法中重复使用）
        float density = getResources().getDisplayMetrics().density;
        
        // 左侧信息布局（固定宽度 220px）：基线为距整屏左缘 300px，此处减去 contentContainer.leftMargin
        LinearLayout leftTopLayout = new LinearLayout(this);
        carInfoTopLayout = leftTopLayout;
        leftTopLayout.setId(View.generateViewId()); // 生成唯一ID
        leftTopLayout.setOrientation(LinearLayout.VERTICAL);
        RelativeLayout.LayoutParams leftTopParams = new RelativeLayout.LayoutParams(
            220, // 固定宽度220px
            RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        leftTopParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        int infoRelLeft = CAR_UI_SCREEN_LEFT_INFO_PX - containerLeft;
        leftTopParams.setMargins(Math.max(0, infoRelLeft), 20, 0, 0);
        topBar.addView(leftTopLayout, leftTopParams);
        
        // 续航里程容器（纵向布局）
        LinearLayout rangeLayout = new LinearLayout(this);
        rangeLayout.setOrientation(LinearLayout.VERTICAL);
        rangeLayout.setGravity(Gravity.START);
        leftTopLayout.addView(rangeLayout);
        
        // 续航里程数值（适中大小，单行显示）
        rangeText = new TextView(this);
        rangeText.setText("270 km");
        rangeText.setTextColor(colorCarTextPrimary);
        rangeText.setTextSize(18); // 从20减小到18
        rangeText.setTypeface(null, android.graphics.Typeface.BOLD);
        rangeText.setSingleLine(true); // 限制单行显示
        rangeText.setEllipsize(android.text.TextUtils.TruncateAt.END); // 如果文本过长，在末尾显示省略号
        rangeLayout.addView(rangeText);
        
        // "续航里程"标签（小字，在数值下方，距离更近，单行显示）
        rangeLabel = new TextView(this);
        rangeLabel.setText("续航里程");
        rangeLabel.setTextColor(colorCarTextMuted);
        rangeLabel.setTextSize(11); // 从12减小到11
        rangeLabel.setSingleLine(true); // 限制单行显示
        rangeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END); // 如果文本过长，在末尾显示省略号
        // 设置上边距，让标签和数值更近
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.setMargins(0, (int)(-2 * density), 0, 0); // 负边距，让标签和数值更近（从默认间距减少）
        rangeLayout.addView(rangeLabel, labelParams);
        
        // 油量信息容器（横向布局，显示图标和百分比）
        LinearLayout fuelLayout = new LinearLayout(this);
        fuelLayout.setOrientation(LinearLayout.HORIZONTAL);
        fuelLayout.setGravity(Gravity.CENTER_VERTICAL);
        fuelLayout.setPadding(0, 8, 0, 0); // 与续航区块间距略收紧
        leftTopLayout.addView(fuelLayout);
        
        // 油量图标（优先 assets/car/you.png）
        int fuelIconSize = (int)(16 * density); // 16dp，与之前的文字大小差不多
        fuelIcon = new ImageView(this);
        android.graphics.drawable.Drawable fuelDr = CarControlAssets.loadCarIconDrawable(this, "you", fuelIconSize);
        if (fuelDr != null) {
            fuelIcon.setImageDrawable(fuelDr);
            fuelIcon.clearColorFilter();
        } else {
            int iconResId = getResources().getIdentifier("you", "drawable", getPackageName());
            if (iconResId != 0) {
                fuelIcon.setImageResource(iconResId);
                fuelIcon.setColorFilter(colorCarFuelBlue, PorterDuff.Mode.SRC_IN);
            } else {
                LogHelper.w(TAG, "⚠️ 油量图标未找到: assets/car/you.png 与 drawable/you");
            }
        }
        LinearLayout.LayoutParams fuelIconParams = new LinearLayout.LayoutParams(fuelIconSize, fuelIconSize);
        fuelIconParams.setMargins(0, 0, (int)(6 * density), 0); // 右边距6dp
        fuelIcon.setLayoutParams(fuelIconParams);
        fuelIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        if (fuelDr == null) {
            fuelIcon.setColorFilter(colorCarFuelBlue, PorterDuff.Mode.SRC_IN);
        }
        fuelLayout.addView(fuelIcon);
        
        // 油量百分比（单行显示）
        fuelPercentText = new TextView(this);
        fuelPercentText.setText("43%");
        fuelPercentText.setTextColor(colorCarFuelBlue);
        fuelPercentText.setTextSize(12); // 从14减小到12
        fuelPercentText.setTypeface(null, android.graphics.Typeface.BOLD);
        fuelPercentText.setSingleLine(true); // 限制单行显示
        fuelPercentText.setEllipsize(android.text.TextUtils.TruncateAt.END); // 如果文本过长，在末尾显示省略号
        fuelLayout.addView(fuelPercentText);
        
        // 油量进度条（单独一行，显示在油量百分比下方）
        fuelProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fuelProgressBar.setId(View.generateViewId()); // 生成唯一ID，用于图片定位
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fuelProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(colorCarFuelBlue));
        }
        fuelProgressBar.setMax(100);
        fuelProgressBar.setProgress(43);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, // 占满整行
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        // 转换为dp到px（使用已定义的density变量）
        progressParams.height = (int)(8 * density);
        progressParams.setMargins(0, (int)(2 * density), 0, 0); // 与油量图标行间距收紧
        leftTopLayout.addView(fuelProgressBar, progressParams);
        
        // 总里程、车内温度、车外温度（纵向布局，放在油量进度条下方）
        // 总里程
        odometerText = new TextView(this);
        odometerText.setText("总里程: --");
        odometerText.setTextColor(colorCarTextMuted);
        odometerText.setTextSize(11);
        odometerText.setSingleLine(true);
        LinearLayout.LayoutParams odometerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        odometerParams.setMargins(0, (int)(4 * density), 0, 0); // 相对进度条上移
        leftTopLayout.addView(odometerText, odometerParams);
        
        // 车内温度
        interiorTempText = new TextView(this);
        interiorTempText.setText("🌡️ 车内: --℃");
        interiorTempText.setTextColor(colorCarTextMuted);
        interiorTempText.setTextSize(11);
        interiorTempText.setSingleLine(true);
        LinearLayout.LayoutParams interiorTempParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        interiorTempParams.setMargins(0, (int)(2 * density), 0, 0);
        leftTopLayout.addView(interiorTempText, interiorTempParams);
        
        // 车外温度
        exteriorTempText = new TextView(this);
        exteriorTempText.setText("🌡️ 车外: --℃");
        exteriorTempText.setTextColor(colorCarTextMuted);
        exteriorTempText.setTextSize(11);
        exteriorTempText.setSingleLine(true);
        LinearLayout.LayoutParams exteriorTempParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        exteriorTempParams.setMargins(0, (int)(2 * density), 0, 0);
        leftTopLayout.addView(exteriorTempText, exteriorTempParams);
        
        // 右侧信息布局（刷新按钮 + 更新时间文本，一行显示，高度居中对齐）
        LinearLayout rightTopLayout = new LinearLayout(this);
        rightTopLayout.setOrientation(LinearLayout.HORIZONTAL); // 水平方向，一行显示
        rightTopLayout.setGravity(Gravity.CENTER_VERTICAL); // 垂直居中对齐
        RelativeLayout.LayoutParams rightTopParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        rightTopParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        rightTopParams.setMargins(0, (int) (10 * density), (int) (12 * density), 0);
        topBar.addView(rightTopLayout, rightTopParams);
        
        // 1. 刷新按钮（第一个，放在更新文本左边，距离15px，水平居中对齐）
        refreshIcon = new ImageView(this);
        refreshIcon.setImageResource(R.drawable.ic_car_control_refresh);
        refreshIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        refreshIcon.setContentDescription(getString(R.string.car_control_vehicle_refresh));
        int tap = (int) (36 * density);
        refreshIcon.setPadding((int)(6 * density), (int)(6 * density), (int)(6 * density), (int)(6 * density));
        refreshIcon.setBackground(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            refreshIcon.setForeground(null);
        }
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(tap, tap);
        iconParams.setMargins(0, 0, 15, 0); // 右边距15px，与更新文本的距离
        iconParams.gravity = Gravity.CENTER_VERTICAL; // 垂直居中对齐
        refreshIcon.setClickable(true);
        refreshIcon.setFocusable(true);
        refreshIcon.setSoundEffectsEnabled(false);
        refreshIcon.setOnClickListener(v -> {
            LogHelper.d(TAG, "🔄 点击刷新图标");
            initCarInfo(); // 旋转动画在 initCarInfo 内与请求同步启动
        });
        rightTopLayout.addView(refreshIcon, iconParams);
        
        // 2. 更新时间文本（第二个，放在刷新按钮右边）
        updateTimeText = new TextView(this);
        updateTimeText.setText("--:--");
        updateTimeText.setTextColor(colorCarUpdateTime);
        updateTimeText.setTextSize(12); // 12sp
        updateTimeText.setSingleLine(true);
        updateTimeText.setEllipsize(TextUtils.TruncateAt.END);
        {
            float wPlace = updateTimeText.getPaint().measureText("--:--");
            float wTime = updateTimeText.getPaint().measureText("88:88");
            updateTimeText.setMinimumWidth((int) Math.ceil(Math.max(wPlace, wTime)));
        }
        LinearLayout.LayoutParams updateTimeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        updateTimeParams.gravity = Gravity.CENTER_VERTICAL; // 垂直居中对齐
        rightTopLayout.addView(updateTimeText, updateTimeParams);
        // 长按更新时间文本，作为保底退出车控界面的方式
        updateTimeText.setOnLongClickListener(v -> {
            LogHelper.d(TAG, "⏱️ 长按更新时间文本，用户请求结束车控界面");
            try {
                performCleanupAndExit();
            } catch (Exception e) {
                LogHelper.e(TAG, "长按更新时间文本执行清理失败", e);
            }
            finishProjectionTask();
            return true;
        });
        
        // ========== 底部按钮布局（ViewPager2容器：距整屏左 300px、右 50、底边距见 CAR_UI_BUTTON_BAR_BOTTOM_MARGIN_PX）==========
        buttonPageContainer = new androidx.viewpager2.widget.ViewPager2(this);
        buttonPageContainer.setOrientation(androidx.viewpager2.widget.ViewPager2.ORIENTATION_HORIZONTAL);
        buttonPageContainer.setUserInputEnabled(false);

        RelativeLayout.LayoutParams buttonContainerParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        buttonContainerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        int buttonRelLeft = CAR_UI_SCREEN_LEFT_INFO_PX - containerLeft;
        buttonContainerParams.setMargins(
                Math.max(0, buttonRelLeft), 0, 50, CAR_UI_BUTTON_BAR_BOTTOM_MARGIN_PX);
        contentContainer.addView(buttonPageContainer, buttonContainerParams);

        // ========== 车模（整屏左 540px 起、垂直居中、405×226，不夹在顶栏底栏之间以免被裁成细条）==========
        carModelImage = new ImageView(this);
        carModelImage.setId(View.generateViewId());
        carModelImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        carModelImage.setAdjustViewBounds(true);
        carModelImage.setClickable(true);
        carModelImage.setFocusable(true);

        RelativeLayout.LayoutParams imageParams = new RelativeLayout.LayoutParams(
                CAR_MODEL_VIEW_WIDTH_PX,
                CAR_MODEL_VIEW_HEIGHT_PX);
        imageParams.addRule(RelativeLayout.CENTER_VERTICAL);
        int imageRelLeft = CAR_UI_SCREEN_LEFT_MODEL_PX - containerLeft;
        imageParams.setMargins(Math.max(0, imageRelLeft), 0, 20, 0);
        contentContainer.addView(carModelImage, imageParams);

        LogHelper.d(TAG, "✅ 车模基线布局：整屏左 " + CAR_UI_SCREEN_LEFT_MODEL_PX + "px、垂直居中、"
                + CAR_MODEL_VIEW_WIDTH_PX + "×" + CAR_MODEL_VIEW_HEIGHT_PX);
        
        // 从配置加载按钮
        loadButtonConfig();

        // 初始化 CarButtonStateManager（必须在 setupViewPager 之前，确保按钮状态可用）
        carButtonStateManager = new CarButtonStateManager(this);
        SharedPreferences carPrefs = getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE);
        carButtonStateManager.loadFromPrefs(carPrefs);
        // 恢复上次确认的后备箱/透气状态，避免异步 API 返回前按钮颜色闪烁
        isTrunkOpen = carPrefs.getBoolean(KEY_IS_TRUNK_OPEN, false);
        isVentMode = carPrefs.getBoolean(KEY_IS_VENT_MODE, false);

        // 从恢复的 remoteOn 状态同步 buttonFunctions（避免异步 API 返回前按钮显示错误文本）
        for (int i = 0; i < controlButtons.length && i < buttonFunctions.length; i++) {
            if (carButtonStateManager.get(i) != null) {
                buttonFunctions[i] = carButtonStateManager.getDisplayText(i);
            }
        }
        carButtonStateManager.setCallback(new CarButtonStateManager.Callback() {
            @Override
            public void onButtonStateChanged(int slotIndex) {
                if (isFinishing() || isDestroyed()) return;
                String newText = carButtonStateManager.getDisplayText(slotIndex);
                updateButtonText(slotIndex, newText);
                if (buttonFunctions != null && slotIndex < buttonFunctions.length) {
                    buttonFunctions[slotIndex] = newText;
                }
                // 车窗状态变化时同步更新透气按钮背景
                if (carButtonStateManager.getFunctionKey(slotIndex) == CarButtonInfo.FunctionKey.WINDOW) {
                    isWindowOpen = carButtonStateManager.getRemoteOn(slotIndex);
                    for (int v = 0; v < 8; v++) {
                        if (carButtonStateManager.getFunctionKey(v) == CarButtonInfo.FunctionKey.VENTILATE) {
                            updateButtonBackground(v, "透气");
                            break;
                        }
                    }
                }
                // 透气按钮状态变化时持久化 isVentMode
                if (carButtonStateManager.getFunctionKey(slotIndex) == CarButtonInfo.FunctionKey.VENTILATE) {
                    isVentMode = carButtonStateManager.getRemoteOn(slotIndex);
                    getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE)
                        .edit().putBoolean(KEY_IS_VENT_MODE, isVentMode).apply();
                }
                // 后备箱状态变化时更新 isTrunkOpen
                if (carButtonStateManager.getFunctionKey(slotIndex) == CarButtonInfo.FunctionKey.TRUNK) {
                    isTrunkOpen = carButtonStateManager.getRemoteOn(slotIndex);
                    getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE)
                        .edit().putBoolean(KEY_IS_TRUNK_OPEN, isTrunkOpen).apply();
                }
            }

            @Override
            public void onSyncStart() {
                if (!isFinishing() && !isDestroyed()) {
                    startRefreshIconRotation();
                }
            }

            @Override
            public void onSyncComplete() {
                if (!isFinishing() && !isDestroyed()) {
                    stopRefreshIconRotation();
                }
            }

            @Override
            public void onCommandResult(int slotIndex, boolean success, String message) {
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    // 首次成功（message==null）时震动反馈；"confirmed" 轮询确认只刷新 UI 不重复震
                    if (message == null) {
                        vibrate(VIBRATE_SUCCESS_LONG_PRESS_MS);
                    }
                    showToast("执行成功");
                    // 延迟刷新车辆信息面板（等待 API 状态同步）
                    if (mainHandler != null) {
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                initCarInfo();
                            }
                        }, 2000);
                    }
                } else {
                    vibrate(VIBRATE_TOUCH_DOWN_MS); // 失败短震
                    showToast(message != null ? "失败: " + message : "执行失败");
                }
            }
        });

        // 设置ViewPager2（必须在loadButtonConfig之后）
        setupViewPager();

        applyCarControlUiMode();
        
        setContentView(mainLayout);
        
        // 在车模图片上设置滑动监听，用于切换按钮页面（必须在setContentView之后）
        // 使用post确保View已经添加到布局中，并且totalPages已经计算完成
        if (mainHandler != null) {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed() && carModelImage != null) {
                    LogHelper.d(TAG, "🔧 准备设置车模图片滑动监听，totalPages=" + totalPages);
                    setupCarImageSwipeListener();
                }
            });
        } else {
            // 如果mainHandler为null，直接设置
            if (carModelImage != null) {
                LogHelper.d(TAG, "🔧 直接设置车模图片滑动监听，totalPages=" + totalPages);
                setupCarImageSwipeListener();
            }
        }
        
        // 为按钮容器添加滑动监听（循环切换）
        // 注意：setupButtonContainerSwipeListener()已在setupViewPager()中调用，这里不需要重复调用
        
        // 应用安全区域适配（让内容避开摄像头，但背景延伸到摄像头区域）
        // 注意：如果缓存未初始化，这里会尝试初始化并更新margin（可能造成轻微闪烁）
        // 但大多数情况下，cutout信息已经在创建contentContainer时应用了
        applySafeAreaPadding();
        updateCarScreenAnchoredChildMargins();

        // 首帧即应用手势区策略，post 再刷一次以贴合 layout/insets 稳定后的状态
        if (!isFinishing() && !isDestroyed()) {
            hideSystemUI();
        }
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                hideSystemUI();
            }
        });

        // 设置双击手势检测（在setContentView之后，确保mainLayout已创建）
        if (mainHandler != null) {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    setupDoubleTapGesture();
                }
            });
        }

    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getDisplayIdSafe() == 1 && !isCleaningUp) {
            bottomSwipeHandler.handleTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 对齐 {@link com.wmqc.miroot.charging.RearScreenChargingActivity#finishFromMiRoot}：
     * 不压黑、不在 finish 前 restore；{@link #finish()} 内唤醒背屏，官方背屏在 onDestroy 后恢复。
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
        LogHelper.d(TAG, "🚪 车控投屏结束: " + reason);
        ProjectionOngoingNotifications.cancelAll(getApplicationContext());
        RearMirootProjectionLifecycle.hideWindowBeforeProjectionFinish(this);
        // 主屏 HOME 仅在 finish() 中发送一次（避免与 finish / onDestroy restore 连发）
        finish();
    }

    /**
     * 充电动画覆盖层收起时：唤醒背屏并露出下层歌词（车控自身结束不走此路径）。
     */
    private void prepareRearDisplayBeforeGestureFinish() {
        if (getDisplayIdSafe() != 1) {
            return;
        }
        try {
            ITaskService ts = taskService;
            if (ts == null) {
                ts = ChargingService.getTaskService();
            }
            if (ts != null) {
                ts.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "手势退出前唤醒背屏失败: " + e.getMessage());
        }
        try {
            RearScreenLyricsActivity.forceShowProjectionUiAfterChargingOverlay();
        } catch (Throwable t) {
            LogHelper.w(TAG, "手势退出前强制歌词 UI 可见失败: " + t.getMessage());
        }
    }

    private void registerCarControlBackPressedCallback() {
        if (carBackPressedCallbackRegistered) {
            return;
        }
        carBackPressedCallbackRegistered = true;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                LogHelper.d(TAG, "🔙 用户按返回键，手动退出车控投屏");
                finishProjectionFromUser("back-pressed");
            }
        });
    }
    
    /**
     * 创建控制按钮（适配背屏分辨率）
     */
    /**
     * 白底黑图标：车辆/功能处于「已锁、窗关、空调关、加热关、尾箱关」等，按钮文案为解锁/开窗/打开…/点火/主副驾加热；寻车为单次操作亦用白底。
     * 蓝底：未锁、窗开、空调开、加热开、尾箱开等，按钮文案为锁车/关窗/关闭…/熄火/关加热，与代码注释「锁车=当前未锁」一致。
     */
    private boolean isCarControlAlertSecondaryState(String text) {
        if (text == null) {
            return false;
        }

        // 浅灰底（Secondary）：已锁、窗关、空调关、加热关等（车辆处于"闲置/关闭"状态）
        // 按钮文本为解锁/开窗/打开…/点火/主副驾加热（即按下后会执行的动作）
        if ("解锁".equals(text) || "开窗".equals(text) || "打开空调".equals(text) || "打开座椅加热".equals(text)
                || "点火".equals(text) || "主驾加热".equals(text) || "副驾加热".equals(text)) {
            return true;
        }
        // 寻车为单次操作，始终浅灰底
        if ("寻车".equals(text)) {
            return true;
        }
        // 尾箱关闭时浅灰底，开启时蓝底
        if ("尾箱".equals(text) || "后备箱".equals(text) || "开后备箱".equals(text)) {
            return !isTrunkOpen;
        }
        // 透气按钮：非透气模式时浅灰底，透气模式（一条缝）时蓝底
        if ("透气".equals(text)) {
            return !isVentMode;
        }
        // 蓝底（Primary）：未锁、窗开、空调开、加热开等（车辆处于"活跃"状态）
        // 按钮文本为锁车/关窗/关闭…/熄火/关加热
        return false;
    }

    private boolean isCarControlIconActiveState(String text, String resourceName) {
        if (resourceName != null) {
            return hasOnSuffix(resourceName);
        }
        return isOpenState(text);
    }

    /** 车控按钮图标着色（深浅色下统一用 SRC_IN） */
    private void applyCarControlButtonIconTint(android.graphics.drawable.Drawable icon, String text, String resourceName) {
        if (icon == null) {
            return;
        }
        icon.clearColorFilter();
        int color = isCarControlAlertSecondaryState(text)
                ? carBtnIconSecondary
                : (isCarControlIconActiveState(text, resourceName)
                        ? carBtnIconPrimaryActive
                        : carBtnIconPrimaryIdle);
        icon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    /**
     * 车控按钮图标：优先 {@code assets/car/{name}.png}，否则 {@code res/drawable}。
     */
    private android.graphics.drawable.Drawable loadControlButtonIcon(String text, int iconSize) {
        String resourceName = getIconResourceName(text);
        if (resourceName != null) {
            android.graphics.drawable.Drawable assetIcon = CarControlAssets.loadCarIconDrawable(this, resourceName, iconSize);
            if (assetIcon != null) {
                assetIcon.mutate();
                assetIcon.setBounds(0, 0, iconSize, iconSize);
                applyCarControlButtonIconTint(assetIcon, text, resourceName);
                return assetIcon;
            }
        }
        int iconResId = getIconResourceId(text);
        if (iconResId == 0) {
            return null;
        }
        try {
            android.graphics.drawable.Drawable icon;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                icon = getResources().getDrawable(iconResId, getTheme());
            } else {
                icon = getResources().getDrawable(iconResId);
            }
            if (icon != null) {
                icon.mutate();
                icon.setBounds(0, 0, iconSize, iconSize);
                String nameForTint = resourceName != null ? resourceName : getResourceName(iconResId);
                if (nameForTint == null) {
                    LogHelper.w(TAG, "⚠️ 无法获取资源名，资源ID=" + iconResId + ", 功能=" + text);
                }
                applyCarControlButtonIconTint(icon, text, nameForTint);
                return icon;
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 加载图标失败: " + text, e);
        }
        return null;
    }

    /**
     * 根据按钮文本获取背景色（「活跃」态浅冷蓝底 / 「静态」态白底）
     */
    private int getButtonBackgroundColor(String text) {
        return isCarControlAlertSecondaryState(text) ? carBtnBgSecondary : carBtnBgPrimary;
    }
    
    /**
     * 更新按钮背景色
     */
    private void updateButtonBackground(int buttonIndex, String text) {
        if (buttonIndex >= 0 && buttonIndex < controlButtons.length && controlButtons[buttonIndex] != null) {
            Button button = controlButtons[buttonIndex];
            android.graphics.drawable.Drawable currentBg = button.getBackground();
            
            float density = getResources().getDisplayMetrics().density;
            int buttonSize = (int)(40 * density);
            int iconSize = (int)(32 * density);
            int iconInset = (buttonSize - iconSize) / 2;
            
            android.graphics.drawable.GradientDrawable bgDrawable = new android.graphics.drawable.GradientDrawable();
            bgDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bgDrawable.setColor(getButtonBackgroundColor(text));
            
            if (currentBg instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable layerDrawable = (android.graphics.drawable.LayerDrawable) currentBg;
                if (layerDrawable.getNumberOfLayers() >= 2) {
                    // 更新背景层
                    layerDrawable.setDrawable(0, bgDrawable);
                    // 同时更新图标层的颜色（根据状态设置蓝色或黑色）
                    android.graphics.drawable.Drawable iconDrawable = layerDrawable.getDrawable(1);
                    if (iconDrawable != null) {
                        iconDrawable.mutate();
                        // 如果图标是 InsetDrawable，需要获取内部的 Drawable
                        android.graphics.drawable.Drawable actualIcon = iconDrawable;
                        if (iconDrawable instanceof android.graphics.drawable.InsetDrawable) {
                            android.graphics.drawable.InsetDrawable insetDrawable = (android.graphics.drawable.InsetDrawable) iconDrawable;
                            actualIcon = insetDrawable.getDrawable();
                            if (actualIcon != null) {
                                actualIcon.mutate();
                            }
                        }
                        if (actualIcon != null) {
                            String resourceName = getIconResourceName(text);
                            if (resourceName == null) {
                                int rid = getIconResourceId(text);
                                resourceName = getResourceName(rid);
                            }
                            if (resourceName == null) {
                                LogHelper.w(TAG, "⚠️ [更新背景] 无法获取资源名，功能=" + text);
                            }
                            applyCarControlButtonIconTint(actualIcon, text, resourceName);
                        }
                    }
                    // 强制刷新按钮背景
                    button.invalidate();
                } else {
                    android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[]{bgDrawable};
                    android.graphics.drawable.LayerDrawable newLayerDrawable = new android.graphics.drawable.LayerDrawable(layers);
                    button.setBackground(newLayerDrawable);
                }
            } else {
                android.graphics.drawable.Drawable icon = loadControlButtonIcon(text, iconSize);
                android.graphics.drawable.Drawable[] layers;
                if (icon != null) {
                    layers = new android.graphics.drawable.Drawable[]{bgDrawable, icon};
                } else {
                    layers = new android.graphics.drawable.Drawable[]{bgDrawable};
                }
                android.graphics.drawable.LayerDrawable layerDrawable = new android.graphics.drawable.LayerDrawable(layers);
                button.setBackground(layerDrawable);
            }
        }
    }
    
    public Button createControlButton(String text) {
        Button button = new Button(this);
        button.setText("");
        button.setContentDescription(text);
        
        float density = getResources().getDisplayMetrics().density;
        int buttonSize = (int)(40 * density);
        int iconSize = (int)(32 * density);
        int iconInset = (buttonSize - iconSize) / 2;
        
        LogHelper.d(TAG, "🔘 创建按钮: " + text + ", 资源名: " + getIconResourceName(text));
        
        android.graphics.drawable.GradientDrawable bgDrawable = new android.graphics.drawable.GradientDrawable();
        bgDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bgDrawable.setColor(getButtonBackgroundColor(text));
        
        android.graphics.drawable.Drawable icon = loadControlButtonIcon(text, iconSize);
        android.graphics.drawable.Drawable[] layers;
        if (icon != null) {
            layers = new android.graphics.drawable.Drawable[]{bgDrawable, icon};
            LogHelper.d(TAG, "✅ 图标已设置: " + text + ", 尺寸: " + iconSize + "px, inset: " + iconInset + "px");
        } else {
            LogHelper.w(TAG, "⚠️ 未找到图标资源: " + text);
            layers = new android.graphics.drawable.Drawable[]{bgDrawable};
        }
        
        android.graphics.drawable.LayerDrawable layerDrawable = new android.graphics.drawable.LayerDrawable(layers);
        if (icon != null) layerDrawable.setLayerInset(1, iconInset, iconInset, iconInset, iconInset);
        button.setBackground(layerDrawable);
        
        button.setMinWidth(buttonSize);
        button.setMinHeight(buttonSize);
        button.setMaxWidth(buttonSize);
        button.setMaxHeight(buttonSize);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            buttonSize,
            buttonSize,
            1.0f
        );
        params.setMargins((int)(8 * density), 0, (int)(8 * density), 0);
        button.setLayoutParams(params);
        // 仅通过按住计时触发，禁止单击/系统长按反馈（避免 Android 16 / 小米上误触与双重反馈）
        button.setOnClickListener(null);
        button.setLongClickable(false);
        button.setHapticFeedbackEnabled(false);
        
        return button;
    }
    
    /**
     * 根据按钮文本获取图标资源名（与 {@code assets/car/{name}.png}、{@code res/drawable} 对应）
     */
    private String getIconResourceName(String text) {
        if (text == null) {
            return null;
        }
        // 统一规则：按钮文本表示当前状态，当前状态为"开启/未锁"时用 _on 后缀（蓝色），当前状态为"关闭/已锁"时用无后缀（黑色）
        if ("解锁".equals(text)) {
            return "ic_car_index_lock";
        } else if ("锁车".equals(text)) {
            return "ic_car_index_lock_on";
        } else if ("寻车".equals(text)) {
            return "ic_car_index_find_car";
        } else if ("点火".equals(text)) {
            return "ic_car_index_engine";
        } else if ("熄火".equals(text)) {
            return "ic_car_index_engine_on";
        } else if ("打开空调".equals(text)) {
            return "ic_ac_unit";
        } else if ("关闭空调".equals(text)) {
            return "ic_ac_unit_on";
        } else if ("开窗".equals(text)) {
            return "ic_car_index_open_window";
        } else if ("关窗".equals(text)) {
            return "ic_car_index_open_window_on";
        } else if ("透气".equals(text)) {
            return isWindowOpen ? "ic_car_index_wind_on" : "ic_car_index_wind";
        } else if ("尾箱".equals(text) || "后备箱".equals(text) || "开后备箱".equals(text)) {
            return isTrunkOpen ? "ic_car_index_trunk_on" : "ic_car_index_trunk";
        } else if ("打开座椅加热".equals(text)) {
            return "ic_seat_heating";
        } else if ("关闭座椅加热".equals(text)) {
            return "ic_seat_heating_on";
        } else if ("主驾加热".equals(text)) {
            return "ic_seat_heating_driver";
        } else if ("关闭主驾加热".equals(text)) {
            return "ic_seat_heating_driver_on";
        } else if ("副驾加热".equals(text)) {
            return "ic_seat_heating_passenger";
        } else if ("关闭副驾加热".equals(text)) {
            return "ic_seat_heating_passenger_on";
        }
        return null;
    }

    /**
     * 根据按钮文本获取图标资源 ID（drawable）；无映射或资源缺失时返回 0。
     */
    private int getIconResourceId(String text) {
        String resourceName = getIconResourceName(text);
        if (resourceName == null) {
            LogHelper.w(TAG, "⚠️ 未找到功能对应的资源映射: " + text);
            return 0;
        }
        int resId = getResources().getIdentifier(resourceName, "drawable", getPackageName());
        if (resId == 0) {
            LogHelper.w(TAG, "⚠️ 图标资源未找到: " + resourceName + " (功能: " + text + ")");
        } else {
            boolean hasOn = hasOnSuffix(resourceName);
            LogHelper.d(TAG, "✅ [图标资源] 功能=" + text + ", 资源名=" + resourceName + ", ID=" + resId + ", 有_on后缀=" + hasOn);
        }
        return resId;
    }
    
    /**
     * 根据资源名判断是否有 _on 后缀或是否为开启状态
     * _on 后缀表示当前状态为开启/未锁，应用蓝色；无后缀表示当前状态为关闭/已锁，应用黑色
     */
    private boolean hasOnSuffix(String resourceName) {
        if (resourceName == null) return false;
        // _on 后缀表示当前状态为开启/未锁，应用蓝色
        return resourceName.endsWith("_on");
    }
    
    /**
     * 根据资源ID获取资源名
     */
    private String getResourceName(int resId) {
        if (resId == 0) return null;
        try {
            return getResources().getResourceEntryName(resId);
        } catch (Exception e) {
            LogHelper.w(TAG, "获取资源名失败: " + resId, e);
            return null;
        }
    }
    
    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected();
        } catch (Exception e) {
            LogHelper.e(TAG, "检查网络连接失败", e);
            return false;
        }
    }
    
    /**
     * 判断是否为开启状态（降级方案，当无法获取资源名时使用）
     * 按钮文本表示当前状态，当前状态为"开启/未锁"时显示蓝色图标，当前状态为"关闭/已锁"时显示黑色图标
     * 注意：正常情况下应该根据资源名是否有 _on 后缀来判断，此方法仅作为降级方案
     */
    private boolean isOpenState(String text) {
        if (text == null) return false;
        
        // 按钮文本表示当前状态，以下按钮表示当前状态为"开启/未锁"，应显示蓝色图标
        // "锁车"表示当前未锁（开启状态），"关闭空调"表示当前空调打开（开启状态），"关窗"表示当前车窗打开（开启状态）
        return "锁车".equals(text) ||  // 锁车按钮表示当前未锁（开启状态）
               "熄火".equals(text) ||  // 熄火按钮表示当前已点火（开启状态）
               "关闭空调".equals(text) ||  // 关闭空调按钮表示当前空调打开（开启状态）
               "关闭座椅加热".equals(text) ||  // 关闭座椅加热按钮表示当前打开（开启状态）
               "关闭主驾加热".equals(text) ||  // 关闭主驾加热按钮表示当前打开（开启状态）
               "关闭副驾加热".equals(text) ||  // 关闭副驾加热按钮表示当前打开（开启状态）
               "关窗".equals(text) ||  // 关窗按钮表示当前车窗打开（开启状态）
               ("透气".equals(text) && isVentMode) ||  // 透气按钮：仅透气模式（一条缝）时显示开启状态
               (("尾箱".equals(text) || "后备箱".equals(text) || "开后备箱".equals(text)) && isTrunkOpen);
    }
    
    /** 在主线程设置车模位图（清除 tint），供自定义图 / assets PNG 使用。 */
    private void applyCarModelImageBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        Runnable r = () -> {
            if (isFinishing() || isDestroyed() || carModelImage == null) {
                return;
            }
            carModelImage.clearColorFilter();
            carModelImage.setImageBitmap(bitmap);
        };
        if (mainHandler != null) {
            mainHandler.post(r);
        } else {
            r.run();
        }
    }

    /** 矢量等资源需用 Drawable，{@link BitmapFactory#decodeResource} 无法解码 vector。 */
    private void applyCarModelImageDrawable(Drawable drawable) {
        if (drawable == null) {
            return;
        }
        final Drawable d = drawable.mutate();
        Runnable r = () -> {
            if (isFinishing() || isDestroyed() || carModelImage == null) {
                return;
            }
            carModelImage.clearColorFilter();
            carModelImage.setImageDrawable(d);
        };
        if (mainHandler != null) {
            mainHandler.post(r);
        } else {
            r.run();
        }
    }

    /**
     * 加载车模：自定义路径 → assets/car/xingrui.webp → {@code R.drawable.xingrui}（矢量须用 Drawable，勿 decodeResource）。
     */
    private void loadCarModelImage() {
        try {
            // 首先尝试加载自定义车模图片
            SharedPreferences prefs = getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE);
            String customCarModelPath = prefs.getString("car_model_path", null);
            
            if (customCarModelPath != null && !customCarModelPath.isEmpty()) {
                File customCarModelFile = new File(customCarModelPath);
                if (customCarModelFile.exists() && customCarModelFile.isFile()) {
                    LogHelper.d(TAG, "📷 尝试加载自定义车模图片: " + customCarModelPath);
                    loadCarModelImageFromFile(customCarModelFile);
                    return;
                } else {
                    LogHelper.w(TAG, "⚠️ 自定义车模图片不存在: " + customCarModelPath);
                }
            }
            
            // 如果没有自定义车模图片，优先 assets/car/xingrui.webp，再 res/drawable
            String carAssetPath = CarControlAssets.webpPath("xingrui");
            if (CarControlAssets.exists(this, carAssetPath)) {
                Bitmap assetBitmap = CarControlAssets.decodeBitmap(this, carAssetPath);
                if (assetBitmap != null) {
                    LogHelper.d(TAG, "✓ 车模图片加载成功（assets/car/xingrui.webp，尺寸: " + assetBitmap.getWidth() + "x" + assetBitmap.getHeight() + "）");
                    applyCarModelImageBitmap(assetBitmap);
                    return;
                }
            }

            android.content.res.Resources resources = getResources();
            int resourceId = resources.getIdentifier("xingrui", "drawable", getPackageName());
            
            if (resourceId == 0) {
                LogHelper.w(TAG, "⚠️ 资源ID不存在，尝试从 assets 加载");
                loadCarModelImageFromAssets();
                return;
            }

            Drawable dr = AppCompatResources.getDrawable(this, resourceId);
            if (dr != null) {
                LogHelper.d(TAG, "✓ 车模图片加载成功（drawable 资源，含矢量）");
                applyCarModelImageDrawable(dr);
                return;
            }

            LogHelper.w(TAG, "⚠️ getDrawable(xingrui) 失败，尝试 assets");
            loadCarModelImageFromAssets();
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 加载车模图片失败，尝试从 assets 加载", e);
            loadCarModelImageFromAssets();
        }
    }
    
    /**
     * 从文件加载车模图片（自定义车模图片）
     */
    private void loadCarModelImageFromFile(File imageFile) {
        try {
            FileInputStream fis = new FileInputStream(imageFile);
            
            // 使用高质量的图片解码选项
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; // 使用最高质量配置
            options.inScaled = false; // 不进行密度缩放
            options.inDither = false; // 不使用抖动
            options.inSampleSize = 1; // 不使用采样，加载完整分辨率
            
            Bitmap bitmap = BitmapFactory.decodeStream(fis, null, options);
            fis.close();
            
            if (bitmap != null) {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                
                LogHelper.d(TAG, "✓ 自定义车模图片加载成功（文件方式，尺寸: " + width + "x" + height + "，让ImageView自动缩放）");
                applyCarModelImageBitmap(bitmap);
            } else {
                LogHelper.w(TAG, "⚠️ 自定义车模图片解码失败，使用默认图片");
                loadCarModelImageFromAssets();
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 从文件加载自定义车模图片失败，使用默认图片", e);
            loadCarModelImageFromAssets();
        }
    }
    
    /**
     * 从assets加载车模图片（备用方案）
     */
    private void loadCarModelImageFromAssets() {
        String carPath = CarControlAssets.webpPath("xingrui");
        try (InputStream is = CarControlAssets.exists(this, carPath)
                ? getAssets().open(carPath)
                : getAssets().open("car/xingrui.webp")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            options.inDither = false;
            options.inSampleSize = 1;

            Bitmap originalBitmap = BitmapFactory.decodeStream(is, null, options);
            if (originalBitmap != null) {
                LogHelper.d(TAG, "✓ 车模图片加载成功（assets，尺寸: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight() + "）");
                applyCarModelImageBitmap(originalBitmap);
            } else {
                LogHelper.w(TAG, "⚠ 车模图片解码失败（assets）");
            }
        } catch (IOException e) {
            LogHelper.e(TAG, "❌ 从 assets 加载车模图片失败", e);
        }
    }
    
    /**
     * 加载按钮配置
     */
    private void loadButtonConfig() {
        android.content.SharedPreferences prefs = getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE);
        String buttonsJson = prefs.getString("rear_buttons_order", null);
        
        // 确保buttonFunctions数组已初始化
        if (buttonFunctions == null) {
            buttonFunctions = new String[8];
        }
        
        // 先清空数组
        for (int i = 0; i < 8; i++) {
            buttonFunctions[i] = null;
        }
        
        if (buttonsJson != null && !buttonsJson.isEmpty()) {
            try {
                org.json.JSONArray jsonArray = new org.json.JSONArray(buttonsJson);
                LogHelper.d(TAG, "📋 从JSON加载按钮配置，数组长度=" + jsonArray.length());
                for (int i = 0; i < 8 && i < jsonArray.length(); i++) {
                    String func = jsonArray.getString(i);
                    buttonFunctions[i] = convertButtonName(func);
                    LogHelper.d(TAG, "  [" + i + "] = " + buttonFunctions[i]);
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "加载按钮配置失败", e);
                buttonFunctions = new String[]{"解锁", "寻车", "尾箱", "开窗", null, null, null, null};
            }
        } else {
            java.util.Set<String> buttons = prefs.getStringSet("rear_buttons", null);
            if (buttons != null && !buttons.isEmpty()) {
                String[] buttonArray = buttons.toArray(new String[0]);
                LogHelper.d(TAG, "📋 从StringSet加载按钮配置，Set大小=" + buttons.size() + "，数组长度=" + buttonArray.length);
                for (int i = 0; i < 8 && i < buttonArray.length; i++) {
                    buttonFunctions[i] = convertButtonName(buttonArray[i]);
                    LogHelper.d(TAG, "  [" + i + "] = " + buttonFunctions[i]);
                }
            } else {
                LogHelper.d(TAG, "📋 使用默认按钮配置");
                buttonFunctions = new String[]{"解锁", "寻车", "尾箱", "开窗", null, null, null, null};
            }
        }
        
        // 打印最终配置
        LogHelper.d(TAG, "📋 最终按钮配置:");
        for (int i = 0; i < 8; i++) {
            LogHelper.d(TAG, "  [" + i + "] = " + (buttonFunctions[i] != null ? buttonFunctions[i] : "null"));
        }
    }
    
    private static final String KEY_AC_STATUS = "ac_status";
    private static final String KEY_SEAT_HEATING_STATUS = "seat_heating_status";
    
    // 车控参数配置键（与SettingsActivity保持一致）
    private static final String CAR_CONTROL_PREFS = "CarControlPrefs";
    private static final String KEY_AC_DURATION = "ac_duration";  // 空调时长（分钟）
    private static final String KEY_AC_TEMPERATURE = "ac_temperature";  // 空调温度（℃）
    private static final String KEY_SEAT_HEATING_DURATION = "seat_heating_duration";  // 座椅加热时长（分钟）
    private static final String KEY_SEAT_HEATING_LEVEL = "seat_heating_level";  // 座椅加热等级（1或2）
    private static final String KEY_IS_TRUNK_OPEN = "is_trunk_open";
    private static final String KEY_IS_VENT_MODE = "is_vent_mode";
    
    // 默认参数值（与SettingsActivity保持一致）
    private static final int DEFAULT_AC_DURATION = 10;  // 默认10分钟
    private static final int DEFAULT_AC_TEMPERATURE = 22;  // 默认22℃
    private static final int DEFAULT_SEAT_HEATING_DURATION = 10;  // 默认10分钟
    private static final int DEFAULT_SEAT_HEATING_LEVEL = 1;  // 默认1级
    
    private int currentPage = 0;
    private int totalPages = 1;
    private float touchStartX = 0;
    private float touchStartY = 0;
    private boolean isSwipeGesture = false;
    private static final float SWIPE_THRESHOLD = 30;
    private static final float SWIPE_VELOCITY_THRESHOLD = 100;
    
    /**
     * 设置ViewPager2
     */
    private void setupViewPager() {
        if (buttonPageContainer == null) {
            LogHelper.e(TAG, "❌ buttonPageContainer为null，无法设置ViewPager2");
            return;
        }
        
        if (buttonFunctions == null) {
            LogHelper.e(TAG, "❌ buttonFunctions为null，无法设置ViewPager2");
            return;
        }
        
        int buttonCount = 0;
        for (String func : buttonFunctions) {
            if (func != null && !func.isEmpty()) {
                buttonCount++;
            }
        }
        
        if (buttonCount == 0) {
            LogHelper.w(TAG, "⚠️ 没有配置按钮，跳过ViewPager2设置");
            return;
        }
        
        // 根据按钮数量计算页数（每页4个按钮）
        totalPages = (buttonCount + 3) / 4; // 向上取整
        if (totalPages < 1) {
            totalPages = 1;
        }
        if (totalPages > 2) {
            totalPages = 2; // 最多2页
        }
        currentPage = 0;
        
        LogHelper.d(TAG, "📊 按钮统计: 总数=" + buttonCount + "，计算页数=" + totalPages + "，当前页=" + currentPage);
        
        // 如果只有一页，记录警告
        if (totalPages <= 1) {
            LogHelper.w(TAG, "⚠️ 只有一页按钮，滑动切换功能将不可用");
        }
        
        LogHelper.d(TAG, "📄 设置ViewPager2，按钮总数=" + buttonCount + "，总页数=" + totalPages);
        LogHelper.d(TAG, "📄 buttonFunctions数组内容:");
        for (int i = 0; i < buttonFunctions.length; i++) {
            LogHelper.d(TAG, "  [" + i + "] = " + (buttonFunctions[i] != null ? buttonFunctions[i] : "null"));
        }
        
        ButtonPagerAdapter adapter = new ButtonPagerAdapter(this, buttonFunctions);
        buttonPageContainer.setAdapter(adapter);
        buttonPageContainer.setCurrentItem(0, false);
        
        // 按钮区域不再支持滑动切换，仅保留车模图片的滑动切换功能
        
        LogHelper.d(TAG, "✅ ViewPager2设置完成");
        // 显式设置 ViewPager2 高度（WRAP_CONTENT 在 ViewPager2 中测量不准，会导致按钮图标被裁切）
        float density = getResources().getDisplayMetrics().density;
        int totalButtonHeight = (int)(50 * density);
        ViewGroup.LayoutParams vpParams = buttonPageContainer.getLayoutParams();
        if (vpParams != null) {
            vpParams.height = totalButtonHeight;
            buttonPageContainer.setLayoutParams(vpParams);
        }
        LogHelper.d(TAG, "\uD83D\uDCCF 按钮容器高度已设置: " + totalButtonHeight + "px (40dp + 20px padding)");
    }
    
    /**
     * 在车模图片上设置滑动监听，用于切换按钮页面（循环切换）
     */
    private void setupCarImageSwipeListener() {
        if (carModelImage == null) {
            LogHelper.e(TAG, "❌ carModelImage为null，无法设置滑动监听");
            return;
        }
        
        LogHelper.d(TAG, "🔧 开始设置车模图片滑动监听，totalPages=" + totalPages);
        
        carModelImage.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        touchStartX = event.getX();
                        touchStartY = event.getY();
                        isSwipeGesture = false;
                        LogHelper.d(TAG, "👆 车模图片触摸开始: x=" + touchStartX + ", y=" + touchStartY);
                        return true; // 返回true以接收后续事件
                    case android.view.MotionEvent.ACTION_MOVE:
                        float moveDeltaX = Math.abs(event.getX() - touchStartX);
                        float moveDeltaY = Math.abs(event.getY() - touchStartY);
                        // 检测任何方向的滑动（不区分左右）
                        if (moveDeltaX > SWIPE_THRESHOLD || moveDeltaY > SWIPE_THRESHOLD) {
                            if (!isSwipeGesture) {
                                LogHelper.d(TAG, "🔄 检测到滑动开始: moveDeltaX=" + moveDeltaX + ", moveDeltaY=" + moveDeltaY);
                            }
                            isSwipeGesture = true;
                            return true;
                        }
                        return isSwipeGesture;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        LogHelper.d(TAG, "👆 车模图片触摸结束: isSwipeGesture=" + isSwipeGesture + ", totalPages=" + totalPages);
                        if (isSwipeGesture) {
                            float touchEndX = event.getX();
                            float touchEndY = event.getY();
                            float upDeltaX = touchEndX - touchStartX;
                            float upDeltaY = touchEndY - touchStartY;
                            
                            // 计算滑动的总距离（不区分方向）
                            float totalDelta = (float) Math.sqrt(upDeltaX * upDeltaX + upDeltaY * upDeltaY);
                            
                            LogHelper.d(TAG, "🔄 检测到滑动: totalDelta=" + totalDelta + ", SWIPE_THRESHOLD=" + SWIPE_THRESHOLD + ", currentPage=" + currentPage + ", totalPages=" + totalPages);
                            
                            // 只要滑动距离超过阈值，就切换页面（循环切换）
                            if (totalDelta > SWIPE_THRESHOLD) {
                                if (totalPages > 1) {
                                    // 切换到下一页（循环）
                                    currentPage = (currentPage + 1) % totalPages;
                                    LogHelper.d(TAG, "🔄 循环切换到第" + currentPage + "页");
                                    if (buttonPageContainer != null) {
                                        buttonPageContainer.setCurrentItem(currentPage, true);
                                        LogHelper.d(TAG, "✅ 已调用setCurrentItem切换到第" + currentPage + "页");
                                    } else {
                                        LogHelper.e(TAG, "❌ buttonPageContainer为null，无法切换页面");
                                    }
                                } else {
                                    LogHelper.w(TAG, "⚠️ totalPages=" + totalPages + "，只有一页，无需切换");
                                }
                                isSwipeGesture = false;
                                return true;
                            } else {
                                LogHelper.d(TAG, "⏭️ 滑动距离不足: totalDelta=" + totalDelta + " <= " + SWIPE_THRESHOLD);
                            }
                        } else {
                            LogHelper.d(TAG, "⏭️ 不是滑动手势，可能是点击");
                        }
                        isSwipeGesture = false;
                        return false;
                }
                return false;
            }
        });
        LogHelper.d(TAG, "✅ 车模图片滑动监听已设置（循环切换模式），totalPages=" + totalPages);
    }
    
    /**
     * 切换到下一页（供车模图片滑动调用）
     */
    public void switchToNextPage() {
        if (totalPages > 1) {
            currentPage = (currentPage + 1) % totalPages;
            LogHelper.d(TAG, "🔄 切换到第" + currentPage + "页");
            if (buttonPageContainer != null) {
                buttonPageContainer.setCurrentItem(currentPage, true);
            }
        }
    }
    
    /**
     * 转换按钮名称（处理合并后的按钮）
     */
    private String convertButtonName(String name) {
        android.content.SharedPreferences prefs = getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE);
        
        if ("锁车/解锁".equals(name)) {
            return "解锁";
        } else if ("开窗/关窗".equals(name)) {
            return "开窗";
        } else if ("点火/熄火".equals(name)) {
            return "点火";
        } else if ("空调".equals(name)) {
            boolean acOn = prefs.getBoolean(KEY_AC_STATUS, false);
            return acOn ? "关闭空调" : "打开空调";
        } else if ("座椅加热".equals(name)) {
            boolean seatHeatingOn = prefs.getBoolean(KEY_SEAT_HEATING_STATUS, false);
            return seatHeatingOn ? "关闭座椅加热" : "打开座椅加热";
        } else if ("开后备箱".equals(name)) {
            return "尾箱";
        }
        return name;
    }
    
    /** 刷新进行中：顺时针匀速旋转图标（无 Material 波纹） */
    private void startRefreshIconRotation() {
        if (refreshIcon == null) {
            return;
        }
        refreshIcon.animate().cancel();
        if (refreshIconRotationAnimator != null) {
            refreshIconRotationAnimator.cancel();
            refreshIconRotationAnimator = null;
        }
        refreshIcon.setRotation(0f);
        refreshIconRotationAnimator = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f);
        refreshIconRotationAnimator.setDuration(900);
        refreshIconRotationAnimator.setInterpolator(new LinearInterpolator());
        refreshIconRotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        refreshIconRotationAnimator.start();
    }

    private void stopRefreshIconRotation() {
        if (refreshIconRotationAnimator != null) {
            refreshIconRotationAnimator.cancel();
            refreshIconRotationAnimator = null;
        }
        if (refreshIcon != null) {
            refreshIcon.animate().cancel();
            refreshIcon.setRotation(0f);
        }
    }

    /**
     * 初始化车辆信息
     */
    private void initCarInfo() {
        // 防重复调用机制
        if (isRefreshingCarInfo) {
            LogHelper.d(TAG, "⚠️ 车辆信息正在刷新中，跳过重复调用");
            return;
        }
        
        isRefreshingCarInfo = true;
        startRefreshIconRotation();
        
        // 显示加载状态（歌词视图不需要显示加载状态）
        
        // 在后台线程获取真实车辆数据
        new Thread(() -> {
            try {
                VehicleStatusService.VehicleStatusInfo statusInfo = VehicleStatusService.getVehicleStatus(this);
                
                int rangeValue = VehicleStatusService.distanceToEmptyKmForHud(
                        VehicleStatusService.parseDistanceToEmptyKm(statusInfo.distanceToEmpty));
                int fuelPercent = VehicleStatusService.fuelLevelPercentForHud(
                        VehicleStatusService.parseFuelLevelPercent(statusInfo.fuelLevelStatus));
                String odometer = VehicleStatusService.formatOdometerKmDisplayOrUnknown(statusInfo.odometer);
                String interiorTemp = VehicleStatusService.formatTempCelsiusDigitsOrUnknown(statusInfo.interiorTemp);
                String exteriorTemp = VehicleStatusService.formatTempCelsiusDigitsOrUnknown(statusInfo.exteriorTemp);
                String updateTimeStr = VehicleStatusService.formatUpdateTimeShortHhMm(
                        statusInfo.updateDateTime, "未知");
                if (statusInfo.updateDateTime != null && !statusInfo.updateDateTime.isEmpty()
                        && !"未知".equals(statusInfo.updateDateTime)
                        && !"时间格式错误".equals(statusInfo.updateDateTime)) {
                    LogHelper.d(TAG, "解析更新时间: " + statusInfo.updateDateTime + " -> " + updateTimeStr);
                }
                
                // 解析锁车状态、车窗状态和后备箱状态（用于更新按钮文本和背景色）
                // 使用翻译方法解析锁车状态（1=已锁，其他=未锁）
                final String lockStatus = VehicleStatusService.translateDoorLockStatus(statusInfo.doorLockStatusDriver);
                // 车窗/天窗已在 getVehicleStatus 中从接口原始值翻译为「已开/已关」，勿再 translate（否则会误判为已关）
                final String windowStatus = statusInfo.winStatusDriver != null ? statusInfo.winStatusDriver : "未知";
                final String sunroofStatus = statusInfo.sunroofOpenStatus != null ? statusInfo.sunroofOpenStatus : "未知";
                
                // 解析后备箱状态（使用翻译方法）
                final String trunkStatus = VehicleStatusService.translateTrunkStatus(statusInfo.trunkOpenStatus);
                final boolean trunkOpen = "已开".equals(trunkStatus);
                
                LogHelper.d(TAG, "🔒 锁车状态: " + statusInfo.doorLockStatusDriver + " -> " + lockStatus + 
                      ", 车窗状态: " + statusInfo.winPosDriver + " -> " + windowStatus + 
                      ", 天窗状态: " + statusInfo.sunroofPos + " -> " + sunroofStatus + 
                      ", 后备箱状态: " + statusInfo.trunkOpenStatus + " -> " + trunkStatus);
                
                // 将变量声明为final以便在lambda中使用
                final int range = rangeValue;
                final int fuel = fuelPercent;
                final String odometerStr = odometer;
                final String interiorTempStr = interiorTemp;
                final String exteriorTempStr = exteriorTemp;
                final String updateTime = updateTimeStr;
                final VehicleStatusService.VehicleStatusInfo finalStatusInfo = statusInfo;

                // 在主线程更新UI
                if (mainHandler != null) {
                    mainHandler.post(() -> {
                        isRefreshingCarInfo = false; // 重置标志
                        stopRefreshIconRotation();
                        if (!isFinishing() && !isDestroyed()) {
                            isTrunkOpen = trunkOpen; // 更新后备箱状态
                            isWindowOpen = "已开".equals(windowStatus); // 更新车窗状态
                            // 解析透气模式：winPosDriver 非0且未全开=透气（一条缝）
                            isVentMode = false;
                            if (finalStatusInfo != null && finalStatusInfo.winPosDriver != null) {
                                try {
                                    int pos = Integer.parseInt(finalStatusInfo.winPosDriver);
                                    isVentMode = pos > 0 && pos <= 50;
                                } catch (NumberFormatException ignored) {}
                            }

                            // 持久化后备箱/透气状态
                            SharedPreferences prefs = getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean(KEY_IS_TRUNK_OPEN, isTrunkOpen)
                                .putBoolean(KEY_IS_VENT_MODE, isVentMode)
                                .apply();

                            // 同步 CarButtonStateManager（会触发 onButtonStateChanged 回调更新按钮 UI）
                            if (carButtonStateManager != null) {
                                carButtonStateManager.syncFromRemote(finalStatusInfo, prefs);
                            }

                            updateCarInfo(range, fuel, odometerStr, interiorTempStr, exteriorTempStr, updateTime, lockStatus, windowStatus);
                        }
                    });
                } else {
                    isRefreshingCarInfo = false; // 重置标志
                    runOnUiThread(this::stopRefreshIconRotation);
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 获取车辆信息失败", e);
                // 先检查网络是否可用
                boolean networkAvailable = isNetworkAvailable();
                if (mainHandler != null) {
                    mainHandler.post(() -> {
                        isRefreshingCarInfo = false; // 重置标志
                        stopRefreshIconRotation();
                        if (!isFinishing() && !isDestroyed()) {
                            if (!networkAvailable) {
                                // 网络不可用，显示网络错误提示，使用默认值
                                LogHelper.w(TAG, "⚠️ 获取车辆信息失败：网络不可用");
                                // updateTimeText已移除
                                updateCarInfo(0, 0, "未知", "未知", "未知", "未知", "未知", "未知");
                            } else {
                                // 网络可用，检查登录是否失效（如果API返回401/403，登录会被标记为失效）
                                boolean isMarkedInvalid = LoginService.isLoginMarkedInvalid(RearScreenCarControlActivity.this);
                                if (isMarkedInvalid) {
                                    LogHelper.d(TAG, "⚠️ 获取车辆信息失败，登录已失效，跳转到登录界面");
                                    Intent intent = new Intent(RearScreenCarControlActivity.this, CarControlLoginActivity.class);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(intent);
                                    finishProjectionTask();
                                } else {
                                    // 网络可用但登录未失效，可能是其他错误，使用默认值
                                    LogHelper.w(TAG, "⚠️ 获取车辆信息失败：网络可用但获取失败，可能是服务器错误");
                                    // updateTimeText已移除
                                    updateCarInfo(0, 0, "未知", "未知", "未知", "未知", "未知", "未知");
                                }
                            }
                        }
                    });
                } else {
                    isRefreshingCarInfo = false; // 重置标志
                    runOnUiThread(this::stopRefreshIconRotation);
                }
            }
        }).start();
    }
    
    /**
     * 更新车辆信息
     * @param lockStatus 锁车状态（doorLockStatusDriver，小写）
     * @param windowStatus 车窗状态（winStatusDriver，小写）
     */
    private void updateCarInfo(int range, int fuelPercent, String odometer, String interiorTemp, 
                                String exteriorTemp, String updateTime, String lockStatus, String windowStatus) {
        // 更新续航里程
        if (rangeText != null) {
            rangeText.setText(range + " km");
        }

        cachedFuelPercentForUi = fuelPercent;
        if (fuelPercentText != null) {
            fuelPercentText.setText(fuelPercent + "%");
        }
        
        // 更新总里程
        if (odometerText != null) {
            if (!"未知".equals(odometer) && !odometer.isEmpty()) {
                odometerText.setText("总里程: " + odometer);
            } else {
                odometerText.setText("总里程: --");
            }
        }
        
        // 更新车内温度
        if (interiorTempText != null) {
            if (!"未知".equals(interiorTemp) && !interiorTemp.isEmpty()) {
                interiorTempText.setText("🌡️ 车内: " + interiorTemp + "℃");
            } else {
                interiorTempText.setText("🌡️ 车内: --℃");
            }
        }
        
        // 更新车外温度
        if (exteriorTempText != null) {
            if (!"未知".equals(exteriorTemp) && !exteriorTemp.isEmpty()) {
                exteriorTempText.setText("🌡️ 车外: " + exteriorTemp + "℃");
            } else {
                exteriorTempText.setText("🌡️ 车外: --℃");
            }
        }
        
        if (fuelProgressBar != null) {
            fuelProgressBar.setProgress(fuelPercent);
        }
        applyFuelPercentVisuals(fuelPercent);

        // 更新时间（投屏顶栏仅 HH:mm，与占位 --:-- 同量级，避免刷新后突然变长）
        if (updateTimeText != null) {
            if (!"未知".equals(updateTime) && !updateTime.isEmpty() && !"--:--".equals(updateTime)) {
                updateTimeText.setText(updateTime);
                LogHelper.d(TAG, "✅ 更新时间已更新: " + updateTime);
            } else {
                updateTimeText.setText("--:--");
                LogHelper.w(TAG, "⚠️ 更新时间无效，显示默认值: updateTime=" + updateTime);
            }
            updateTimeText.setTextColor(colorCarUpdateTime);
        }
        
        // 更新车窗状态关联的透气按钮背景色
        for (int i = 0; i < 8; i++) {
            if (carButtonStateManager != null && carButtonStateManager.getFunctionKey(i) == CarButtonInfo.FunctionKey.VENTILATE) {
                updateButtonBackground(i, "透气");
                break;
            }
        }

        LogHelper.d(TAG, "✅ 车辆信息已更新 - 续航: " + range + "km, 油量: " + fuelPercent + "%, 总里程: " + odometer + "km, 车内: " + interiorTemp + "℃, 车外: " + exteriorTemp + "℃");
    }
    
    /**
     * 按住达到时长即执行车控（无需松手）；仅支持按住触发，无单击。
     */
    private static final long CAR_CONTROL_HOLD_TO_EXECUTE_MS = 1200;

    private void setupButtons() {
        for (int i = 0; i < controlButtons.length; i++) {
            if (controlButtons[i] != null) {
                setupButtonLongPress(controlButtons[i], i);
            }
        }
    }
    
    /**
     * 按住达到 {@link #CAR_CONTROL_HOLD_TO_EXECUTE_MS} 毫秒即触发执行；未到时间抬起则取消。
     * 整段手势消费触摸事件，避免 Android 16 / 小米等机型上单击穿透或重复触发。
     */
    public void setupButtonLongPress(Button button, final int index) {
        if (button == null) {
            LogHelper.w(TAG, "⚠️ 按钮为null，无法设置长按监听，索引: " + index);
            return;
        }
        
        if (mainHandler == null) {
            LogHelper.e(TAG, "❌ mainHandler为null，无法设置按钮长按监听，索引: " + index);
            return;
        }

        final int moveSlop = Math.max(
                ViewConfiguration.get(button.getContext()).getScaledTouchSlop() * 3,
                (int) (16 * button.getResources().getDisplayMetrics().density + 0.5f));
        
        final Runnable[] holdRunnable = {null};
        final float[] downX = {0};
        final float[] downY = {0};
        final boolean[] gestureActive = {false};
        
        button.setOnClickListener(null);
        button.setLongClickable(false);
        button.setHapticFeedbackEnabled(false);
        
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    gestureActive[0] = true;
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    // 按下视觉反馈
                    button.setAlpha(0.6f);
                    // 按下不震动，长按触发时才震
                    if (holdRunnable[0] != null) {
                        mainHandler.removeCallbacks(holdRunnable[0]);
                        holdRunnable[0] = null;
                    }
                    holdRunnable[0] = () -> {
                        holdRunnable[0] = null;
                        if (isFinishing() || isDestroyed() || !gestureActive[0]) {
                            return;
                        }
                        if (carButtonStateManager != null && carButtonStateManager.get(index) != null) {
                            LogHelper.d(TAG, "🔘 按住触发: 索引=" + index + ", 按住=" + CAR_CONTROL_HOLD_TO_EXECUTE_MS + "ms)");
                            // 长按触发时恢复透明度
                            button.setAlpha(1.0f);
                            // 长按触发短震动 + 开始执行
                            vibrate(VIBRATE_SUCCESS_TAP_MS);
                            showToast("执行中");
                            SharedPreferences prefs = getSharedPreferences(CAR_CONTROL_PREFS, MODE_PRIVATE);
                            carButtonStateManager.execute(index, RearScreenCarControlActivity.this, prefs);
                        }
                    };
                    mainHandler.postDelayed(holdRunnable[0], CAR_CONTROL_HOLD_TO_EXECUTE_MS);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!gestureActive[0]) {
                        return false;
                    }
                    float dx = Math.abs(event.getX() - downX[0]);
                    float dy = Math.abs(event.getY() - downY[0]);
                    if (dx > moveSlop || dy > moveSlop) {
                        if (holdRunnable[0] != null) {
                            mainHandler.removeCallbacks(holdRunnable[0]);
                            holdRunnable[0] = null;
                        }
                        LogHelper.d(TAG, "👆 按钮[" + index + "]滑动超阈值，取消按住触发 dx=" + dx + " dy=" + dy + " slop=" + moveSlop);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!gestureActive[0]) {
                        return false;
                    }
                    gestureActive[0] = false;
                    // 恢复视觉状态
                    button.setAlpha(1.0f);
                    if (holdRunnable[0] != null) {
                        mainHandler.removeCallbacks(holdRunnable[0]);
                        holdRunnable[0] = null;
                    }
                    return true;

                default:
                    return gestureActive[0];
            }
        });
        
        LogHelper.d(TAG, "✅ 按钮[" + index + "]按住触发已设置（" + CAR_CONTROL_HOLD_TO_EXECUTE_MS + "ms，无需抬起）");
    }
    
    /** 执行成功：单击 / 长按 震动时长（与 {@link VibrationHelper} 一致，体感明显） */
    private static final long VIBRATE_SUCCESS_TAP_MS = 85;
    private static final long VIBRATE_SUCCESS_LONG_PRESS_MS = 500;
    /** 按下瞬间短震（略短于成功反馈） */
    private static final long VIBRATE_TOUCH_DOWN_MS = 45;

    /**
     * 震动反馈：统一走 {@link VibrationHelper}（固定强度 + Android 13+ USAGE_HARDWARE_FEEDBACK），
     * 避免 DEFAULT_AMPLITUDE 在部分机型上几乎无感。
     */
    private void vibrate(long durationMs) {
        VibrationHelper.vibrateOneShot(this, durationMs, "车控投屏震动失败");
    }

    /** 按下时短震 */
    private void vibrate() {
        vibrate(VIBRATE_TOUCH_DOWN_MS);
    }

    /**
     * 更新按钮文本和图标
     * @param buttonIndex 按钮索引
     * @param newText 新文本
     */
    private void updateButtonText(int buttonIndex, String newText) {
        if (buttonIndex >= 0 && buttonIndex < controlButtons.length && controlButtons[buttonIndex] != null) {
            Button button = controlButtons[buttonIndex];
            button.setText("");
            button.setContentDescription(newText);
            
            float density = getResources().getDisplayMetrics().density;
            int buttonSize = (int)(40 * density);
            int iconSize = (int)(32 * density);
            int iconInset = (buttonSize - iconSize) / 2;
            
            android.graphics.drawable.GradientDrawable bgDrawable = new android.graphics.drawable.GradientDrawable();
            bgDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bgDrawable.setColor(getButtonBackgroundColor(newText));
            
            android.graphics.drawable.Drawable icon = loadControlButtonIcon(newText, iconSize);
            android.graphics.drawable.Drawable[] layers;
            if (icon != null) {
                layers = new android.graphics.drawable.Drawable[]{bgDrawable, icon};
                LogHelper.d(TAG, "✅ 更新图标成功: " + newText);
            } else {
                layers = new android.graphics.drawable.Drawable[]{bgDrawable};
                LogHelper.w(TAG, "⚠️ 未找到图标资源: " + newText);
            }
            
            android.graphics.drawable.LayerDrawable layerDrawable = new android.graphics.drawable.LayerDrawable(layers);
            if (icon != null) layerDrawable.setLayerInset(1, iconInset, iconInset, iconInset, iconInset);
            button.setBackground(layerDrawable);
            
            LogHelper.d(TAG, "✅ 按钮[" + buttonIndex + "]图标已更新为: " + newText);
        } else {
            LogHelper.w(TAG, "⚠️ 按钮索引无效或按钮为null: " + buttonIndex);
        }
    }
    
    /**
     * 绑定TaskService
     */
    private void bindTaskService() {
        try {
            Intent intent = new Intent(this, RootTaskService.class);
            bindService(intent, taskServiceConnection, Context.BIND_AUTO_CREATE);
            LogHelper.d(TAG, "✓ 开始绑定TaskService（RootTaskService）");
        } catch (Exception e) {
            LogHelper.e(TAG, "绑定TaskService失败", e);
        }
    }
    
    /**
     * 背屏常亮总开关（与 [RearAssistPrefs] / 功能页一致）开启时注册 {@link RearScreenWakeService}；关闭时仅显示「投屏」通知。
     */
    private void applyCarControlKeepScreenWakeFromPrefs() {
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displayId = getDisplay().getDisplayId();
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }
        boolean keepOn = RearAssistPrefs.INSTANCE.isKeepScreenOnEnabled(this);
        if (displayId == 1 && mainLayout != null && !isFinishing()) {
            try {
                if (keepOn) {
                    ProjectionOnlyNotificationHelper.cancelCar(this);
                    RearScreenWakeManager.getInstance().startWakeService(
                            getApplicationContext(), RearScreenCarControlActivity.class);
                    RearScreenWakeService.requestNotificationRefresh(this);
                    LogHelper.d(TAG, "✅ 已注册车控投屏前台服务（合并通知）");
                } else {
                    RearScreenWakeManager.getInstance().stopWakeService(
                            getApplicationContext(), RearScreenCarControlActivity.class);
                    ProjectionOnlyNotificationHelper.showCar(this);
                    LogHelper.d(TAG, "✅ 车控投屏：仅显示通知（常亮关）");
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 注册背屏前台服务失败", e);
            }
        } else {
            LogHelper.d(TAG, "⏸️ 未注册背屏前台服务 (displayId=" + displayId + ", mainLayout=" + (mainLayout != null) + ", isFinishing=" + isFinishing() + ")");
        }
        if (displayId == 1 && mainLayout != null && !isFinishing() && !isDestroyed()) {
            setupWindow();
        }
        updateProjectionProximitySession();
    }

    /**
     * 背屏常亮总开关开启时注册 {@link RearScreenWakeService}（合并通知）；关闭时仅显示「投屏」通知。
     */
    private void startCarControlWakeService() {
        registerRearAssistProximityPreferenceListener();
        applyCarControlKeepScreenWakeFromPrefs();
    }
    
    /**
     * @param skipProximityUpdate 为 true 时不刷新投屏遮盖会话（退出清理路径）
     */
    private void stopCarControlWakeService(boolean skipProximityUpdate) {
        try {
            ProjectionOnlyNotificationHelper.cancelCar(this);
            RearScreenWakeManager.getInstance().stopWakeService(
                    getApplicationContext(), RearScreenCarControlActivity.class);
            if (!RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                getApplicationContext().stopService(
                        new Intent(getApplicationContext(), RearScreenWakeService.class));
            }
            LogHelper.d(TAG, "⏹ 背屏常亮服务已注销（车控投屏）");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 停止背屏常亮服务失败", e);
        }
        if (!skipProximityUpdate) {
            updateProjectionProximitySession();
        }
    }

    private void stopCarControlWakeService() {
        stopCarControlWakeService(false);
    }

    /** 功能页「投屏常亮」与 [RearAssistPrefs] 一致（手势/广播/按钮入口共用）。 */
    private boolean isProjectionKeepScreenOnEnabled() {
        return RearAssistPrefs.INSTANCE.isKeepScreenOnEnabled(this);
    }

    /**
     * 与 3.4 / {@link com.wmqc.miroot.car.ProjectionHelper} 一致：TaskService 就绪后再次
     * {@link #disableOfficialGesture()}（Session 已在投屏启动时禁用一次）。
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
            LogHelper.w(TAG, "⚠️ TaskService未连接，无法屏蔽官方手势服务，延迟200ms后重试");
            // 延迟重试
            if (mainHandler != null) {
                if (retryDisableGestureRunnable != null) {
                    mainHandler.removeCallbacks(retryDisableGestureRunnable);
                }
                retryDisableGestureRunnable = () -> {
                    if (!isFinishing() && !isDestroyed()) {
                        if (taskService != null && !isOfficialGestureDisabled) {
                            LogHelper.d(TAG, "🔄 重试屏蔽官方手势服务");
                            disableOfficialGesture();
                        } else if (taskService == null) {
                            LogHelper.w(TAG, "⚠️ 重试时TaskService仍未连接，放弃屏蔽");
                        }
                    }
                };
                mainHandler.postDelayed(retryDisableGestureRunnable, 200);
            }
            return;
        }
        
        // 检查Activity状态，避免在销毁后调用
        if (isFinishing() || isDestroyed()) {
            LogHelper.d(TAG, "⚠️ Activity已销毁，跳过屏蔽官方手势服务");
            return;
        }
        
        try {
            LogHelper.d(TAG, "🚫 开始屏蔽官方手势服务（com.xiaomi.subscreencenter）");
            // 使用disableSubScreenLauncher方法
            boolean success = ts.disableSubScreenLauncher();
            if (success) {
                isOfficialGestureDisabled = true;
                if (taskService == null) {
                    taskService = ts;
                }
                LogHelper.d(TAG, "✅ 已屏蔽官方手势服务（com.xiaomi.subscreencenter）");
                
                // 验证是否成功（延迟检查进程是否还在运行）
                if (mainHandler != null) {
                    mainHandler.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed() && taskService != null) {
                            try {
                                boolean isRunning = taskService.isLauncherProcessRunning();
                                if (!isRunning) {
                                    LogHelper.d(TAG, "✅ 验证成功：官方手势服务进程已停止");
                                } else {
                                    LogHelper.w(TAG, "⚠️ 验证失败：官方手势服务进程仍在运行");
                                }
                            } catch (Exception e) {
                                LogHelper.w(TAG, "验证屏蔽状态失败: " + e.getMessage());
                            }
                        }
                    }, 1000);
                }
            } else {
                LogHelper.e(TAG, "❌ disableSubScreenLauncher返回false");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 屏蔽官方手势服务失败", e);
            // 失败后延迟重试一次
            if (mainHandler != null) {
                if (retryDisableGestureRunnable != null) {
                    mainHandler.removeCallbacks(retryDisableGestureRunnable);
                }
                retryDisableGestureRunnable = () -> {
                    if (!isFinishing() && !isDestroyed() && taskService != null && !isOfficialGestureDisabled) {
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
                mainHandler.postDelayed(retryDisableGestureRunnable, 1000);
            }
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
            // TaskService未连接时，尝试重新绑定（延迟执行，避免阻塞）
            if (mainHandler != null) {
                if (retryEnableGestureRunnable != null) {
                    mainHandler.removeCallbacks(retryEnableGestureRunnable);
                }
                retryEnableGestureRunnable = () -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (taskService == null) {
                        LogHelper.d(TAG, "🔄 尝试重新绑定TaskService以恢复官方手势服务");
                        bindTaskService();
                        // 延迟后再次尝试恢复
                        if (mainHandler != null) {
                            mainHandler.postDelayed(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    if (taskService != null) {
                                        enableOfficialGesture();
                                    } else {
                                        LogHelper.w(TAG, "⚠️ TaskService重新绑定失败，标记为已恢复");
                                        // 即使TaskService未连接，也标记为已恢复，避免重复尝试
                                        isOfficialGestureDisabled = false;
                                    }
                                }
                            }, 500);
                        }
                    } else {
                        // TaskService已连接，直接恢复
                        enableOfficialGesture();
                    }
                };
                mainHandler.postDelayed(retryEnableGestureRunnable, 100);
            }
            return;
        }
        
        // 检查Activity状态，避免在销毁后调用
        if (isFinishing() || isDestroyed()) {
            LogHelper.d(TAG, "⚠️ Activity已销毁，跳过恢复官方手势服务");
            return;
        }
        
        try {
            // 使用enableSubScreenLauncher方法
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
    
    /**
     * 在主屏显示 Toast（背屏车控界面不占用背屏提示）。
     */
    private void showToast(String message) {
        if (mainHandler == null) {
            return;
        }
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                LogHelper.d(TAG, "⚠️ Activity已销毁，跳过显示Toast: " + message);
                return;
            }
            try {
                MainDisplayUi.showToast(this, message, Toast.LENGTH_SHORT);
            } catch (Exception e) {
                LogHelper.w(TAG, "⚠️ 显示Toast失败: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        // 检查是否是停止车控投屏的Intent
        if (intent != null && "ACTION_STOP_CAR_CONTROL".equals(intent.getAction())) {
            LogHelper.d(TAG, "🛑 onNewIntent中收到结束投屏请求，立即销毁Activity");
            try {
                // 使用统一的清理方法
                performCleanupAndExit();
                
                // 直接销毁Activity，避免在主屏显示
                finishProjectionTask();
                // 确保立即返回，不执行后续代码
                return;
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理结束投屏请求时发生异常", e);
                // 即使发生异常，也尝试清理资源并销毁Activity
                try {
                    // 确保静态实例被清除
                    currentInstance = null;
                    // 强制清理
                    if (!isFinishing()) {
                        performCleanupAndExit();
                        finishProjectionTask();
                    }
                } catch (Exception e2) {
                    LogHelper.e(TAG, "❌ 销毁Activity时发生异常", e2);
                    // 最后的保障：强制清除静态实例
                    currentInstance = null;
                    try {
                        finishProjectionTask();
                    } catch (Exception e3) {
                        LogHelper.e(TAG, "❌ 强制销毁Activity失败", e3);
                    }
                }
                return;
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        handleProjectionForegroundAfterStopGrace();
        LogHelper.d(TAG, "🔵 onResume");
        
        // 检查是否是停止车控投屏的Intent，或者Activity正在finishing
        if (RearMirootProjectionLifecycle.shouldSkipProjectionResume(
                isFinishing(), projectionExitFlowStarted, finishRequestedByMiRoot)) {
            LogHelper.d(TAG, "🛑 投屏结束中，跳过 onResume 后续逻辑");
            return;
        }
        
        if (getIntent() != null && "ACTION_STOP_CAR_CONTROL".equals(getIntent().getAction())) {
            LogHelper.d(TAG, "🛑 onResume中收到结束投屏请求");
            finishProjectionFromUser("onResume-stop-intent");
            return;
        }

        if (getDisplayIdSafe() == 1) {
            cancelMainScreenPlaceholderTimeout();
        }
        if (finishIfIllegalCarProjectionSurface("onResume")) {
            return;
        }

        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        if (getDisplayIdSafe() == 1) {
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
        }
        
        // 重新设置Display Cutout模式（防止切换画面时摄像头区域黑屏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(params);
        }
        
        // API 30+ 必须用 WindowInsetsController，勿再用 systemUiVisibility 拉沉浸式，否则会盖掉「保留手势区」策略
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getDisplayIdSafe() == 1 && !isFinishing() && !isDestroyed()) {
                RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
                hideSystemUI();
            }
        } else {
            View decorViewResume = getWindow().getDecorView();
            if (decorViewResume != null && getDisplayIdSafe() == 1) {
                int currentVisibility = decorViewResume.getSystemUiVisibility();
                int targetVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN;
                if (currentVisibility != targetVisibility) {
                    decorViewResume.setSystemUiVisibility(targetVisibility);
                }
            }
        }

        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displayId = getDisplay().getDisplayId();
            } catch (Exception e) {
                LogHelper.e(TAG, "获取displayId失败", e);
            }
        }

        int mainDisplayMode = RearMirootProjectionLifecycle.resolveMainDisplayProjectionMode(
                displayId, false, mainLayout != null);
        if (RearMirootProjectionLifecycle.applyMainDisplayPlaceholderPolicy(this, mainDisplayMode)) {
            if (mainDisplayMode == RearMirootProjectionLifecycle.MAIN_DISPLAY_MODE_MUST_END_PROJECTION) {
                LogHelper.w(TAG, "⚠️ 主屏禁止展示车控 UI，结束投屏");
                finishProjectionFromUser("main-display-no-ui-allowed");
            } else if (mainLayout == null) {
                LogHelper.d(TAG, "⏳ onResume 主屏透明占位，轮询迁背屏");
                schedulePollRearUiInitAfterMove();
            }
            return;
        }

        // 只有在背屏时才继续执行
        if (displayId == 1 && !isFinishing()) {
            cancelMainScreenPlaceholderTimeout();
            if (mainLayout == null) {
                initCarControlUiOnRearIfNeeded("onResume");
            } else {
                // UI已创建，重新应用常亮flags（确保屏幕不会熄灭）
                // 与未投放应用时的实现方式一致
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
                LogHelper.d(TAG, "✅ onResume: 已重新应用常亮flags");
            }
            
            // 启动汽车控制背屏常亮服务（根据设置决定是否启动）
            startCarControlWakeService();
        } else if (displayId != 1) {
            LogHelper.w(TAG, "⚠️ onResume时不在背屏（displayId=" + displayId + "），结束投屏");
            try {
                finishProjectionFromUser("onResume-not-on-rear");
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 处理异常情况时发生异常", e);
                currentInstance = null;
                try {
                    if (!isFinishing()) {
                        finishProjectionTask();
                    }
                } catch (Exception e2) {
                    LogHelper.e(TAG, "❌ 强制销毁Activity失败", e2);
                }
            }
            return;
        }
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        handleProjectionForegroundAfterStopGrace();
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        if (getDisplayIdSafe() == 1) {
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
        }
        overridePendingTransition(0, 0);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        LogHelper.d(TAG, "🟡 onPause");
        
        // 在背屏且没有finishing时，继续维持常亮flags（防止系统清除）
        // 与未投屏时常亮实现方式一致：在onPause时重新应用flags，确保屏幕不会熄灭
        try {
            int displayId = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    displayId = getDisplay().getDisplayId();
                } catch (Exception e) {
                    LogHelper.e(TAG, "onPause中获取displayId失败", e);
                }
            }
            
            if (!isFinishing() && displayId == 1 && getWindow() != null) {
                // 检查背屏常亮开关（与功能页一致）
                boolean keepScreenOnEnabledPause = isProjectionKeepScreenOnEnabled();

                if (keepScreenOnEnabledPause) {
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
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "onPause中重新应用常亮flags失败", e);
        }

        if (!isFinishing() && !isChangingConfigurations() && hasCarMainUi() && getDisplayIdSafe() == 1) {
            scheduleRearStopExitGraceIfNeeded("onPause");
        }
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        LogHelper.d(TAG, "🟡 onStop");
        
        // 如果Activity已经在finishing，直接返回，避免重复处理
        if (isFinishing()) {
            LogHelper.d(TAG, "🛑 Activity正在finishing，跳过onStop后续逻辑");
            return;
        }
        
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (getDisplay() != null) {
                    displayId = getDisplay().getDisplayId();
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "onStop中获取displayId失败", e);
            }
        }
        
        // 只要不再处于首次创建UI时的显示屏（displayId != initialDisplayId），
        // 就认为已经“离开背屏前台显示”或被系统移动到主屏/其他屏幕，主动清理并销毁自己
        boolean movedToOtherDisplay = (initialDisplayId != -1 && displayId != initialDisplayId);
        if (movedToOtherDisplay) {
            LogHelper.d(TAG, "🛑 车控界面已从初始显示屏移动(displayId=" + displayId + ", initialDisplayId=" + initialDisplayId + ")，自动清理并销毁");
            try {
                performCleanupAndExit();
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ onStop中执行清理时发生异常", e);
            }
            if (!isFinishing()) {
                finishProjectionTask();
            }
        } else {
            // 仍在初始显示屏上（通常是背屏），保持原有行为：根据开关状态维持常亮flags
            try {
                if (getWindow() != null) {
                    boolean keepScreenOnEnabledStop = isProjectionKeepScreenOnEnabled();

                    if (keepScreenOnEnabledStop) {
                        getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        );
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true);
                            setTurnScreenOn(true);
                        }
                        LogHelper.d(TAG, "✅ onStop: 在背屏时重新应用常亮flags，防止屏幕熄灭（与未投屏时常亮一致）");
                    }
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "onStop中重新应用常亮flags失败", e);
            }
        }

        if (!isFinishing() && !isChangingConfigurations() && hasCarMainUi() && displayId == 1) {
            scheduleRearStopExitGraceIfNeeded("onStop");
        }
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 在窗口焦点改变时禁用动画
        overridePendingTransition(0, 0);
        if (hasFocus) {
            cancelRearStopExitGrace();
        }

        // 与背屏桌面/歌词一致：获焦时清除 NOT_TOUCHABLE，否则系统边缘返回长时间无响应
        if (hasFocus && !isFinishing() && !isDestroyed() && getDisplayIdSafe() == 1) {
            RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
            RearMirootProjectionLifecycle.primeRearSystemBackGestures(this);
            hideSystemUI();
        }
        
        // 如果窗口获得焦点，重新设置Display Cutout模式和常亮flags（防止切换画面时摄像头区域黑屏和屏幕熄灭）
        if (hasFocus && !isFinishing()) {
            // 检查是否在背屏
            int displayId = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    displayId = getDisplay().getDisplayId();
                } catch (Exception e) {
                    LogHelper.e(TAG, "获取displayId失败", e);
                }
            }
            
            // 在背屏时重新应用常亮flags（确保屏幕不会熄灭，防止双击息屏）
            if (displayId == 1) {
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
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.layoutInDisplayCutoutMode = 
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                getWindow().setAttributes(params);
            }
            
            // 确保安全区域正确应用（窗口属性可能变化）
            if (contentContainer != null && mainHandler != null) {
                // 延迟一小段时间，确保窗口完全获得焦点后再应用安全区域（避免闪烁）
                mainHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed() && contentContainer != null) {
                        applySafeAreaPadding();
                    }
                }, 50);
            }
        }
        
        // 如果窗口获得焦点，检查是否需要初始化UI（从主屏移动到背屏的情况）
        if (hasFocus && !isFinishing()) {
            int displayId = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    displayId = getDisplay().getDisplayId();
                } catch (Exception e) {
                    LogHelper.e(TAG, "获取displayId失败", e);
                }
            }
            
            if (displayId == 1 && mainLayout == null) {
                initCarControlUiOnRearIfNeeded("onWindowFocusChanged");
            }
        }
    }
    
    /**
     * 执行清理并退出（统一处理所有退出场景）
     */
    private void performCleanupAndExit() {
        performProjectionResourceCleanup(true);
    }

    /**
     * 资源清理；用户手势/返回退出时由 {@link #onDestroy()} 调用，不在 finish 前执行以免黑屏停留。
     *
     * @param restoreOfficial 是否同步恢复官方背屏（异常/非统一退出路径）
     */
    private void performProjectionResourceCleanup(boolean restoreOfficial) {
        if (isCleaningUp) {
            LogHelper.d(TAG, "⚠️ 清理已在进行中，仍尝试注销背屏常亮");
            stopCarControlWakeService(true);
            releaseProjectionProximitySession();
            return;
        }

        isCleaningUp = true;
        cancelMainScreenPlaceholderTimeout();
        cancelRearUiInitPoll();
        cancelRearStopExitGrace();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        try {
            LogHelper.d(TAG, "🧹 车控资源清理 (restoreOfficial=" + restoreOfficial + ")");

            if (currentInstance == this) {
                currentInstance = null;
            } else if (currentInstance != null) {
                currentInstance = null;
            }

            if (screenReceiver != null) {
                try {
                    unregisterReceiver(screenReceiver);
                    screenReceiver = null;
                } catch (Exception e) {
                    LogHelper.w(TAG, "注销屏幕关闭监听失败", e);
                }
            }

            if (!projectionExitFlowStarted) {
                cleanupWindow(false);
            }

            if (restoreOfficial && !finishRequestedByMiRoot) {
                ITaskService tsSnapshot = taskService;
                try {
                    OfficialSubscreenMiRootProjectionSession.release(getApplicationContext(), tsSnapshot);
                } catch (Exception e) {
                    LogHelper.w(TAG, "恢复官方背屏服务（Session）失败", e);
                }
            }

            if (taskService != null) {
                try {
                    unbindService(taskServiceConnection);
                } catch (Exception e) {
                    LogHelper.e(TAG, "解绑TaskService失败", e);
                }
                taskService = null;
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 车控资源清理异常", e);
            currentInstance = null;
        } finally {
            stopCarControlWakeService(true);
            releaseProjectionProximitySession();
        }
    }
    
    @Override
    public void finish() {
        if (finishRequestedByMiRoot || projectionExitFlowStarted) {
            RearMirootProjectionLifecycle.hideWindowBeforeProjectionFinish(this);
            RearMirootProjectionLifecycle.sendMainDisplayHomeBeforeProjectionEnd(taskService);
            RearMirootProjectionLifecycle.prepareRearDisplayBeforeFinish(getDisplayIdSafe(), taskService);
            if (initialDisplayId == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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

    /** 异常/门禁路径可 finishAndRemoveTask；用户主动结束走 {@link #finish()}。 */
    private void finishProjectionTask() {
        if (finishRequestedByMiRoot || projectionExitFlowStarted) {
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            } catch (Exception ignored) {
            }
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
    
    @Override
    protected void onDestroy() {
        LogHelper.d(TAG, "🔵 onDestroy");
        cancelMainScreenPlaceholderTimeout();
        cancelRearStopExitGrace();

        // 释放 CarButtonStateManager
        if (carButtonStateManager != null) {
            carButtonStateManager.release();
            carButtonStateManager = null;
        }

        // 立即清除静态实例，避免在检查时误判为正在运行
        if (currentInstance == this) {
            currentInstance = null;
            LogHelper.d(TAG, "✅ 已在onDestroy开始时清除静态实例");
        } else if (currentInstance != null) {
            LogHelper.w(TAG, "⚠️ 检测到静态实例不是当前实例，强制清除");
            currentInstance = null;
        }
        
        // 尽早注销常亮，避免后续 performCleanupAndExit 因 isCleaningUp 早退而漏掉 stopWakeService
        stopCarControlWakeService(true);
        
        // 用户主动结束保持不透明底直至销毁；仅异常路径透明兜底。
        if (!finishRequestedByMiRoot && !projectionExitFlowStarted) {
            try {
                if (getWindow() != null) {
                    View decorView = getWindow().getDecorView();
                    getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                    if (decorView != null) {
                        decorView.setBackgroundColor(0x00000000);
                    }
                    if (mainLayout != null) {
                        mainLayout.setVisibility(View.GONE);
                    }
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "⚠️ onDestroy 窗口透明化失败", e);
            }
        }
        
        // 清理所有Handler的Runnable，避免内存泄漏
        if (mainHandler != null) {
            if (retryDisableGestureRunnable != null) {
                mainHandler.removeCallbacks(retryDisableGestureRunnable);
                retryDisableGestureRunnable = null;
            }
            if (retryEnableGestureRunnable != null) {
                mainHandler.removeCallbacks(retryEnableGestureRunnable);
                retryEnableGestureRunnable = null;
            }
            cancelRearUiInitPoll();
            if (delayedResumeCheckRunnable != null) {
                mainHandler.removeCallbacks(delayedResumeCheckRunnable);
                delayedResumeCheckRunnable = null;
            }
            // 清理歌词系统
            if (positionSyncRunnable != null && positionSyncHandler != null) {
                positionSyncHandler.removeCallbacks(positionSyncRunnable);
                positionSyncRunnable = null;
            }
            // 清理所有待执行的消息
            mainHandler.removeCallbacksAndMessages(null);
            LogHelper.d(TAG, "✅ 已清理所有Handler的Runnable");
        }
        
        // 清理MediaController
        if (mediaController != null && mediaControllerCallback != null) {
            try {
                mediaController.unregisterCallback(mediaControllerCallback);
            } catch (Exception e) {
                LogHelper.w(TAG, "⚠️ 注销MediaController回调失败", e);
            }
            mediaController = null;
            mediaControllerCallback = null;
        }
        
        if (rearAssistProximityPrefsListener != null) {
            try {
                RearAssistPrefs.INSTANCE.prefs(this).unregisterOnSharedPreferenceChangeListener(rearAssistProximityPrefsListener);
                rearAssistProximityPrefsListener = null;
                LogHelper.d(TAG, "✅ 已注销背屏遮盖偏好监听");
            } catch (Exception e) {
                LogHelper.w(TAG, "注销背屏遮盖偏好监听失败", e);
            }
        }
        releaseProjectionProximitySession();

        // 注销背屏常亮开关状态变化监听
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
        
        // 车模图片已改为固定大小，无需清理调整相关的Handler和Runnable
        
        // 重置刷新标志
        isRefreshingCarInfo = false;
        stopRefreshIconRotation();
        
        // 执行清理逻辑（统一处理）
        // 注意：performCleanupAndExit() 内部有 isCleaningUp 标志防止重复调用
        // 如果已经在其他地方（如 onResume）调用过，这里会被跳过，避免重复清理和二次动画
        performProjectionResourceCleanup(!finishRequestedByMiRoot);

        super.onDestroy();

        if (finishRequestedByMiRoot) {
            RearMirootProjectionLifecycle.scheduleOfficialSubscreenRestoreAfterDestroy(
                    getApplicationContext(), null);
        }
    }
    
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LogHelper.d(TAG, "🔄 onConfigurationChanged");
        applyCarControlUiMode();

        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (getDisplay() != null) {
                    displayId = getDisplay().getDisplayId();
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "onConfigurationChanged中获取displayId失败", e);
            }
        }
        LogHelper.d(TAG, "📍 onConfigurationChanged时displayId=" + displayId + ", initialDisplayId=" + initialDisplayId);
        
        // 如果已经在背屏创建过UI（initialDisplayId != -1），但现在被系统切回了主屏/其他屏幕，
        // 说明任务被系统迁移到了错误的显示屏，立即执行清理并销毁，避免长期占用主屏
        if (initialDisplayId != -1 && displayId != initialDisplayId) {
            LogHelper.w(TAG, "🛑 onConfigurationChanged检测到displayId变化（从 " + initialDisplayId + " 到 " + displayId + "），立即清理并销毁Activity");
            try {
                performCleanupAndExit();
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ onConfigurationChanged中执行清理时发生异常", e);
            }
            if (!isFinishing()) {
                finishProjectionTask();
            }
        }
    }
    
    /**
     * 清理窗口资源。
     *
     * @param restoreDecorAndBars {@code false} 在即将 {@link #finish()} 时：不做透明化、不恢复系统栏/insets，
     *                            避免与歌词投屏相同机理的闪白；{@code true} 保留原完整清理（异常路径等）。
     */
    private void cleanupWindow(boolean restoreDecorAndBars) {
        try {
            LogHelper.d(TAG, "🧹 开始清理窗口资源 (restoreDecorAndBars=" + restoreDecorAndBars + ")");
            
            if (getWindow() == null) {
                LogHelper.w(TAG, "⚠️ Window为null，跳过清理");
                return;
            }

            View decorView = getWindow().getDecorView();

            if (restoreDecorAndBars) {
                // 首先设置窗口背景为透明，避免退出时显示灰色背景
                try {
                    getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                    LogHelper.d(TAG, "✅ 已设置窗口背景为透明");
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 设置窗口背景透明失败", e);
                }

                // 隐藏或移除视图内容，避免显示灰色背景
                if (decorView != null) {
                    // 隐藏主布局（如果存在）
                    if (mainLayout != null) {
                        try {
                            mainLayout.setVisibility(View.GONE);
                            LogHelper.d(TAG, "✅ 已隐藏主布局");
                        } catch (Exception e) {
                            LogHelper.w(TAG, "⚠️ 隐藏主布局失败", e);
                        }
                    }

                    // 设置DecorView背景为透明
                    try {
                        decorView.setBackgroundColor(0x00000000);
                        LogHelper.d(TAG, "✅ 已设置DecorView背景为透明");
                    } catch (Exception e) {
                        LogHelper.w(TAG, "⚠️ 设置DecorView背景透明失败", e);
                    }
                }
            } else {
                // 与 blackout 一致：内容在透明主题下会盖住 window background，必须隐藏/压黑（仅不设透明、不拉系统栏）。
                if (mainLayout != null) {
                    try {
                        mainLayout.setVisibility(View.GONE);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "⚠️ 隐藏主布局失败", e);
                    }
                }
                try {
                    View contentRoot = findViewById(android.R.id.content);
                    if (contentRoot != null) {
                        contentRoot.setBackgroundColor(0xFF000000);
                    }
                    if (decorView != null) {
                        decorView.setBackgroundColor(0xFF000000);
                    }
                    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 压黑内容/窗口失败", e);
                }
                LogHelper.d(TAG, "✅ 跳过透明化与 Decor/insets 恢复（即将 finish，避免闪白）");
            }

            int flagsToClear =
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (restoreDecorAndBars) {
                flagsToClear |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            getWindow().clearFlags(flagsToClear);
            
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
            
            // 恢复系统UI可见性（显示状态栏和导航栏）
            if (restoreDecorAndBars && decorView != null) {
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
            
            // 恢复窗口属性（可选，因为Activity正在销毁）
            // 注意：窗口属性在Activity销毁时会自动重置，这里主要是为了日志记录
            
            LogHelper.d(TAG, "✅ 窗口资源清理完成");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 清理窗口资源时发生异常", e);
        }
    }
    
    /**
     * 恢复官方Launcher（在背屏时）
     * 确保用户手动退出投屏时正确恢复
     * 注意：enableOfficialGesture() 已经调用了 enableSubScreenLauncher()，但这里仍然显式调用以确保恢复
     * enableSubScreenLauncher() 是幂等的，多次调用不会出错
     * 
     * @deprecated 使用 restoreOfficialLauncherInBackground() 代替，确保在后台线程执行并使用shell命令
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
        if (displayId == 1) {
            if (taskService != null) {
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
                LogHelper.w(TAG, "⚠️ TaskService未连接，无法恢复官方Launcher");
                // TaskService未连接时，尝试重新绑定（延迟执行，避免阻塞）
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (taskService == null) {
                        LogHelper.d(TAG, "🔄 尝试重新绑定TaskService以恢复官方Launcher");
                        bindTaskService();
                        // 延迟后再次尝试恢复
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (taskService != null) {
                                restoreOfficialLauncherInBackground();
                            } else {
                                LogHelper.w(TAG, "⚠️ TaskService重新绑定失败，无法恢复官方Launcher");
                            }
                        }, 500);
                    } else {
                        // TaskService已连接，直接恢复
                        restoreOfficialLauncherInBackground();
                    }
                }, 100);
            }
        } else {
            LogHelper.d(TAG, "ℹ️ 不在背屏（displayId=" + displayId + "），跳过恢复Launcher");
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
                LogHelper.d(TAG, "✅ 已通过shell命令恢复官方Launcher（车控投屏结束，无动画）");
            } else {
                LogHelper.w(TAG, "⚠️ TaskService未连接，无法恢复官方Launcher");
                // TaskService 未连接时回退到 MainActivity 执行 shell
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
     * 应用安全区域适配（让内容避开摄像头，但背景延伸到摄像头区域）
     * 参考：RearScreenNotificationActivity.applySafeAreaPadding()
     * 
     * 注意：此方法主要用于确保cutout信息正确应用，或者在缓存初始化后更新margin
     * 大多数情况下，cutout信息已经在createUI()中创建contentContainer时应用了
     */
    private void applySafeAreaPadding() {
        try {
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
            if (info == null) {
                LogHelper.d(TAG, "ℹ️ 无背屏缓存信息，跳过安全区");
                return;
            }

            int targetLeft = effectiveCarControlContainerLeftMarginPx(info.cutout.left);
            int targetTop = info.cutout.top;
            int targetRight = info.cutout.right;
            int targetBottom = info.cutout.bottom;
            if (targetLeft == 0 && targetTop == 0 && targetRight == 0 && targetBottom == 0) {
                LogHelper.d(TAG, "ℹ️ 无需安全区 margin（非背屏或无 cutout/回退）");
                return;
            }
            
            // 确保contentContainer已创建
            if (contentContainer == null) {
                LogHelper.w(TAG, "⚠️ contentContainer为空，无法应用安全区域");
                return;
            }
            
            // 获取contentContainer的LayoutParams
            if (contentContainer.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams params = 
                    (RelativeLayout.LayoutParams) contentContainer.getLayoutParams();
                
                if (params.leftMargin == targetLeft
                        && params.topMargin == targetTop
                        && params.rightMargin == targetRight
                        && params.bottomMargin == targetBottom) {
                    LogHelper.d(TAG, "ℹ️ 安全区域 margin 已正确，跳过更新");
                    return;
                }

                params.leftMargin = targetLeft;
                params.topMargin = targetTop;
                params.rightMargin = targetRight;
                params.bottomMargin = targetBottom;
                contentContainer.setLayoutParams(params);

                LogHelper.d(TAG, String.format("✅ 已更新安全区 margin: left=%d(parsed=%d), top=%d, right=%d, bottom=%d",
                    targetLeft, info.cutout.left, targetTop, targetRight, targetBottom));
                updateCarScreenAnchoredChildMargins();
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 应用安全区域失败", e);
        }
    }

    /**
     * contentContainer 左移（摄像头安全区）后，按基线整屏坐标重算信息列 / 底栏 / 车模的 leftMargin。
     */
    private void updateCarScreenAnchoredChildMargins() {
        if (contentContainer == null) {
            return;
        }
        android.view.ViewGroup.LayoutParams glp = contentContainer.getLayoutParams();
        if (!(glp instanceof RelativeLayout.LayoutParams)) {
            return;
        }
        int cl = ((RelativeLayout.LayoutParams) glp).leftMargin;

        if (carInfoTopLayout != null) {
            android.view.ViewGroup.LayoutParams tlp = carInfoTopLayout.getLayoutParams();
            if (tlp instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) tlp;
                p.leftMargin = Math.max(0, CAR_UI_SCREEN_LEFT_INFO_PX - cl);
                p.topMargin = 20;
                carInfoTopLayout.setLayoutParams(p);
            }
        }
        if (buttonPageContainer != null) {
            android.view.ViewGroup.LayoutParams blp = buttonPageContainer.getLayoutParams();
            if (blp instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) blp;
                p.leftMargin = Math.max(0, CAR_UI_SCREEN_LEFT_INFO_PX - cl);
                p.rightMargin = 50;
                p.bottomMargin = CAR_UI_BUTTON_BAR_BOTTOM_MARGIN_PX;
                buttonPageContainer.setLayoutParams(p);
            }
        }
        if (carModelImage != null) {
            android.view.ViewGroup.LayoutParams ilp = carModelImage.getLayoutParams();
            if (ilp instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) ilp;
                p.leftMargin = Math.max(0, CAR_UI_SCREEN_LEFT_MODEL_PX - cl);
                p.rightMargin = 20;
                carModelImage.setLayoutParams(p);
            }
        }
    }
}

