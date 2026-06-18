package com.wmqc.miroot.rear;

import com.wmqc.miroot.display.MainDisplayUi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.wmqc.miroot.BuildConfig;
import com.wmqc.miroot.MainActivity;
import com.wmqc.miroot.R;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.LyricsTaskTracking;
import com.wmqc.miroot.lyrics.RearScreenWakeManager;
import com.wmqc.miroot.lyrics.RearScreenWakeService;
import com.wmqc.miroot.lyrics.RootTaskService;
import com.wmqc.miroot.rear.desktop.RearScreenDesktopActivity;
import com.wmqc.miroot.shell.SwitchToRearQsTileService;

/**
 * 前台Service - 投屏常亮（与「始终常亮」同间隔与 KEYCODE_WAKEUP，仅触发条件不同）
 * 
 * 为什么用Service而不是Activity：
 * - Activity方案失败了3次（FLAG_NOT_FOCUSABLE、屏幕外、alpha=0都会被onStop）
 * - Service不会被onPause/onStop，系统很难杀死前台Service
 * - 可以直接持有WakeLock保持屏幕常亮
 * 
 * 注意：WakeLock可能会让两个屏幕都保持常亮（无法指定特定display）
 */
public class RearSwitchKeeperService extends Service {
    private static final String TAG = "RearSwitchKeeperService";
    private static final int REAR_DISPLAY_ID = 1;
    private static final int REAR_DEFAULT_DPI = 450;
    private static final int REAR_DEFAULT_ROTATION = 0;

    /** 与通知「迁回主屏」及外部广播停止应用投屏共用 */
    public static final String ACTION_RETURN_TO_MAIN = "ACTION_RETURN_TO_MAIN";
    /** 与 [com.wmqc.miroot.lyrics.RearScreenWakeService] 同名 Action：投屏常亮总开关变更时同步 Keeper */
    public static final String ACTION_SET_KEEP_SCREEN_ON_ENABLED = "ACTION_SET_KEEP_SCREEN_ON_ENABLED";
    /** [RearScreenDesktopActivity] 结束时：仅当当前监控任务仍为该 key 时才收口（避免误停应用投屏 Keeper）。 */
    public static final String ACTION_RELEASE_MONITOR_IF_MATCH = "com.wmqc.miroot.rear.ACTION_RELEASE_MONITOR_IF_MATCH";
    public static final String EXTRA_MONITOR_KEY = "monitorKey";
    /** 为 true 时跳过 {@link #performInitialKills()}（背屏桌面等已由 Session 处理时避免重复杀进程）。 */
    public static final String EXTRA_SKIP_INITIAL_LAUNCHER_KILLS = "skipInitialLauncherKills";
    /**
     * 仅「应用列表直开背屏」迁第三方应用时应为 true：初始杀进程走 {@link AppProjectionOfficialGesturePolicy}。
     * 磁贴迁屏等为 false：与 3.4 一致始终 {@link com.wmqc.miroot.lyrics.ITaskService#killLauncherProcess()}。
     */
    public static final String EXTRA_USE_APP_LIST_OFFICIAL_GESTURE_POLICY = "useAppListOfficialGesturePolicy";
    private static final String CHANNEL_ID = "rear_screen_keeper";
    private static final int NOTIFICATION_ID = MiRootNotificationIds.APP_PROJECTION_NOTIFICATION_ID;

    private static RearSwitchKeeperService instance = null;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private ITaskService taskService = null;

    // V12.3: 初始杀进程策略 - 只杀1次，不持续监控
    private static final int INITIAL_KILL_COUNT = 1; // 初始杀1次
    private static final long KILL_INTERVAL_MS = 400; // 每次间隔400ms

    /** 接近传感器：背屏遮盖检测（见 [RearSwitchProximityController]） */
    private RearSwitchProximityController proximityController;

    // V14.5: 监听应用是否手动移回主屏
    private static final long CHECK_TASK_INTERVAL_MS = 5000; // 每5秒检查一次（优化：从3s提高到5s降低CPU唤醒频率）
    private String monitoredTaskInfo = null; // 格式: "packageName:taskId"
    private boolean skipInitialLauncherKills = false;
    private static final String SUBSCREENCENTER_PKG = "com.xiaomi.subscreencenter";
    private static final int MAX_TRANSIENT_FOREGROUND_MISMATCH = 3;
    private static final long BACKOFF_BASE_MS = 1000L;
    private static final long BACKOFF_MAX_MS = 30_000L;
    private static final int FAILURE_COUNT_FOR_SHORT_COOLDOWN = 4;
    private static final int FAILURE_COUNT_FOR_LONG_COOLDOWN = 7;
    private static final long COOL_DOWN_SHORT_MS = 30_000L;
    private static final long COOL_DOWN_LONG_MS = 60_000L;

