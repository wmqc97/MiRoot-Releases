

package com.wmqc.miroot.rear;

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

import com.wmqc.miroot.MainActivity;
import com.wmqc.miroot.R;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.LyricsTaskTracking;
import com.wmqc.miroot.lyrics.RootTaskService;
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
    private static final String CHANNEL_ID = "rear_screen_keeper";
    private static final int NOTIFICATION_ID = MiRootNotificationIds.APP_PROJECTION_NOTIFICATION_ID;

    private static RearSwitchKeeperService instance = null;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private ITaskService taskService = null;

    // V12.3: 初始杀进程策略 - 只杀1次，不持续监控
    private static final int INITIAL_KILL_COUNT = 1; // 初始杀1次
    private static final long KILL_INTERVAL_MS = 200; // 每次间隔200ms

    /** 接近传感器：背屏遮盖检测（见 [RearSwitchProximityController]） */
    private RearSwitchProximityController proximityController;

    // V14.5: 监听应用是否手动移回主屏
    private static final long CHECK_TASK_INTERVAL_MS = 2000; // 每2秒检查一次
    private String monitoredTaskInfo = null; // 格式: "packageName:taskId"
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
    private boolean displayStateRestored = false;
    private boolean unifiedExitTriggered = false;

    public static void pauseMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = true;

            // ✅ 取消所有pending的检查任务
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                LogHelper.d(TAG, "⏸️ Monitoring paused, all checks cancelled");
            } else {
                LogHelper.d(TAG, "⏸️ Monitoring paused");
            }
        }
    }

    public static void resumeMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = false;
            LogHelper.d(TAG, "▶️ Monitoring resumed");

            // ✅ 延迟5秒后才开始检查，给投送app足够时间恢复到前台
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                instance.handler.postDelayed(instance.checkTaskRunnable, 5000);
                LogHelper.d(TAG, "⏰ Next check scheduled in 5 seconds");
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

        // V14.6: 处理点击通知返回主屏的事件
        if (intent != null && ACTION_RETURN_TO_MAIN.equals(intent.getAction())) {
            performUnifiedExit(true, true);
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

            LogHelper.d(TAG, "🔆 背屏常亮开关已" + (enabled ? "开启" : "关闭"));

            if (!enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                LogHelper.d(TAG, "⏸️ 背屏WAKEUP发送已停止");
            } else if (enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                startRearScreenWakeup();
            }

            if (!enabled) {
                try {
                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                        LogHelper.d(TAG, "✓ WakeLock released (keep off)");
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
                        wakeLock.acquire();
                        LogHelper.d(TAG, "✓ WakeLock acquired (keep on)");
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
                    monitoredTaskInfo = newMonitoredTask;
                }
            }
            
            // 如果服务被系统重启但没有监控任务，说明应用已结束，停止服务
            if (monitoredTaskInfo == null) {
                LogHelper.d(TAG, "⏸️ 没有监控任务，停止服务（可能是系统重启但应用已结束）");
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
            LogHelper.d(TAG, "🔆 背屏常亮开关状态: " + (keepScreenOnEnabled ? "开启" : "关闭"));
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
                        wakeLock.acquire();
                        LogHelper.d(TAG, "✓ WakeLock acquired (SCREEN_BRIGHT_WAKE_LOCK)");
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "✗ Failed to acquire WakeLock", e);
                }
            }

            // 3. V12.2: 初始杀进程（只杀几次，不持续监控）
            performInitialKills();

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

                    // V2.3: 排除充电动画/通知动画（临时占用背屏，不应导致Service销毁）
                    if (rearForegroundApp != null && (rearForegroundApp.contains("RearScreenChargingActivity")
                            || rearForegroundApp.contains("RearScreenNotificationActivity"))) {
                        // 充电动画正在显示，跳过本次检查
                        onDependencyHealthy();
                        handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                        return;
                    }

                    // 如果背屏前台应用不是我们监控的应用，说明它被关闭或切换了
                    if (rearForegroundApp == null || !rearForegroundApp.equals(monitoredTaskInfo)) {
                        // 子屏桌面反复崩溃/切换时，先按健康检查和冷却窗口退避，避免频繁拉起/切换。
                        if (rearForegroundApp == null || rearForegroundApp.contains(SUBSCREENCENTER_PKG)) {
                            onDependencyFailure("rear foreground unavailable: " + rearForegroundApp);
                            handler.postDelayed(this, computeDependencyBackoffDelayMs());
                            return;
                        }

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
            handler.postDelayed(checkTaskRunnable, CHECK_TASK_INTERVAL_MS);
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

            // 持续发送，每0.8秒执行一次（优化：降低频率以减少耗电）
            if (keepScreenOnEnabled) {
                handler.postDelayed(this, wakeupIntervalMs);
            }
        }
    };

    private void startRearScreenWakeup() {
        if (handler != null && keepScreenOnEnabled) {
            // 立即执行第一次唤醒，然后开始持续发送
            handler.post(wakeupRearScreenRunnable);
            LogHelper.d(TAG, "⏰ 背屏持续唤醒已启动 (0.8秒间隔，优化后降低耗电)");
        }
    }

    /**
     * V12.3: 初始杀进程 - 只杀1次，不持续监控
     */
    private void performInitialKills() {

        for (int i = 0; i < INITIAL_KILL_COUNT; i++) {
            final int killNumber = i + 1;

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (taskService != null) {
                        try {
                            taskService.killLauncherProcess();
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

        LogHelper.w(TAG, "═══════════════════════════════════════");
        LogHelper.w(TAG, "⚠ Service onDestroy called");

        // 立即移除前台通知
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        ProjectionOngoingNotifications.cancelAll(getApplicationContext());

        // 清理所有待执行的任务
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        
        // V12.2: 恢复并主动唤醒Launcher
        if (taskService != null) {
            try {

                // 1. 恢复Launcher（unsuspend）
                taskService.enableSubScreenLauncher();

                // 2. 短暂延迟，确保unsuspend生效
                Thread.sleep(300);

                // 3. 主动启动Launcher的Activity来唤醒它

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
                LogHelper.d(TAG, "✓ WakeLock released");
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
        LogHelper.w(TAG, "═══════════════════════════════════════");
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
     */
    public static Notification createServiceNotification(Context context) {
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

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.miroot_main_service_notif_title))
                .setContentText(context.getString(R.string.miroot_main_service_notif_text))
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
        svc.wakeupIntervalMs = RearAssistPrefs.INSTANCE.intervalMs(svc);
    }

    private void loadProximitySensorSetting() {
        proximityController.updateFromPrefs();
        wakeupIntervalMs = RearAssistPrefs.INSTANCE.intervalMs(this);
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
                Toast.makeText(
                        getApplicationContext(),
                        getString(R.string.toast_task_returned_main, appName),
                        Toast.LENGTH_SHORT
                ).show();
            }, 100);
        }

        stopSelf();
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
