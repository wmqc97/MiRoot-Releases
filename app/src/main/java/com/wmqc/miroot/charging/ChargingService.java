package com.wmqc.miroot.charging;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.wmqc.miroot.R;
import com.wmqc.miroot.rear.RearAssistPrefs;
import com.wmqc.miroot.rear.RearAssistService;
import com.wmqc.miroot.rear.RearProjectionProximityGate;
import com.wmqc.miroot.rear.RearSwitchKeeperService;
import com.wmqc.miroot.shell.SwitchToRearQsTileService;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.LyricsTaskTracking;
import com.wmqc.miroot.lyrics.RearScreenWakeManager;
import com.wmqc.miroot.lyrics.RootTaskService;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 监听插电/拔电，在背屏显示充电动画。
 * <p>
 * 含 6s 冷却、插电时协调器与通知打断、常亮模式唤醒循环、设置/恢复广播、
 * TaskService 10×100ms 等待、taskId 轮询 60×30ms 中途重发 am start、拔电结束广播并停循环；
 * 副屏 displayId 探测与 taskId/TaskService 延迟重试。迁屏步骤：单次 {@code service call}、40ms 等待。
 */
public class ChargingService extends Service {

    private static final String TAG = "ChargingService";
    private static final String CHANNEL_ID = "miroot_charging_fgs_silent";
    /** 主渠道异常时兜底；部分 ROM 对 IMPORTANCE_NONE 的前台通知不认可，单独用 LOW 渠道再试。 */
    private static final String CHANNEL_ID_FALLBACK = "miroot_charging_fgs_fb";
    private static final int NOTIF_ID = 10042;

    private static final String PREFS = "MiRootCharging";
    private static final String KEY_ENABLED = "charging_animation_enabled";
    private static final String KEY_DEBUG_MAIN_SCREEN_ONLY = "charging_debug_main_screen_only";

    /** 两次成功迁屏之间的最短间隔（ms）。 */
    private static final long ANIMATION_COOLDOWN_MS = 6000L;
    private static final long RETRY_NO_TASKSERVICE_MS = 3500L;
    private static final long RETRY_NO_TASKID_MS = 2800L;
    private static final int TASK_SERVICE_WAIT_ATTEMPTS = 10;
    private static final int TASK_SERVICE_WAIT_STEP_MS = 100;
    private static final long CHARGING_FLOW_WAKELOCK_MS = 8000L;

    private static volatile ChargingService instance;
    private ITaskService taskService;
    private long lastChargingAnimationTime;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean chargingFlowInProgress = new AtomicBoolean(false);