    // V2.3: 临时暂停监控（充电动画显示期间）
    private boolean monitoringPaused = false;
    private int dependencyFailureCount = 0;
    private int consecutiveForegroundMismatchCount = 0;
    private long cooldownUntilUptimeMs = 0L;
    private int reconnectFailureCount = 0;
    private long reconnectCooldownUntilUptimeMs = 0L;

    // V2.4: 持续唤醒背屏（防止自动熄屏）；间隔与「背屏辅助」设置一致
    private int wakeupIntervalMs = RearAssistPrefs.DEFAULT_INTERVAL_MS;
    private boolean keepScreenOnEnabled = true; // 由 Intent / 设置项决定
    /** WakeLock 超时兜底（30 分钟）：若 Keeper 异常未 release，系统可自动回收；wakeupRunnable 每轮续期。 */
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L;
    private boolean displayStateRestored = false;
    private boolean unifiedExitTriggered = false;

    /**
     * 用户手势返回等常会先到官方背屏中心或短暂拿不到前台；原逻辑只走退避从不
     * {@link #performUnifiedExit}，背屏 DPI/旋转不会恢复。连续若干次仍不在投屏任务上则收口。
     */
    private int offMonitoredRearStreak = 0;
    private static final int OFF_MONITORED_STREAK_FOR_EXIT = 5;

    /** 见 {@link #EXTRA_USE_APP_LIST_OFFICIAL_GESTURE_POLICY}。 */
    private boolean useAppListOfficialGesturePolicyForInitialKill = false;

    public static void pauseMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = true;

