/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: ???T0??????
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.wmqc.miroot.lyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Build;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.PendingIntent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

import com.wmqc.miroot.MainActivity;
import com.wmqc.miroot.rear.ProjectionNotificationStopReceiver;
import com.wmqc.miroot.R;
import com.wmqc.miroot.car.RearScreenCarControlActivity;
import com.wmqc.miroot.rear.MiRootNotificationIds;
import com.wmqc.miroot.rear.RearAssistPrefs;
import com.wmqc.miroot.rear.RearSwitchKeeperService;
import com.wmqc.miroot.rear.desktop.RearScreenDesktopActivity;

import java.util.Set;

/**
 * ?????????
 * ?800ms??????KEYCODE_WAKEUP?????????????????????
 * ????Activity??????Activity????????
 * 与 Flutter {@code flutter.keep_screen_on_enabled} 同步；前台通知合并「投屏显示中」与投屏常亮状态，点按结束投屏。
 */
public class RearScreenWakeService extends Service {
    private static final String TAG = "RearScreenWakeService";

    /** 与 [com.wmqc.miroot.rear.RearSwitchKeeperService] 同名，供功能页等统一下发「投屏常亮」开关。 */
    public static final String ACTION_SET_KEEP_SCREEN_ON_ENABLED = "ACTION_SET_KEEP_SCREEN_ON_ENABLED";
    /**
     * 音乐投屏等场景下由本服务发送 KEYCODE_WAKEUP 时为 true；
     * 供 {@link com.wmqc.miroot.record.RearScreenRecordService} 避免与录制侧重复唤醒。
     */
    private static volatile boolean sWakeupLoopActive = false;

    /** @return 投屏常亮唤醒循环是否正在运行（录制应跳过自行发送唤醒） */
    public static boolean isWakeupLoopActive() {
        return sWakeupLoopActive;
    }

    /** 与 {@link com.wmqc.miroot.rear.ProjectionOngoingNotifications#WAKE_PROJECTION_COMBINED_NOTIF_ID} 一致 */
    public static final int NOTIFICATION_ID = MiRootNotificationIds.MUSIC_OR_CAR_PROJECTION_NOTIFICATION_ID;
    private static final String NOTIFICATION_CHANNEL_ID = "rear_screen_wake_channel";

    private ITaskService taskService;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private Runnable rebindRunnable;
    private boolean isRunning = false;
    private boolean taskServiceBound = false;
    // ??????????????/??
    private long lastBindAttemptMs = 0L;
    private static final long BIND_THROTTLE_MS = 3000;
    // SCREEN_OFF ???????????????
    private static final long WAKE_RETRY_DELAY_MS = 250;
    private static final int WAKE_RETRY_COUNT = 4;
    
    private int getWakeIntervalMs() {
        return RearAssistPrefs.INSTANCE.intervalMs(this);
    }