    private boolean chargingAlwaysOnEnabled;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning;
    private int chargingCheckCounter;
    private int lastRearDisplayIdForWakeup = 1;

    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            LogHelper.d(TAG, "TaskService 已连接（充电动画）");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
            mainHandler.postDelayed(() -> {
                if (instance != null && taskService == null) {
                    LogHelper.d(TAG, "TaskService 断开，1s 后重绑");
                    bindTaskService();
                }
            }, 1000L);
        }
    };

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadChargingPrefsFromDisk();
            LogHelper.d(TAG, "充电动画设置已重载，常亮=" + chargingAlwaysOnEnabled);
        }
    };

    private final BroadcastReceiver resumeChargingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ChargingIntents.ACTION_RESUME_CHARGING_ANIMATION.equals(intent.getAction())) {
                return;
            }
            LogHelper.d(TAG, "收到恢复充电动画广播");
            mainHandler.postDelayed(() -> executor.execute(() -> {
                if (!isAnimationEnabled() || isDebugMainScreenOnly()) {
                    return;
                }
                if (!chargingFlowInProgress.compareAndSet(false, true)) {
                    LogHelper.w(TAG, "恢复充电动画：流程占用中");
                    return;
                }
                try {
                    RearChargingAnimationCoordinator.AnimationType old =
                        RearChargingAnimationCoordinator.startAnimation(
                            RearChargingAnimationCoordinator.AnimationType.CHARGING);
                    if (old == RearChargingAnimationCoordinator.AnimationType.NOTIFICATION) {
                        RearChargingAnimationCoordinator.sendInterruptBroadcast(
                            ChargingService.this,
                            RearChargingAnimationCoordinator.AnimationType.NOTIFICATION);
                    }
                    reloadChargingPrefsFromDisk();
                    runRearChargingFlowOnce(getApplicationContext(), false, false);
                } finally {
                    chargingFlowInProgress.set(false);
                }
            }), 300L);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                executor.execute(() -> onPowerConnected(context));
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                executor.execute(ChargingService.this::onPowerDisconnected);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // 须在任何其他逻辑之前进入前台，否则系统可能报 ForegroundServiceDidNotStartInTimeException。
        ensureForegroundStarted();
        instance = this;
        wakeupHandler = new Handler(Looper.getMainLooper());
        try {
            reloadChargingPrefsFromDisk();
        } catch (Throwable t) {
            LogHelper.w(TAG, "reload prefs in onCreate (可能未解锁 CE 存储): " + t.getMessage());
            chargingAlwaysOnEnabled = false;
        }
        bindTaskService();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, f);
        }
        IntentFilter settingsFilter = new IntentFilter(ChargingIntents.ACTION_RELOAD_CHARGING_SETTINGS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
        }
        IntentFilter resumeFilter = new IntentFilter(ChargingIntents.ACTION_RESUME_CHARGING_ANIMATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resumeChargingReceiver, resumeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resumeChargingReceiver, resumeFilter);
        }
        LogHelper.d(TAG, "ChargingService onCreate");
    }

    private void reloadChargingPrefsFromDisk() {
        chargingAlwaysOnEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(
            ChargingAnimationPrefs.KEY_ALWAYS_ON, false);
    }

    @Override
    public void onDestroy() {
        stopWakeupLoop();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            LogHelper.w(TAG, "unregisterReceiver: " + e.getMessage());
        }
        try {
            unregisterReceiver(settingsReceiver);
        } catch (Exception e) {
            LogHelper.w(TAG, "unregisterReceiver settings: " + e.getMessage());
        }
        try {
            unregisterReceiver(resumeChargingReceiver);
        } catch (Exception e) {
            LogHelper.w(TAG, "unregisterReceiver resume: " + e.getMessage());
        }
        try {
            unbindService(taskServiceConnection);
        } catch (Exception e) {
            LogHelper.w(TAG, "unbindService: " + e.getMessage());
        }
        executor.shutdown();
        instance = null;
        taskService = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        LogHelper.d(TAG, "ChargingService onDestroy");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 与 onCreate 双保险：部分机型/开机场景下须尽快再次声明前台，避免超时杀进程。
        ensureForegroundStarted();
        if (taskService == null) {
            bindTaskService();
        }
        return START_STICKY;
    }

    private static boolean isCurrentlyPlugged(Context ctx) {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent sticky;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sticky = ctx.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                sticky = ctx.registerReceiver(null, filter);
            }
            if (sticky == null) {
                return false;
            }
            int plug = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return plug != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void runChargingFlowWrapped(Context appContext, boolean allowDeferredRetry, boolean fromPowerConnect) {
        if (!chargingFlowInProgress.compareAndSet(false, true)) {
            LogHelper.d(TAG, "充电动画流程执行中，忽略重叠调用");
            return;
        }
        try {
            reloadChargingPrefsFromDisk();
            if (fromPowerConnect) {
                lastChargingAnimationTime = System.currentTimeMillis();
                RearChargingAnimationCoordinator.AnimationType old =
                    RearChargingAnimationCoordinator.startAnimation(
                        RearChargingAnimationCoordinator.AnimationType.CHARGING);
                if (old == RearChargingAnimationCoordinator.AnimationType.NOTIFICATION) {
                    RearChargingAnimationCoordinator.sendInterruptBroadcast(
                        appContext, RearChargingAnimationCoordinator.AnimationType.NOTIFICATION);
                }
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                boolean locked = km != null && km.isKeyguardLocked();
                LogHelper.d(TAG, locked ? "锁屏插电：充电动画（仅记录）" : "亮屏插电：充电动画");
            }
            boolean moved = runRearChargingFlowOnce(appContext, allowDeferredRetry, fromPowerConnect);
            if (moved && fromPowerConnect && chargingAlwaysOnEnabled) {
                mainHandler.post(this::startWakeupAndUpdateLoop);
            }
        } finally {
            chargingFlowInProgress.set(false);
        }
    }

    /**
     * 尽快、可重复调用（onCreate + onStartCommand 双入口），降低 ForegroundServiceDidNotStartInTimeException 概率。
     * 若主路径连续失败必须再走兜底 startForeground，否则系统仍会超时杀进程。
     */
    private void ensureForegroundStarted() {
        try {
            createNotificationChannelSafe();
            startForegroundWithTypeInner();
            return;
        } catch (Throwable t) {
            LogHelper.e(TAG, "ensureForegroundStarted primary failed", t);
        }
        try {
            createNotificationChannelSafe();
            startForegroundWithTypeInner();
            return;
        } catch (Throwable t) {
            LogHelper.e(TAG, "ensureForegroundStarted retry failed", t);
        }
        try {
            startForegroundFallbackLastResort();
        } catch (Throwable t) {
            LogHelper.e(TAG, "ensureForegroundStarted last resort failed", t);
            throw new IllegalStateException("ChargingService startForeground failed", t);
        }
    }

    private void createNotificationChannelSafe() {
        try {
            createNotificationChannel();
        } catch (Throwable t) {
            LogHelper.w(TAG, "createNotificationChannel: " + t.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        // LOW + setSilent：满足「可见渠道」类检查，同时尽量不打扰用户（部分机型对 NONE 前台不通过）。
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.charging_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        );
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.charging_service_notif_subtitle))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void startForegroundWithTypeInner() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void ensureFallbackChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID_FALLBACK,
            getString(R.string.charging_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        );
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildFallbackNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID_FALLBACK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.charging_service_notif_subtitle))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void startForegroundFallbackLastResort() {
        ensureFallbackChannel();
        Notification n = buildFallbackNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void bindTaskService() {
        try {
            Intent i = new Intent(this, RootTaskService.class);
            bindService(i, taskServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            LogHelper.e(TAG, "绑定 RootTaskService 失败", e);
        }
    }

    private boolean isAnimationEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    private boolean isDebugMainScreenOnly() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DEBUG_MAIN_SCREEN_ONLY, false);
    }

    private void onPowerConnected(Context context) {
        if (!isAnimationEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChargingAnimationTime < ANIMATION_COOLDOWN_MS) {
            LogHelper.d(TAG, "充电动画冷却中，跳过");
            return;
        }
        if (isDebugMainScreenOnly()) {
            lastChargingAnimationTime = now;
            int level = getBatteryLevel(context);
            acquireWakeLockDebug();
            try {
                android.content.Intent intent = new android.content.Intent(this, RearScreenChargingActivity.class);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(RearScreenChargingActivity.EXTRA_BATTERY_LEVEL, level);
                intent.putExtra(RearScreenChargingActivity.EXTRA_DEBUG_MAIN_PREVIEW, true);
                startActivity(intent);
                LogHelper.d(TAG, "主屏调试模式：已直接启动充电动画（未迁背屏）");
            } catch (Exception e) {
                LogHelper.e(TAG, "主屏调试充电动画启动失败", e);
            } finally {
                releaseChargingWakeLock();
            }
            return;
        }
        Context app = context.getApplicationContext();
        runChargingFlowWrapped(app, true, true);
    }

    /**
     * @return 是否已成功解析 taskId 并执行迁屏命令
     */
    private boolean runRearChargingFlowOnce(Context context, boolean allowDeferredRetry, boolean fromPowerConnect) {
        if (!waitTaskServiceForChargingFlow()) {
            LogHelper.w(TAG, "TaskService 未就绪（10×100ms）");
            if (allowDeferredRetry && isCurrentlyPlugged(context)) {
                executor.execute(() -> delayedRetryAfterNoTaskService(context));
            }
            return false;
        }
        int level = getBatteryLevel(context);
        acquireChargingWakeLock();
        boolean movedOk = false;
        boolean assistPausedHere = false;
        try {
            int rearDisplayId = resolveRearDisplayIdForMove();
            lastRearDisplayIdForWakeup = rearDisplayId;
            int rearRestoreId = resolveRearTaskIdToRestore(rearDisplayId);
            if (rearRestoreId > 0) {
                RearAssistService.pauseMonitoringForCharging();
                RearProjectionProximityGate.pauseForCharging();
                RearSwitchKeeperService.pauseMonitoring();
                assistPausedHere = true;
            }

            ChargingOfficialSubscreen.applyDisableBeforeChargingFlow(context, taskService);

            String component = getPackageName() + "/" + RearScreenChargingActivity.class.getName();
            String mainCmd;
            if (rearRestoreId > 0) {
                mainCmd = String.format(
                    Locale.US,
                    "am start -n %s --ei %s %d --ei %s %d --ei %s %d",
                    component,
                    RearScreenChargingActivity.EXTRA_BATTERY_LEVEL,
                    level,
                    RearScreenChargingActivity.EXTRA_REAR_TASK_ID,
                    rearRestoreId,
                    RearScreenChargingActivity.EXTRA_REAR_DISPLAY_ID,
                    rearDisplayId
                );
                LogHelper.d(TAG, "充电动画将携带 rearTaskId=" + rearRestoreId + " 以便结束后恢复投屏");
            } else {
                mainCmd = String.format(
                    Locale.US,
                    "am start -n %s --ei %s %d --ei %s %d",
                    component,
                    RearScreenChargingActivity.EXTRA_BATTERY_LEVEL,
                    level,
                    RearScreenChargingActivity.EXTRA_REAR_DISPLAY_ID,
                    rearDisplayId
                );
            }
            LogHelper.d(TAG, "主屏启动充电动画 Activity");
            taskService.executeShellCommand(mainCmd);

            String taskId = pollChargingTaskId(mainCmd);
            if (taskId != null) {
                try {
                    if (RearAssistPrefs.INSTANCE.isKeepScreenOnEnabled(context.getApplicationContext())) {
                        RearScreenWakeManager.getInstance().startWakeService(
                                context.getApplicationContext(), RearScreenChargingActivity.class);
                        LogHelper.d(TAG, "迁背屏前已启动背屏常亮服务（充电动画）");
                    } else {
                        LogHelper.d(TAG, "背屏常亮关，跳过迁背屏前常亮服务（充电动画）");
                    }
                } catch (Exception e) {
                    LogHelper.w(TAG, "迁背屏前启动背屏常亮服务失败: " + e.getMessage());
                }
                String moveCmd = "service call activity_task 50 i32 " + taskId + " i32 " + rearDisplayId;
                taskService.executeShellCommand(moveCmd);
                Thread.sleep(40);
                LogHelper.d(TAG, "充电动画迁往背屏 displayId=" + rearDisplayId + " taskId=" + taskId);
                movedOk = true;
                assistPausedHere = false;
                if (!fromPowerConnect) {
                    lastChargingAnimationTime = System.currentTimeMillis();
                }
            } else {
                LogHelper.w(TAG, "未解析到 RearScreenChargingActivity taskId");
                if (allowDeferredRetry && isCurrentlyPlugged(context)) {
                    executor.execute(() -> delayedRetryAfterNoTaskId(context));
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "显示充电动画失败", e);
        } finally {
            if (assistPausedHere) {
                RearAssistService.resumeMonitoringAfterCharging();
                RearProjectionProximityGate.resumeAfterCharging();
                RearSwitchKeeperService.resumeMonitoring();
            }
            releaseChargingWakeLock();
        }
        if (movedOk && chargingAlwaysOnEnabled && !fromPowerConnect) {
            mainHandler.post(this::startWakeupAndUpdateLoop);
        }
        return movedOk;
    }

    private void delayedRetryAfterNoTaskService(Context context) {
        try {
            Thread.sleep(RETRY_NO_TASKSERVICE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!isAnimationEnabled() || isDebugMainScreenOnly()) {
            return;
        }
        if (!isCurrentlyPlugged(context)) {
            return;
        }
        LogHelper.w(TAG, "TaskService 延迟重试一轮");
        runChargingFlowWrapped(context, false, false);
    }

    private void delayedRetryAfterNoTaskId(Context context) {
        try {
            Thread.sleep(RETRY_NO_TASKID_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!isAnimationEnabled() || isDebugMainScreenOnly()) {
            return;
        }
        if (!isCurrentlyPlugged(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastChargingAnimationTime > 0 && now - lastChargingAnimationTime < ANIMATION_COOLDOWN_MS) {
            return;
        }
        LogHelper.w(TAG, "taskId 延迟重试一轮（中途重发 am start）");
        runChargingFlowWrapped(context, false, false);
    }

    private void onPowerDisconnected() {
        stopWakeupLoop();
        try {
            Intent i = new Intent(ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            LogHelper.d(TAG, "已发送结束充电动画广播");
        } catch (Exception e) {
            LogHelper.w(TAG, "发送结束广播失败: " + e.getMessage());
        }
    }

    private int resolveRearTaskIdToRestore(int rearDisplayId) {
        if (taskService == null) {
            return -1;
        }
        String lastLine = LyricsTaskTracking.getLastMovedLine();
        if (lastLine == null) {
            lastLine = SwitchToRearQsTileService.getLastMovedTask();
        }
        if (lastLine == null) {
            return -1;
        }
        int lastTid = LyricsTaskTracking.getLastTaskId();
        if (lastTid <= 0) {
            try {
                int c = lastLine.indexOf(':');
                if (c > 0) {
                    lastTid = Integer.parseInt(lastLine.substring(c + 1).trim());
                }
            } catch (Exception ignored) {
            }
        }
        if (lastTid <= 0) {
            return -1;
        }
        try {
            String fg = taskService.getForegroundAppOnDisplay(rearDisplayId);
            if (fg != null && fg.contains("RearScreenChargingActivity")) {
                return lastTid;
            }
            if (fg == null) {
                return LyricsTaskTracking.hasActiveTask() ? lastTid : -1;
            }
            if (fg.equals(lastLine)) {
                return lastTid;
            }
            int colon = fg.indexOf(':');
            if (colon > 0) {
                String pkg = fg.substring(0, colon).trim();
                String tidStr = fg.substring(colon + 1).trim();
                String lastPkg = LyricsTaskTracking.getLastMovedPackage();
                if (lastPkg == null && lastLine.contains(":")) {
                    lastPkg = lastLine.substring(0, lastLine.indexOf(':')).trim();
                }
                if (lastPkg != null && lastPkg.equals(pkg)) {
                    try {
                        return Integer.parseInt(tidStr);
                    } catch (NumberFormatException ignored) {
                        return lastTid;
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "resolveRearTaskIdToRestore: " + e.getMessage());
        }
        return -1;
    }

    private int resolveRearDisplayIdForMove() {
        try {
            String cmd =
                "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";
            String out = taskService.executeShellCommandWithResult(cmd);
            if (out != null) {
                String t = out.trim().split("\\s")[0];
                if (!t.isEmpty()) {
                    int id = Integer.parseInt(t);
                    if (id > 0) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "resolveRearDisplayIdForMove: " + e.getMessage());
        }
        return 1;
    }

    /** 轮询 {@code am stack list | grep RearScreenChargingActivity} 取 taskId；60×30ms，第 20/40 次重发 am start。 */
    private String pollChargingTaskId(String retryCmd) {
        Pattern p = Pattern.compile("taskId=(\\d+)");
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                Thread.sleep(30);
                String result = taskService.executeShellCommandWithResult(
                    "am stack list | grep RearScreenChargingActivity");
                if (result != null && !result.trim().isEmpty()) {
                    Matcher m = p.matcher(result);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
                if (attempt == 19 || attempt == 39) {
                    taskService.executeShellCommand(retryCmd);
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "poll taskId: " + e.getMessage());
                break;
            }
        }
        return null;
    }

    private boolean waitTaskServiceForChargingFlow() {
        if (taskService != null) {
            return true;
        }
        bindTaskService();
        for (int i = 0; i < TASK_SERVICE_WAIT_ATTEMPTS; i++) {
            try {
                Thread.sleep(TASK_SERVICE_WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (taskService != null) {
                return true;
            }
        }
        return taskService != null;
    }

    private static int getBatteryLevel(Context context) {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 单次流程内 PARTIAL_WAKE_LOCK，约 8s。 */
    private void acquireChargingWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:ChargingWake");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(CHARGING_FLOW_WAKELOCK_MS);
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "WakeLock: " + t.getMessage());
        }
    }

    private void acquireWakeLockDebug() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:ChargingDebug");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(30_000);
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "WakeLock debug: " + e.getMessage());
        }
    }

    private void releaseChargingWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void startWakeupAndUpdateLoop() {
        if (isWakeupRunning) {
            LogHelper.w(TAG, "Wakeup loop 已在运行");
            return;
        }
        reloadChargingPrefsFromDisk();
        if (!chargingAlwaysOnEnabled) {
            return;
        }
        isWakeupRunning = true;
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWakeupRunning) {
                    return;
                }
                reloadChargingPrefsFromDisk();
                if (!chargingAlwaysOnEnabled) {
                    stopWakeupLoop();
                    return;
                }
                chargingCheckCounter++;
                if (chargingCheckCounter >= 50) {
                    chargingCheckCounter = 0;
                    if (taskService != null) {
                        try {
                            String rearFg = taskService.getForegroundAppOnDisplay(lastRearDisplayIdForWakeup);
                            if (rearFg == null || !rearFg.contains("RearScreenChargingActivity")) {
                                LogHelper.d(TAG, "背屏非充电动画，停止常亮循环 rearFg=" + rearFg);
                                stopWakeupLoop();
                                return;
                            }
                        } catch (Exception e) {
                            LogHelper.w(TAG, "检查背屏前台失败: " + e.getMessage());
                        }
                    }
                }
                try {
                    if (taskService != null) {
                        taskService.executeShellCommand(
                            "input -d " + lastRearDisplayIdForWakeup + " keyevent KEYCODE_WAKEUP");
                    }
                } catch (Throwable t) {
                    LogHelper.w(TAG, "wakeup 命令失败: " + t.getMessage());
                }
                try {
                    int batteryLevel = getBatteryLevel(getApplicationContext());
                    RearScreenChargingActivity.updateBatteryLevelStatic(batteryLevel);
                } catch (Exception e) {
                    LogHelper.w(TAG, "更新电量失败: " + e.getMessage());
                }
                wakeupHandler.postDelayed(this, 800L);
            }
        };
        wakeupHandler.post(wakeupRunnable);
        LogHelper.d(TAG, "常亮唤醒与电量更新循环已启动");
    }

    private void stopWakeupLoop() {
        isWakeupRunning = false;
        chargingCheckCounter = 0;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
    }
}
