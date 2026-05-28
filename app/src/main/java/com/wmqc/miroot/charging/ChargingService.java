package com.wmqc.miroot.charging;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.wmqc.miroot.lyrics.DisplayInfoCache;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.RootTaskService;
import com.wmqc.miroot.rear.MiRootNotificationIds;
import com.wmqc.miroot.rear.OfficialDisableAfterLaunch;
import com.wmqc.miroot.rear.RearActivityLaunchSpec;
import com.wmqc.miroot.rear.RearProjectionLaunchSequence;
import com.wmqc.miroot.rear.RearSwitchKeeperService;
import com.wmqc.miroot.shell.SwitchToRearQsTileService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * 充电动画服务（流程对齐 MiRoot-3.4）：
 * <ul>
 *   <li>插电：解析 task → 暂停 Keeper → 优先背屏直启，失败主屏占位迁屏(3.4) → 禁用官方背屏 → 通知 inflate</li>
 *   <li>常亮：800ms 周期 KEYCODE_WAKEUP + 电量刷新</li>
 *   <li>拔电：结束动画广播 + 停唤醒循环</li>
 * </ul>
 */
public class ChargingService extends Service {

    private static final String TAG = "ChargingService";
    private static final int NOTIF_ID = MiRootNotificationIds.MAIN_SERVICE_NOTIFICATION_ID;

    private static final long CHARGING_ANIMATION_COOLDOWN_MS = 6000L;
    private static final long CHARGING_FLOW_WAKELOCK_MS = 8000L;
    private static final int TASK_SERVICE_READY_RETRY_MAX = 10;
    private static final long TASK_SERVICE_READY_RETRY_STEP_MS = 100L;
    private static final int REAR_DISPLAY_ID = 1;

    private static volatile ChargingService instance;