    private int getAdaptiveWakeIntervalMs() {
        int base = Math.max(600, getWakeIntervalMs());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return Math.min(base * 2, 6000);
            }
        } catch (Throwable ignored) {
        }
        return base;
    }

    /** 与「始终常亮」一致：不以 Activity 反射轮询投屏是否存活，仅由注册表 + 生命周期启停服务。 */
    private boolean hasProjectionRegistration() {
        return RearScreenWakeManager.getInstance().hasRegisteredActivities();
    }

    // ???????????????????
    private BroadcastReceiver screenOffReceiver;
    
    // TaskService????
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            taskServiceBound = true;
            LogHelper.d(TAG, "? TaskService???");
            
            startWakeupLoop();
            updateForegroundNotification();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            LogHelper.w(TAG, "?? TaskService????");
            taskService = null;
            taskServiceBound = false;
            // ??????????????????
            lastBindAttemptMs = 0L;
            
            // ???????
            if (rebindRunnable == null) {
                rebindRunnable = () -> {
                    LogHelper.d(TAG, "?? ??????TaskService...");
                    bindTaskService();
                };
            }
            if (wakeupHandler != null) {
                wakeupHandler.removeCallbacks(rebindRunnable);
                wakeupHandler.postDelayed(rebindRunnable, 1000);
            } else {
                new Handler(Looper.getMainLooper()).postDelayed(rebindRunnable, 1000);
            }
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        LogHelper.d(TAG, "SVC: onCreate");
        
        wakeupHandler = new Handler(Looper.getMainLooper());
        
        updateForegroundNotification();
        
        // ???????????????????
        registerScreenOffReceiver();
        
        // ??TaskService
        bindTaskService();
    }
    
    /**
     * ???????????????????
     */
    private void registerScreenOffReceiver() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    // ???????????- ????????
                    LogHelper.d(TAG, "EVT: SCREEN_OFF -> send WAKEUP");
                    handleScreenOff();
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, filter);
            }
            LogHelper.d(TAG, "EVT: SCREEN_OFF receiver registered");
        } catch (Exception e) {
            LogHelper.e(TAG, "EVT: SCREEN_OFF receiver register failed", e);
        }
    }
    
    /**
     * ??????????????
     */
    private void handleScreenOff() {
        // ??????????
        if (!isKeepScreenOnEnabled()) {
            LogHelper.d(TAG, "EVT: SCREEN_OFF ignored (keep_screen_on_disabled)");
            return;
        }

        // ?????????????????????AlwaysWakeUpService?????
        if (taskService != null) {
            try {
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                LogHelper.d(TAG, "WAKE: sent (screen_off immediate)");
                
                // ?????????????????????
                wakeupHandler.postDelayed(() -> {
                    // ???? isRunning??? SCREEN_OFF ??? loop ????????
                    if (taskService != null && isKeepScreenOnEnabled()) {
                        try {
                            taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                            LogHelper.d(TAG, "WAKE: sent (screen_off delayed)");
                        } catch (Exception e) {
                            LogHelper.w(TAG, "WAKE: delayed send failed", e);
                        }
                    }
                }, 100); // 100ms??
            } catch (Exception e) {
                LogHelper.w(TAG, "WAKE: immediate send failed", e);
            }
        } else {
            LogHelper.w(TAG, "WAKE: TaskService null (screen_off), try rebind");
            // ??????????????????????????
            forceBindTaskService();
            scheduleWakeRetries("screen_off");
        }
    }

    private void scheduleWakeRetries(String reason) {
        if (wakeupHandler == null) return;
        for (int i = 1; i <= WAKE_RETRY_COUNT; i++) {
            final int attempt = i;
            wakeupHandler.postDelayed(() -> {
                if (!isKeepScreenOnEnabled()) return;
                if (taskService == null) {
                    forceBindTaskService();
                    return;
                }
                try {
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    LogHelper.d(TAG, "WAKE: retry sent (" + reason + ", attempt=" + attempt + ")");
                } catch (Throwable t) {
                    LogHelper.w(TAG, "WAKE: retry failed (" + reason + ", attempt=" + attempt + ")", t);
                    forceBindTaskService();
                }
            }, WAKE_RETRY_DELAY_MS * i);
        }
    }
    
    /**
     * ?? TaskService??? RootTaskService?
     */
    private void bindTaskService() {
        try {
            if (taskService != null) {
                return;
            }

            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastBindAttemptMs < BIND_THROTTLE_MS) {
                return;
            }
            lastBindAttemptMs = now;
            
            LogHelper.d(TAG, "BIND: bind RootTaskService");
            Intent intent = new Intent(this, RootTaskService.class);
            taskServiceBound = bindService(intent, taskServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            LogHelper.e(TAG, "BIND: bind TaskService failed", e);
        }
    }

    private void forceBindTaskService() {
        lastBindAttemptMs = 0L;
        bindTaskService();
    }
    
    /** Activity 在已注册投屏后调用，刷新合并通知文案（如切换投屏常亮开关）。 */
    public static void requestNotificationRefresh(Context context) {
        Context app = context.getApplicationContext();
        app.startService(new Intent(app, RearScreenWakeService.class));
    }

    private void ensureWakeNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.projection_wake_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(getString(R.string.projection_wake_channel_desc));
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 按 WakeManager 注册表路由停止投屏通知。
     */
    private PendingIntent buildStopProjectionContentIntent() {
        Set<String> reg = RearScreenWakeManager.getInstance().getRegisteredActivities();
        boolean hasLyrics = reg.contains(RearScreenLyricsActivity.class.getName());
        boolean hasCar = reg.contains(RearScreenCarControlActivity.class.getName());
        boolean hasDesktop = reg.contains(RearScreenDesktopActivity.class.getName());
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (hasLyrics) {
            Intent i = new Intent(this, ProjectionNotificationStopReceiver.class);
            i.setAction(ProjectionNotificationStopReceiver.ACTION_STOP_MUSIC_PROJECTION_FROM_NOTIFICATION);
            return PendingIntent.getBroadcast(this, 20, i, piFlags);
        }
        if (hasCar) {
            Intent i = new Intent(this, ProjectionNotificationStopReceiver.class);
            i.setAction(ProjectionNotificationStopReceiver.ACTION_STOP_CAR_PROJECTION_FROM_NOTIFICATION);
            return PendingIntent.getBroadcast(this, 21, i, piFlags);
        }
        if (hasDesktop) {
            Intent i = new Intent(this, RearSwitchKeeperService.class);
            i.setAction(RearSwitchKeeperService.ACTION_RETURN_TO_MAIN);
            return PendingIntent.getService(this, 23, i, piFlags);
        }
        // 背屏桌面→应用投屏：桌面已注销 Wake 注册，但 Wake 前台通知可能仍在；此时由 Keeper 接管
        if (RearSwitchKeeperService.isRunning()) {
            Intent i = new Intent(this, RearSwitchKeeperService.class);
            i.setAction(RearSwitchKeeperService.ACTION_RETURN_TO_MAIN);
            return PendingIntent.getService(this, 24, i, piFlags);
        }
        Intent fallback = new Intent(this, MainActivity.class);
        fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 22, fallback, piFlags);
    }

    private void updateForegroundNotification() {
        try {
            ensureWakeNotificationChannel();
            NotificationCompat.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    : new NotificationCompat.Builder(this);

            Set<String> reg = RearScreenWakeManager.getInstance().getRegisteredActivities();
            boolean hasLyrics = reg.contains(RearScreenLyricsActivity.class.getName());
            boolean hasCar = reg.contains(RearScreenCarControlActivity.class.getName());

            String title;
            String summary = getString(R.string.projection_notify_stop_summary);
            String bigText;
            if (hasLyrics && !hasCar) {
                title = getString(R.string.projection_notify_music_title);
                bigText = getString(R.string.projection_notify_music_big);
            } else if (hasCar && !hasLyrics) {
                title = getString(R.string.projection_notify_car_title);
                bigText = getString(R.string.projection_notify_car_big);
            } else {
                title = getString(R.string.projection_notify_multi_title);
                bigText = getString(R.string.projection_notify_multi_big);
            }

            PendingIntent stopPi = buildStopProjectionContentIntent();

            Notification notification = builder
                    .setContentTitle(title)
                    .setContentText(summary)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                    .setSmallIcon(R.drawable.ic_stat_notify_record)
                    .setContentIntent(stopPi)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setAutoCancel(false)
                    .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(MiRootNotificationIds.APP_PROJECTION_NOTIFICATION_ID);
            }

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            LogHelper.d(TAG, "SVC: foreground notification updated");
        } catch (Exception e) {
            LogHelper.e(TAG, "SVC: updateForegroundNotification failed", e);
        }
    }
    
    /**
     * ??????????
     */
    /** 与功能页 [RearAssistPrefs] 一致（并与 Flutter 迁移同步）。 */
    private boolean isKeepScreenOnEnabled() {
        return RearAssistPrefs.INSTANCE.isKeepScreenOnEnabled(this);
    }
    
    private void startWakeupLoop() {
        if (isRunning) {
            LogHelper.d(TAG, "LOOP: already running");
            return;
        }

        // ??????????
        boolean keepScreenOnEnabled = isKeepScreenOnEnabled();
        if (!keepScreenOnEnabled) {
            LogHelper.d(TAG, "LOOP: keep_screen_on_disabled, not start");
            return;
        }
        
        isRunning = true;
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                // ??????????
                boolean keepScreenOnEnabled = isKeepScreenOnEnabled();
                if (!keepScreenOnEnabled) {
                    LogHelper.d(TAG, "LOOP: keep_screen_on_disabled -> stop");
                    stopWakeupLoop();
                    return;
                }
                
                // ??wakeup?????
                try {
                    if (taskService != null) {
                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    } else {
                        LogHelper.w(TAG, "WAKE: TaskService null, rebind");
                        bindTaskService();
                    }
                } catch (Throwable t) {
                    LogHelper.w(TAG, "WAKE: shell command failed", t);
                    // ????????????
                    bindTaskService();
                }
                
                if (wakeupHandler != null && isRunning) {
                    wakeupHandler.postDelayed(this, getAdaptiveWakeIntervalMs());
                }
            }
        };
        
        // ?????????
        wakeupHandler.post(wakeupRunnable);
        sWakeupLoopActive = true;
        LogHelper.d(TAG, "LOOP: started (intervalMs=" + getAdaptiveWakeIntervalMs() + ")");
    }
    
    /**
     * ??????
     */
    private void stopWakeupLoop() {
        isRunning = false;
        sWakeupLoopActive = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
        if (wakeupHandler != null && rebindRunnable != null) {
            wakeupHandler.removeCallbacks(rebindRunnable);
        }
        LogHelper.d(TAG, "LOOP: stopped");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogHelper.d(TAG, "onStartCommand (flags=" + flags + ", startId=" + startId + ")");
        
        // ???????????????Intent Action?
        if (intent != null && ACTION_SET_KEEP_SCREEN_ON_ENABLED.equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            LogHelper.d(TAG, "CFG: keep_screen_on=" + enabled);
            
            if (enabled) {
                // ???????????????TaskService???????
                if (!isRunning && taskService != null) {
                    startWakeupLoop();
                    LogHelper.d(TAG, "CFG: loop start requested");
                } else if (!isRunning && taskService == null) {
                    LogHelper.d(TAG, "CFG: TaskService not ready, wait bind");
                }
            } else {
                // ???????????
                if (isRunning) {
                    stopWakeupLoop();
                    LogHelper.d(TAG, "CFG: loop stop requested");
                }
            }
            
            updateForegroundNotification();
            return START_STICKY;
        }
        
        // 无注册（未投屏或未调用 startWakeService）则停止，与「始终常亮」仅由开关/服务启停不同
        if (!hasProjectionRegistration()) {
            LogHelper.d(TAG, "SVC: no projection registration -> stopSelf");
            stopWakeupLoop();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 背屏桌面打开第三方应用后由 Keeper 单独负责迁屏/常亮；避免与 Wake 双前台通知并存
        Set<String> regForKeeper = RearScreenWakeManager.getInstance().getRegisteredActivities();
        boolean wakeHasLyrics = regForKeeper.contains(RearScreenLyricsActivity.class.getName());
        boolean wakeHasCar = regForKeeper.contains(RearScreenCarControlActivity.class.getName());
        if (RearSwitchKeeperService.isRunning() && !wakeHasLyrics && !wakeHasCar) {
            LogHelper.d(TAG, "SVC: keeper active without music/car wake -> stopSelf");
            stopWakeupLoop();
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // ????????????????????????????Activity????
        // ????????????????????Activity????????????
        // ?Activity???????????
        // ???????????????????
        boolean keepScreenOnEnabled = isKeepScreenOnEnabled();
        if (keepScreenOnEnabled) {
            // ???????????????TaskService???????
            if (!isRunning && taskService != null) {
                startWakeupLoop();
                LogHelper.d(TAG, "? ????????????????");
            }
        } else {
            // ???????????
            if (isRunning) {
                stopWakeupLoop();
                LogHelper.d(TAG, "?? ????????????????");
            }
        }
        
        updateForegroundNotification();
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        LogHelper.d(TAG, "SVC: onDestroy");
        
        // ??????
        stopWakeupLoop();

        // 结束投屏 / stopService 时必须移除此前台通知，否则通知栏仍残留「投屏常亮」
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "SVC: stopForeground failed", t);
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "SVC: cancel notification failed", t);
        }
        
        // ???????????
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
                LogHelper.d(TAG, "EVT: SCREEN_OFF receiver unregistered");
            } catch (Exception e) {
                LogHelper.w(TAG, "EVT: SCREEN_OFF receiver unregister failed", e);
            }
        }
        
        // 解绑 TaskService（RootTaskService）
        try {
            if (taskServiceBound) {
                unbindService(taskServiceConnection);
                taskServiceBound = false;
                LogHelper.d(TAG, "BIND: unbound TaskService");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "BIND: unbind failed: " + e.getMessage());
        }
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        // ???????
        return null;
    }
}