            // ✅ 取消所有pending的检查任务
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                if (BuildConfig.DEBUG) LogHelper.d(TAG, "Monitoring paused, all checks cancelled");
            } else {
                if (BuildConfig.DEBUG) LogHelper.d(TAG, "Monitoring paused");
            }
        }
    }

    public static void resumeMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = false;
            if (BuildConfig.DEBUG) LogHelper.d(TAG, "Monitoring resumed");

            // ✅ 延迟5秒后才开始检查，给投送app足够时间恢复到前台
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                instance.handler.postDelayed(instance.checkTaskRunnable, 5000);
                if (BuildConfig.DEBUG) LogHelper.d(TAG, "Next check scheduled in 5 seconds");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 创建通知渠道
        createNotificationChannel();

        // 创建Handler用于定时任务
        handler = new Handler(Looper.getMainLooper());

        proximityController = new RearSwitchProximityController(this, handler, this::handleProximityCovered);

        // V2.2: 从SharedPreferences恢复传感器开关状态
        loadProximitySensorSetting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // V14.6: 处理点击通知返回主屏的事件（允许重复触发，避免上次收口中断后无法再次清理）
        if (intent != null && ACTION_RETURN_TO_MAIN.equals(intent.getAction())) {
            unifiedExitTriggered = false;
            performUnifiedExit(true, true);
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_RELEASE_MONITOR_IF_MATCH.equals(intent.getAction())) {
            String key = intent.getStringExtra(EXTRA_MONITOR_KEY);
            if (monitoredTaskInfo != null && key != null && key.equals(monitoredTaskInfo)) {
                performUnifiedExit(false, false);
            }
            return START_NOT_STICKY;
        }

        // V2.2: 接近传感器 — 始终与功能页「背屏遮盖检测」偏好一致
        if (intent != null && "ACTION_SET_PROXIMITY_ENABLED".equals(intent.getAction())) {
            loadProximitySensorSetting();
            applyProximitySensorRegistrationState();
            return START_STICKY;
        }

        // V2.5: 处理背屏常亮开关设置（与 [RearAssistPrefs] 一致）
        if (intent != null && ACTION_SET_KEEP_SCREEN_ON_ENABLED.equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            keepScreenOnEnabled = enabled;

            if (BuildConfig.DEBUG) LogHelper.d(TAG, "背屏常亮开关已" + (enabled ? "开启" : "关闭"));

            if (!enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                if (BuildConfig.DEBUG) LogHelper.d(TAG, "背屏WAKEUP发送已停止");
            } else if (enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                startRearScreenWakeup();
            }

            if (!enabled) {
                try {
                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                        if (BuildConfig.DEBUG) LogHelper.d(TAG, "WakeLock released (keep off)");
                    }
                } catch (Exception e) {
                    LogHelper.w(TAG, "WakeLock release failed", e);
                }
                wakeLock = null;
            } else {
                try {
                    if (wakeLock == null || !wakeLock.isHeld()) {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        wakeLock = pm.newWakeLock(
                                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                                "MiRoot::RearSwitchKeeper");
                        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                        if (BuildConfig.DEBUG) LogHelper.d(TAG, "WakeLock acquired (keep on, timeout=30min)");
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "WakeLock acquire failed", e);
                }
            }

            try {
                Notification n = buildNotification();
                MiRootNotificationIds.cancelBusinessProjectionNotifications(getApplicationContext());
                if (Build.VERSION.SDK_INT >= 34) {
                    ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID,
                            n,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTIFICATION_ID, n);
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "foreground notif update failed", e);
            }

            return START_STICKY;
        }

        try {
            // V14.7: 先从Intent获取要监控的任务信息
            if (intent != null) {
                String newMonitoredTask = intent.getStringExtra("lastMovedTask");
                if (newMonitoredTask != null) {
                    if (monitoredTaskInfo == null || !monitoredTaskInfo.equals(newMonitoredTask)) {
                        displayStateRestored = false;
                        unifiedExitTriggered = false;
                        offMonitoredRearStreak = 0;
                    }
                    monitoredTaskInfo = newMonitoredTask;
                }
                skipInitialLauncherKills = intent.getBooleanExtra(EXTRA_SKIP_INITIAL_LAUNCHER_KILLS, false);
                useAppListOfficialGesturePolicyForInitialKill =
                        intent.getBooleanExtra(EXTRA_USE_APP_LIST_OFFICIAL_GESTURE_POLICY, false);
            }
            
            // 如果服务被系统重启但没有监控任务，说明应用已结束，停止服务
            if (monitoredTaskInfo == null) {
                if (BuildConfig.DEBUG) LogHelper.d(TAG, "没有监控任务，停止服务（可能是系统重启但应用已结束）");
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }

            // V2.5: 与 [RearAssistPrefs] 一致；磁贴迁屏会显式传入 Intent extra
            if (intent != null && intent.hasExtra("keepScreenOnEnabled")) {
                keepScreenOnEnabled = intent.getBooleanExtra("keepScreenOnEnabled", true);
            } else {
                keepScreenOnEnabled = RearAssistPrefs.INSTANCE.isKeepScreenOnEnabled(this);
            }
            if (BuildConfig.DEBUG) LogHelper.d(TAG, "背屏常亮开关状态: " + (keepScreenOnEnabled ? "开启" : "关闭"));
            wakeupIntervalMs = RearAssistPrefs.INSTANCE.intervalMs(this);

            // V15.1: 立即显示通知，不等待其他操作
            Notification notification = buildNotification();
            MiRootNotificationIds.cancelBusinessProjectionNotifications(getApplicationContext());
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            // 在后台线程执行耗时操作，不阻塞通知显示
            new Thread(() -> {
                // 绑定 TaskService（RootTaskService）
                bindTaskService();

                // 优化：延迟初始化接近传感器，仅在确认有应用在背屏且传感器开关开启时才初始化
                // 传感器将在 checkTaskRunnable 中确认应用在背屏后再初始化
            }).start();

            // 2. 仅当背屏常亮总开关开启时持有 WakeLock
            if (keepScreenOnEnabled) {
                try {
                    if (wakeLock == null || !wakeLock.isHeld()) {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        wakeLock = pm.newWakeLock(
                                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                                "MiRoot::RearSwitchKeeper");
                        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                        if (BuildConfig.DEBUG) LogHelper.d(TAG, "WakeLock acquired (SCREEN_BRIGHT_WAKE_LOCK, timeout=30min)");
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "✗ Failed to acquire WakeLock", e);
                }
            }

            // 3. 初始杀官方背屏中心（平衡球/真心话/心率等与磁贴迁屏一致；背屏桌面显式 skip）
            if (!skipInitialLauncherKills) {
                performInitialKills();
            }

            // 4. V14.5: 启动定期检查任务
            if (monitoredTaskInfo != null) {
                startTaskMonitoring();
            }

            // 5. V2.5: 启动持续唤醒背屏（每0.5秒，根据开关状态）
            startRearScreenWakeup();

        } catch (Exception e) {
            LogHelper.e(TAG, "✗ Error starting service", e);
        }

        // START_STICKY: 如果被系统杀死，会自动重启
        return START_STICKY;
    }

    /**
     * V15.2: 启动任务监听 - 检测应用是否在前台
     * 监控被投放到背屏的应用，如果不在前台了（被关闭或切换），自动停止服务并清除通知
     */
    private final Runnable checkTaskRunnable = new Runnable() {
        @Override
        public void run() {
            // V2.3: 如果监控已暂停（充电动画显示中），跳过本次检查
            if (monitoringPaused) {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                return;
            }

            long now = android.os.SystemClock.uptimeMillis();
            if (now < cooldownUntilUptimeMs) {
                long remain = Math.max(300L, cooldownUntilUptimeMs - now);
                handler.postDelayed(this, Math.min(remain, CHECK_TASK_INTERVAL_MS));
                return;
            }

            if (monitoredTaskInfo != null && taskService != null) {
                try {
                    // V15.2: 检查背屏(displayId=1)的前台应用是否还是我们监控的应用
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(REAR_DISPLAY_ID);

                    // 以 taskId 为准：手势返回等会把任务迁离背屏，但 am stack list 仍可能在同屏段内保留旧 task 行，
                    // 仅靠 getForegroundAppOnDisplay 字符串比对会漏收口。
                    int monitoredTaskId = parseMonitoredTaskId();
                    if (monitoredTaskId > 0
                            && !taskService.isTaskOnDisplay(monitoredTaskId, REAR_DISPLAY_ID)) {
                        offMonitoredRearStreak = 0;
                        onDependencyHealthy();
                        if (BuildConfig.DEBUG) {
                            LogHelper.d(TAG, "monitored task left rear display tid=" + monitoredTaskId + " -> unified exit");
                        }
                        performUnifiedExit(false, false);
                        return;
                    }

                    // V2.3: 排除充电动画/通知动画（临时占用背屏，不应导致Service销毁）
                    if (rearForegroundApp != null && (rearForegroundApp.contains("RearScreenChargingActivity")
                            || rearForegroundApp.contains("RearScreenNotificationActivity"))) {
                        // 充电动画正在显示，跳过本次检查
                        offMonitoredRearStreak = 0;
                        onDependencyHealthy();
                        handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                        return;
                    }

                    // 如果背屏前台应用不是我们监控的应用，说明它被关闭或切换了
                    if (rearForegroundApp == null || !rearForegroundApp.equals(monitoredTaskInfo)) {
                        // 上文已判定 monitoredTaskId>0 时任务仍在背屏段内：getForegroundAppOnDisplay 只取该段
                        // 「最后一条」task 行，与真实栈顶/可见 Activity 常不一致（副屏中心、他包 taskId 等；
                        // logcat 曾出现投抖音却报 org.telegram.plus），不能据此收窄口。
                        if (monitoredTaskId > 0) {
                            offMonitoredRearStreak = 0;
                            onDependencyHealthy();
                            proximityController.initSensorIfNeeded();
                            handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                            return;
                        }
                        // 无有效 taskId 时退回旧策略
                        if (rearForegroundApp == null || rearForegroundApp.contains(SUBSCREENCENTER_PKG)) {
                            // 手势返回等：背屏回到官方中心或短暂 null，不能永远只退避而不恢复 DPI/旋转。
                            offMonitoredRearStreak++;
                            if (offMonitoredRearStreak >= OFF_MONITORED_STREAK_FOR_EXIT) {
                                if (BuildConfig.DEBUG) {
                                    LogHelper.d(TAG, "rear at launcher/null streak=" + offMonitoredRearStreak + " -> unified exit");
                                }
                                offMonitoredRearStreak = 0;
                                onDependencyHealthy();
                                performUnifiedExit(false, false);
                                return;
                            }
                            onDependencyFailure("rear foreground unavailable: " + rearForegroundApp);
                            handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                            return;
                        }

                        offMonitoredRearStreak = 0;
                        consecutiveForegroundMismatchCount++;
                        if (consecutiveForegroundMismatchCount < MAX_TRANSIENT_FOREGROUND_MISMATCH) {
                            onDependencyFailure("transient foreground mismatch: " + rearForegroundApp);
                            handler.postDelayed(this, computeDependencyBackoffDelayMs());
                            return;
                        }
                        onDependencyHealthy();
                        // 应用不在背屏前台了（被关闭或切换），统一收口清理状态与参数
                        performUnifiedExit(false, false);
                        return;
                    }

                    offMonitoredRearStreak = 0;
                    // 优化：确认应用在背屏后，才初始化接近传感器（仅在需要时启用）
                    proximityController.initSensorIfNeeded();
                    onDependencyHealthy();

                    // 继续监听
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);

                } catch (Exception e) {
                    onDependencyFailure("task check failed: " + e.getMessage());
                    LogHelper.w(TAG, "Task check failed: " + e.getMessage());
                    handler.postDelayed(this, computeDependencyBackoffDelayMs());
                }
            } else {
                onDependencyFailure("task service unavailable");
                handler.postDelayed(this, computeDependencyBackoffDelayMs());
            }
        }
    };

    private void startTaskMonitoring() {
        if (monitoredTaskInfo != null && handler != null) {
            // 首次尽快检测（下一帧），避免「投屏开始后 3s 内手势返回」要等到首轮 postDelayed 才收口。
            handler.post(checkTaskRunnable);
        }
    }

    /**
     * V2.5: 持续唤醒背屏任务 - 每0.8秒发送WAKEUP，防止背屏自动熄屏（优化：从100ms改为800ms以降低耗电）
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            // 检查开关状态
            if (!keepScreenOnEnabled) {
                // 开关已关闭，停止唤醒循环
                return;
            }

            // 续期 WakeLock（acquire 有超时版本会延长超时，避免异常时系统无法休眠）
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                } catch (Exception ignored) {
                }
            }

            // 发送WAKEUP唤醒信号（与「始终常亮」一致：不在循环内轮询背屏前台；结束迁屏由 stopService / checkTaskRunnable 负责）
            if (taskService != null) {
                try {
                    // 向背屏(displayId=1)发送WAKEUP唤醒信号
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // LogHelper.d(TAG, "✨ 背屏保活唤醒已发送"); // 注释掉以减少日志
                } catch (Exception e) {
                    LogHelper.w(TAG, "背屏唤醒失败: " + e.getMessage());
                }
            }

            // 持续发送，最低 1.2 秒一次，减少唤醒与耗电。
            if (keepScreenOnEnabled) {
                handler.postDelayed(this, Math.max(wakeupIntervalMs, 1200));
            }
        }
    };

    private void startRearScreenWakeup() {
        if (handler != null && keepScreenOnEnabled) {
            // 稍后再发首个 WAKEUP，降低启动瞬时抖动。
            handler.postDelayed(wakeupRearScreenRunnable, 180);
            if (BuildConfig.DEBUG) LogHelper.d(TAG, "背屏持续唤醒已启动");
        }
    }

    /**
     * V12.3: 初始杀进程 - 只杀1次，不持续监控。
     * {@link #EXTRA_USE_APP_LIST_OFFICIAL_GESTURE_POLICY} 为 true 时走 {@link AppProjectionOfficialGesturePolicy}；
     * 否则与 3.4 磁贴迁屏一致走 {@link com.wmqc.miroot.lyrics.ITaskService#killLauncherProcess()}。
     */
    private void performInitialKills() {

        for (int i = 0; i < INITIAL_KILL_COUNT; i++) {
            final int killNumber = i + 1;

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (taskService != null) {
                        try {
                            String pkgForPolicy = "";
                            if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
                                int colon = monitoredTaskInfo.indexOf(':');
                                if (colon > 0) {
                                    pkgForPolicy = monitoredTaskInfo.substring(0, colon);
                                }
                            }
                            if (useAppListOfficialGesturePolicyForInitialKill) {
                                taskService.killLauncherProcessForAppProjection(pkgForPolicy);
                            } else {
                                taskService.killLauncherProcess();
                            }
                        } catch (Exception e) {
                            LogHelper.w(TAG, "⚠ Kill #" + killNumber + " failed: " + e.getMessage());
                        }
                    } else {
                        LogHelper.w(TAG, "⚠ TaskService not available for kill #" + killNumber);
                    }

                    // 最后一次杀完后的总结
                    if (killNumber == INITIAL_KILL_COUNT) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                            }
                        }, 100);
                    }
                }
            }, i * KILL_INTERVAL_MS);
        }
    }

    /**
     * TaskService 连接回调（RootTaskService）
     */
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            reconnectFailureCount = 0;
            reconnectCooldownUntilUptimeMs = 0L;

            // 取消重连任务（如果存在）
            if (handler != null) {
                handler.removeCallbacks(reconnectTaskServiceRunnable);
            }

            if (!skipInitialLauncherKills) {
                performInitialKills();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            LogHelper.w(TAG, "⚠ TaskService disconnected - will attempt to reconnect");
            taskService = null;

            // 启动重连任务
            scheduleReconnectTaskService();
        }
    };

    /**
     * TaskService重连任务
     */
    private final Runnable reconnectTaskServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (taskService == null) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now < reconnectCooldownUntilUptimeMs) {
                    handler.postDelayed(this, reconnectCooldownUntilUptimeMs - now);
                    return;
                }
                bindTaskService();
                reconnectFailureCount++;
                applyReconnectCooldownIfNeeded();
                handler.postDelayed(this, computeReconnectBackoffDelayMs());
            } else {
            }
        }
    };

    /**
     * 安排TaskService重连
     */
    private void scheduleReconnectTaskService() {
        if (handler != null) {
            handler.postDelayed(reconnectTaskServiceRunnable, 300);
        }
    };

    /**
     * 绑定 TaskService（通过 RootTaskService）
     */
    private void bindTaskService() {
        if (taskService != null) {
            return;
        }

        try {
            Intent intent = new Intent(this, RootTaskService.class);
            bindService(intent, taskServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            LogHelper.e(TAG, "✗ Failed to bind TaskService", e);
        }
    }

    private void onDependencyHealthy() {
        dependencyFailureCount = 0;
        consecutiveForegroundMismatchCount = 0;
        cooldownUntilUptimeMs = 0L;
    }

    private void onDependencyFailure(String reason) {
        dependencyFailureCount++;
        applyDependencyCooldownIfNeeded();
        LogHelper.w(TAG, "⚠ Rear dependency unhealthy #" + dependencyFailureCount + " : " + reason);
    }

    private long computeDependencyBackoffDelayMs() {
        int exp = Math.max(0, dependencyFailureCount - 1);
        long delay = BACKOFF_BASE_MS << Math.min(exp, 5);
        if (delay < 0 || delay > BACKOFF_MAX_MS) {
            delay = BACKOFF_MAX_MS;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (cooldownUntilUptimeMs > now) {
            delay = Math.max(delay, cooldownUntilUptimeMs - now);
        }
        return Math.max(delay, CHECK_TASK_INTERVAL_MS);
    }

    private void applyDependencyCooldownIfNeeded() {
        long now = android.os.SystemClock.uptimeMillis();
        if (dependencyFailureCount >= FAILURE_COUNT_FOR_LONG_COOLDOWN) {
            cooldownUntilUptimeMs = Math.max(cooldownUntilUptimeMs, now + COOL_DOWN_LONG_MS);
            return;
        }
        if (dependencyFailureCount >= FAILURE_COUNT_FOR_SHORT_COOLDOWN) {
            cooldownUntilUptimeMs = Math.max(cooldownUntilUptimeMs, now + COOL_DOWN_SHORT_MS);
        }
    }

    private long computeReconnectBackoffDelayMs() {
        int exp = Math.max(0, reconnectFailureCount - 1);
        long delay = BACKOFF_BASE_MS << Math.min(exp, 5);
        if (delay < 0 || delay > BACKOFF_MAX_MS) {
            delay = BACKOFF_MAX_MS;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (reconnectCooldownUntilUptimeMs > now) {
            delay = Math.max(delay, reconnectCooldownUntilUptimeMs - now);
        }
        return Math.max(delay, BACKOFF_BASE_MS);
    }

    private void applyReconnectCooldownIfNeeded() {
        long now = android.os.SystemClock.uptimeMillis();
        if (reconnectFailureCount >= FAILURE_COUNT_FOR_LONG_COOLDOWN) {
            reconnectCooldownUntilUptimeMs = Math.max(reconnectCooldownUntilUptimeMs, now + COOL_DOWN_LONG_MS);
            return;
        }
        if (reconnectFailureCount >= FAILURE_COUNT_FOR_SHORT_COOLDOWN) {
            reconnectCooldownUntilUptimeMs = Math.max(reconnectCooldownUntilUptimeMs, now + COOL_DOWN_SHORT_MS);
        }
    }

    /**
     * 解绑 TaskService
     */
    private void unbindTaskService() {
        if (taskService != null) {
            try {
                unbindService(taskServiceConnection);
            } catch (Exception e) {
                LogHelper.w(TAG, "Failed to unbind TaskService", e);
            }
            taskService = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (BuildConfig.DEBUG) LogHelper.d(TAG, "Service onDestroy called");

        // 立即移除前台通知
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        ProjectionOngoingNotifications.cancelAll(getApplicationContext());
        stopProjectionWakeServices();

        // 清理所有待执行的任务
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        
        // V12.2: 恢复并主动唤醒Launcher
        if (taskService != null) {
            try {

                // 1. 恢复Launcher（unsuspend）
                taskService.enableSubScreenLauncher();

            } catch (Exception e) {
                LogHelper.w(TAG, "Failed to restore launcher", e);
            }
        }

        restoreRearDisplayStateIfNeeded();

        // 解绑TaskService
        unbindTaskService();

        // 释放WakeLock（优化：确保在所有情况下都能释放，避免资源泄漏）
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                if (BuildConfig.DEBUG) LogHelper.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "Failed to release WakeLock", e);
        } finally {
            // 确保WakeLock引用被清空
            wakeLock = null;
        }

        // 注销接近传感器
        if (proximityController != null) {
            proximityController.unregisterSensor();
        }

        instance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不支持绑定
        return null;
    }

    /**
     * 创建通知渠道（Android 8.0+必需）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.rear_switch_keeper_channel),
                    NotificationManager.IMPORTANCE_LOW // 低重要性，减少干扰
            );
            channel.setDescription(getString(R.string.rear_switch_keeper_channel_desc));
            channel.setShowBadge(false); // 不显示角标
            channel.enableLights(false); // 不闪烁LED
            channel.enableVibration(false); // 不振动

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);

        }
    }

    /**
     * 获取应用名称
     */
    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(appInfo);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "Failed to get app name: " + e.getMessage());
        }
        return packageName; // 失败时返回包名
    }

    /**
     * V2.4: 创建通用的Service前台通知（供多个Service共用）
     * 点击打开应用；与「音乐投屏进行中」等其它通知的 PendingIntent 无关。
     */
    public static Notification createServiceNotification(Context context) {
        return createServiceNotification(context, false);
    }

    /**
     * @param appendMusicAutoProjectionHint 仅 {@link com.wmqc.miroot.ui.music.MusicAutoProjectionService}
     *                                      为 true：在大标题 {@link R.string#miroot_main_service_notif_title} 后追加
     *                                      {@link R.string#miroot_main_service_notif_title_suffix_auto_music_projection}，
     *                                      摘要仍为「点击打开应用」，不影响充电动画等其它调用方。
     */
    public static Notification createServiceNotification(Context context, boolean appendMusicAutoProjectionHint) {
        // 创建通知渠道
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.rear_switch_keeper_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.rear_switch_keeper_channel_desc));
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent openAppIntent = new Intent(context, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openAppPi = PendingIntent.getActivity(
                context,
                90,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String contentText = context.getString(R.string.miroot_main_service_notif_text);
        String contentTitle = context.getString(R.string.miroot_main_service_notif_title);
        if (appendMusicAutoProjectionHint) {
            contentTitle =
                    contentTitle + context.getString(R.string.miroot_main_service_notif_title_suffix_auto_music_projection);
        }

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openAppPi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 磁贴迁屏前台通知：摘要强调「点按将应用迁回主屏幕」；点击触发 moveTaskToDisplay 迁回主屏并 stopSelf。
     */
    private Notification buildNotification() {
        String appName = "应用";

        if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
            String packageName = monitoredTaskInfo.split(":")[0];
            appName = getAppName(packageName);
        } else {
            LogHelper.w(TAG, "⚠ Invalid monitored task info: " + monitoredTaskInfo);
        }

        Intent returnIntent = new Intent(this, RearSwitchKeeperService.class);
        returnIntent.setAction(ACTION_RETURN_TO_MAIN);
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 0, returnIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String lineWake = keepScreenOnEnabled
                ? getString(R.string.projection_combined_line_wake_on)
                : getString(R.string.projection_combined_line_wake_off);
        String summary = getString(R.string.projection_tile_notif_summary);
        String bigText = getString(R.string.projection_tile_notif_big_fmt, appName, lineWake);

        String keeperSuffix = keepScreenOnEnabled
                ? getString(R.string.projection_tile_notif_title_keep_suffix)
                : getString(R.string.projection_tile_notif_title_keep_empty);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.projection_tile_notif_title_fmt, appName, keeperSuffix))
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setSmallIcon(R.drawable.ic_stat_notify_record)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 检查Service是否正在运行
     */
    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 停止Service
     */
    public static void stop() {
        if (instance != null) {
            instance.stopSelf();
        }
    }

    /**
     * 功能页「背屏遮盖检测」开关或偏好刷新后调用，使磁贴 Keeper 的接近传感器与 {@link RearAssistPrefs} 一致。
     */
    public static void syncProximityWithRearAssistPrefs(Context context) {
        RearSwitchKeeperService svc = instance;
        if (svc == null) {
            return;
        }
        svc.loadProximitySensorSetting();
        svc.applyProximitySensorRegistrationState();
    }

    /**
     * 功能页「发送间隔」滑块变更后调用，使本服务内 KEYCODE_WAKEUP 周期与 {@link RearAssistPrefs#intervalMs} 一致。
     */
    public static void syncWakeIntervalFromPrefs(Context context) {
        RearSwitchKeeperService svc = instance;
        if (svc == null) {
            return;
        }
        svc.wakeupIntervalMs = Math.max(1200, RearAssistPrefs.INSTANCE.intervalMs(svc));
    }

    private void loadProximitySensorSetting() {
        proximityController.updateFromPrefs();
        wakeupIntervalMs = Math.max(1200, RearAssistPrefs.INSTANCE.intervalMs(this));
    }

    private void applyProximitySensorRegistrationState() {
        proximityController.applyRegistrationState(monitoredTaskInfo);
    }

    /**
     * 处理接近传感器覆盖事件 - 拉回主屏并停止Service
     */
    private void handleProximityCovered() {
        if (!proximityController.isProximityEnabled()) {
            LogHelper.d(TAG, "⏸️ 遮盖检测已关闭，忽略接近事件");
            return;
        }

        LogHelper.w(TAG, "═══════════════════════════════════════");
        LogHelper.w(TAG, "🤚 PROXIMITY TRIGGER - Return to main display");
        LogHelper.w(TAG, "═══════════════════════════════════════");

        performUnifiedExit(true, true);
    }

    /** @return taskId from {@link #monitoredTaskInfo} {@code pkg:taskId}，解析失败返回 -1 */
    private int parseMonitoredTaskId() {
        if (monitoredTaskInfo == null || !monitoredTaskInfo.contains(":")) {
            return -1;
        }
        try {
            int colon = monitoredTaskInfo.lastIndexOf(':');
            if (colon <= 0 || colon >= monitoredTaskInfo.length() - 1) {
                return -1;
            }
            return Integer.parseInt(monitoredTaskInfo.substring(colon + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void performUnifiedExit(boolean moveTaskToMain, boolean showToastWhenMoved) {
        if (unifiedExitTriggered) {
            return;
        }
        unifiedExitTriggered = true;

        String packageName = null;
        Integer taskId = null;
        if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
            try {
                String[] parts = monitoredTaskInfo.split(":");
                packageName = parts[0];
                taskId = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                LogHelper.w(TAG, "parse monitoredTaskInfo failed: " + monitoredTaskInfo, e);
            }
        }

        // 1) 先取消业务通知，避免退出中间态残留
        ProjectionOngoingNotifications.cancelAll(getApplicationContext());
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {
        }

        // 2) 停常亮
        keepScreenOnEnabled = false;
        if (handler != null) {
            handler.removeCallbacks(wakeupRearScreenRunnable);
            handler.removeCallbacks(checkTaskRunnable);
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "WakeLock release failed during unified exit", e);
        } finally {
            wakeLock = null;
        }
        stopProjectionWakeServices();

        // 3) 统一恢复参数：优先快照，失败回默认
        restoreRearDisplayStateIfNeeded();

        // 4) 迁回主屏
        boolean moved = false;
        if (moveTaskToMain && taskService != null && taskId != null) {
            try {
                moved = taskService.moveTaskToDisplay(taskId, 0);
            } catch (Exception e) {
                LogHelper.w(TAG, "moveTaskToDisplay(0) failed", e);
            }
        }

        // 5) 清理状态
        LyricsTaskTracking.clearLastTask();
        SwitchToRearQsTileService.clearLastMovedTask();
        AppProjectionDisplayPrefs.INSTANCE.clearSessionSnapshot(this);
        monitoredTaskInfo = null;
        if (proximityController != null) {
            proximityController.unregisterSensor();
        }

        if (showToastWhenMoved && moved && packageName != null) {
            String appName = getAppName(packageName);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                MainDisplayUi.showToast(
                        getApplicationContext(),
                        getString(R.string.toast_task_returned_main, appName),
                        Toast.LENGTH_SHORT
                );
            }, 100);
        }

        // 与背屏桌面 [RearDesktopLaunchHelper] 的 Session.acquire 成对：此前在桌面 Activity.onDestroy 里 release
        // 会在「桌面→应用投屏」交接时误恢复官方背屏；改在 Keeper 真正收口时统一 release（ref 已为 0 则无害跳过）。
        try {
            OfficialSubscreenMiRootProjectionSession.release(getApplicationContext(), taskService);
        } catch (Exception e) {
            LogHelper.w(TAG, "OfficialSubscreenMiRootProjectionSession.release failed: " + e.getMessage());
        }

        stopSelf();
    }

    /**
     * 背屏桌面投屏会注册 {@link RearScreenWakeService}；从桌面打开应用后桌面 Activity 可能仍在栈内，
     * 结束应用投屏时须在此注销，避免常亮循环与「投屏中」通知残留。
     */
    private void stopProjectionWakeServices() {
        try {
            Context app = getApplicationContext();
            RearScreenWakeManager mgr = RearScreenWakeManager.getInstance();
            // 注销所有已注册的投屏 Activity，避免残留 stale 注册导致常亮服务泄漏
            for (String name : new java.util.ArrayList<>(mgr.getRegisteredActivities())) {
                try {
                    Class<?> cls = Class.forName(name);
                    mgr.stopWakeService(app, cls);
                } catch (Exception ignored) {
                }
            }
            // 无论注册表是否已清空，都强制停 Wake，避免「MiRoot・投屏中」通知与唤醒循环残留
            stopService(new Intent(app, RearScreenWakeService.class));
            mgr.clearStaleRegistrationsWhenNoProjection(app);
        } catch (Exception e) {
            LogHelper.w(TAG, "stopProjectionWakeServices failed", e);
        }
    }

    private void restoreRearDisplayStateIfNeeded() {
        if (displayStateRestored || taskService == null) {
            return;
        }
        try {
            // 结束投屏：强制恢复固定默认值（DPI 450、旋转 0），不再恢复会话快照。
            boolean dpiOk = taskService.executeShellCommand(
                    "wm density " + REAR_DEFAULT_DPI + " -d " + REAR_DISPLAY_ID);
            boolean rotationOk = taskService.setDisplayRotation(REAR_DISPLAY_ID, REAR_DEFAULT_ROTATION);
            boolean ok = dpiOk && rotationOk;
            if (ok) {
                displayStateRestored = true;
            } else {
                LogHelper.w(TAG, "⚠ Failed to restore rear display state");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "restoreRearDisplayStateIfNeeded failed", e);
        } finally {
            AppProjectionDisplayPrefs.INSTANCE.clearSessionSnapshot(this);
        }
    }
}