    private ITaskService taskService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private long lastChargingAnimationTime;
    private boolean chargingAlwaysOnEnabled;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning;
    private int chargingCheckCounter;

    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }

    public static boolean isInstanceRunning() {
        return instance != null;
    }

    public static void refreshMainForegroundNotificationIfRunning() {
        ChargingService cs = instance;
        if (cs == null) {
            return;
        }
        try {
            cs.startForeground(NOTIF_ID, RearSwitchKeeperService.createServiceNotification(cs));
        } catch (Exception e) {
            LogHelper.w(TAG, "refreshMainForegroundNotificationIfRunning failed", e);
        }
    }

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            LogHelper.d(TAG, "TaskService connected (charging)");
            try {
                DisplayInfoCache.getInstance().initialize(taskService);
            } catch (Exception e) {
                LogHelper.w(TAG, "init DisplayInfoCache failed: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
            mainHandler.postDelayed(() -> {
                if (instance != null && taskService == null) {
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
                try {
                    if (!ChargingAnimationPrefs.isEnabled(getApplicationContext())) {
                        return;
                    }
                    int batteryLevel = getBatteryLevel(getApplicationContext());
                    RearChargingAnimationCoordinator.startAnimation(
                        RearChargingAnimationCoordinator.AnimationType.CHARGING);
                    showChargingOnRearScreen(batteryLevel, false);
                    reloadChargingPrefsFromDisk();
                    if (chargingAlwaysOnEnabled) {
                        mainHandler.post(ChargingService.this::startWakeupAndUpdateLoop);
                    }
                } catch (Throwable t) {
                    LogHelper.w(TAG, "resume charging failed: " + t.getMessage());
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
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                executor.execute(() -> onPowerConnected(context.getApplicationContext()));
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                executor.execute(ChargingService.this::onPowerDisconnected);
            }
        }
    };

    private final BroadcastReceiver stopWakeupReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ChargingIntents.ACTION_STOP_CHARGING_WAKEUP.equals(intent.getAction())) {
                return;
            }
            LogHelper.d(TAG, "收到停止常亮唤醒广播");
            stopWakeupLoop();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wakeupHandler = new Handler(Looper.getMainLooper());
        reloadChargingPrefsFromDisk();
        bindTaskService();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, filter);
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

        IntentFilter stopWakeupFilter = new IntentFilter(ChargingIntents.ACTION_STOP_CHARGING_WAKEUP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopWakeupReceiver, stopWakeupFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopWakeupReceiver, stopWakeupFilter);
        }

        startForeground(NOTIF_ID, RearSwitchKeeperService.createServiceNotification(this));
        LogHelper.d(TAG, "ChargingService created");
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
            unregisterReceiver(stopWakeupReceiver);
        } catch (Exception e) {
            LogHelper.w(TAG, "unregisterReceiver stopWakeup: " + e.getMessage());
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (taskService == null) {
            bindTaskService();
        }
        return START_STICKY;
    }

    private void bindTaskService() {
        try {
            bindService(new Intent(this, RootTaskService.class), taskServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            LogHelper.w(TAG, "bindTaskService failed: " + e.getMessage());
        }
    }

    private void reloadChargingPrefsFromDisk() {
        chargingAlwaysOnEnabled = ChargingAnimationPrefs.isAlwaysOn(getApplicationContext());
    }

    private void onPowerConnected(Context appContext) {
        if (!ChargingAnimationPrefs.isEnabled(appContext)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChargingAnimationTime < CHARGING_ANIMATION_COOLDOWN_MS) {
            LogHelper.d(TAG, "充电动画冷却中，跳过");
            return;
        }

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean isLocked = km != null && km.isKeyguardLocked();
        int level = getBatteryLevel(appContext);
        lastChargingAnimationTime = now;

        LogHelper.d(TAG, "插电 battery=" + level + "% locked=" + isLocked);

        RearChargingAnimationCoordinator.AnimationType old =
            RearChargingAnimationCoordinator.startAnimation(
                RearChargingAnimationCoordinator.AnimationType.CHARGING);
        if (old == RearChargingAnimationCoordinator.AnimationType.NOTIFICATION) {
            RearChargingAnimationCoordinator.sendInterruptBroadcast(
                appContext,
                RearChargingAnimationCoordinator.AnimationType.NOTIFICATION);
        }

        showChargingOnRearScreen(level, isLocked);

        reloadChargingPrefsFromDisk();
        if (chargingAlwaysOnEnabled) {
            mainHandler.post(this::startWakeupAndUpdateLoop);
        }
    }

    private void onPowerDisconnected() {
        LogHelper.d(TAG, "拔电，结束充电动画");
        stopWakeupLoop();
        finishChargingAnimation();
    }

    private void finishChargingAnimation() {
        try {
            Intent i = new Intent(ChargingIntents.ACTION_FINISH_CHARGING_ANIMATION);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Exception e) {
            LogHelper.w(TAG, "finishChargingAnimation failed: " + e.getMessage());
        }
    }

    private static int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) {
            return 0;
        }
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private void acquireWakeLock(long timeoutMs) {
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
                wakeLock.acquire(timeoutMs);
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "acquireWakeLock failed: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "releaseWakeLock failed: " + t.getMessage());
        }
    }

    private void showChargingOnRearScreen(int level, boolean isLocked) {
        showChargingOnRearScreenWithRetry(level, isLocked, 0);
    }

    private void showChargingOnRearScreenWithRetry(int level, boolean isLocked, int retryCount) {
        if (taskService == null) {
            if (retryCount < TASK_SERVICE_READY_RETRY_MAX) {
                mainHandler.postDelayed(
                    () -> showChargingOnRearScreenWithRetry(level, isLocked, retryCount + 1),
                    TASK_SERVICE_READY_RETRY_STEP_MS);
            }
            return;
        }

        acquireWakeLock(CHARGING_FLOW_WAKELOCK_MS);
        try {
            int rearTaskId = resolveRearTaskIdForCharging();
            if (rearTaskId > 0) {
                RearSwitchKeeperService.pauseMonitoring();
            }

            int rearDisplayId = REAR_DISPLAY_ID;

            String component = getPackageName() + "/" + RearScreenChargingActivity.class.getName();
            String mainCmd = "am start -n " + component
                + " --ei batteryLevel " + level
                + " --ei rearTaskId " + rearTaskId
                + " --ei rearDisplayId " + rearDisplayId;
            String directCmd = "am start --display " + rearDisplayId + " -n " + component
                + " --ei batteryLevel " + level
                + " --ei rearTaskId " + rearTaskId
                + " --ei rearDisplayId " + rearDisplayId;

            RearActivityLaunchSpec launchSpec = new RearActivityLaunchSpec(
                "RearScreenChargingActivity",
                component,
                mainCmd,
                directCmd,
                false,
                rearDisplayId,
                OfficialDisableAfterLaunch.CHARGING_SUBSCREEN,
                true
            );

            boolean launched = RearProjectionLaunchSequence.runPreferDirectRearLaunchWithPlaceholderFallback(
                this,
                taskService,
                launchSpec,
                (displayId, usedPlaceholderFallback, taskId) -> notifyChargingTaskMovedToRear(displayId)
            );
            if (!launched) {
                LogHelper.e(TAG, "充电动画启动失败（背屏直启与主屏占位迁屏均失败）");
                return;
            }

            if (isLocked) {
                LogHelper.d(TAG, "锁屏状态，充电动画已在背屏启动");
            } else {
                LogHelper.d(TAG, "亮屏状态，充电动画已在背屏启动");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "showChargingOnRearScreen failed: " + e.getMessage());
        } finally {
            releaseWakeLock();
        }
    }

    /**
     * 对齐 3.4：仅根据 {@link SwitchToRearQsTileService#getLastMovedTask()} 与背屏前台判断。
     */
    private int resolveRearTaskIdForCharging() {
        String lastTask = SwitchToRearQsTileService.getLastMovedTask();
        if (lastTask != null && lastTask.contains(":") && !lastTask.contains("RearScreenChargingActivity")) {
            try {
                String[] parts = lastTask.split(":");
                int tid = Integer.parseInt(parts[parts.length - 1].trim());
                if (taskService != null && taskService.isTaskOnDisplay(tid, REAR_DISPLAY_ID)) {
                    return tid;
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "resolveRearTaskIdForCharging lastMoved: " + e.getMessage());
            }
        }
        try {
            if (taskService == null) {
                return -1;
            }
            String stack = taskService.executeShellCommandWithResult("am stack list");
            if (stack == null || stack.isEmpty()) {
                return -1;
            }
            boolean inTargetDisplay = false;
            for (String line : stack.split("\n")) {
                if (line.startsWith("RootTask")) {
                    int did = parseRootTaskDisplayId(line);
                    inTargetDisplay = (did == REAR_DISPLAY_ID);
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
            LogHelper.w(TAG, "resolveRearTaskIdForCharging stack: " + e.getMessage());
        }
        return -1;
    }

    private static int parseRootTaskDisplayId(String line) {
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

    private void notifyChargingTaskMovedToRear(int rearDisplayId) {
        try {
            Intent moved = new Intent(ChargingIntents.ACTION_NOTIFY_CHARGING_TASK_MOVED_TO_REAR);
            moved.setPackage(getPackageName());
            moved.putExtra(RearScreenChargingActivity.EXTRA_REAR_DISPLAY_ID, rearDisplayId);
            sendBroadcast(moved);
            LogHelper.d(TAG, "已通知充电动画 task 迁到背屏，请求立即 inflate UI");
        } catch (Exception e) {
            LogHelper.w(TAG, "notifyChargingTaskMovedToRear failed: " + e.getMessage());
        }
    }

    /** 对齐 3.4 常亮：800ms 周期唤醒 + 电量更新（仅常亮开关开启时）。 */
    private void startWakeupAndUpdateLoop() {
        if (isWakeupRunning) {
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
                            String rearFg = taskService.getForegroundAppOnDisplay(REAR_DISPLAY_ID);
                            if (rearFg == null || !rearFg.contains("RearScreenChargingActivity")) {
                                boolean chargingSessionActive =
                                    RearChargingAnimationCoordinator.isAnimationPlaying()
                                        || RearChargingAnimationCoordinator.isChargingProtectionActive()
                                        || RearScreenChargingActivity.getCurrentInstance() != null;
                                if (chargingSessionActive) {
                                    LogHelper.d(TAG, "背屏息屏/前台暂不可见，充电会话仍活跃，继续常亮循环");
                                } else {
                                    LogHelper.d(TAG, "背屏非充电动画，停止常亮循环");
                                    stopWakeupLoop();
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            LogHelper.w(TAG, "检查充电动画状态失败: " + e.getMessage());
                        }
                    }
                }
                try {
                    if (taskService != null) {
                        taskService.executeShellCommand(
                            "input -d " + REAR_DISPLAY_ID + " keyevent KEYCODE_WAKEUP");
                    }
                } catch (Throwable t) {
                    LogHelper.w(TAG, "wakeup failed: " + t.getMessage());
                }
                try {
                    RearScreenChargingActivity.updateBatteryLevelStatic(
                        getBatteryLevel(getApplicationContext()));
                } catch (Exception e) {
                    LogHelper.w(TAG, "update battery failed: " + e.getMessage());
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
