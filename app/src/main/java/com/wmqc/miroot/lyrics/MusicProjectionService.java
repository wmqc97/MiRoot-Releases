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

package com.wmqc.miroot.lyrics;

import android.app.IntentService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import com.wmqc.miroot.MainActivity;

/**
 * 音乐投屏服务
 * 处理外部广播调用，打开背屏显示（音乐歌词投屏）
 */
public class MusicProjectionService extends IntentService {
    private static final String TAG = "MusicProjectionService";

    private ITaskService taskService;
    private PowerManager.WakeLock wakeLock;

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            LogHelper.d(TAG, "✓ TaskService已连接");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };

    public MusicProjectionService() {
        super("MusicProjectionService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogHelper.d(TAG, "🔵 MusicProjectionService onCreate");
        // 立即绑定TaskService，给onHandleIntent更多时间等待连接
        // 注意：IntentService是单线程的，不能在这里sleep，否则会阻塞后续操作
        bindTaskService();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // 立即获取WakeLock，确保锁屏时也能执行（在方法开始时获取，保持30秒）
        acquireWakeLock(30000);
        try {
            LogHelper.d(TAG, "🔵 MusicProjectionService onHandleIntent 开始");
            if (intent == null) {
                LogHelper.w(TAG, "⚠ intent 为 null");
                return;
            }

            String action = intent.getAction();
            LogHelper.d(TAG, "📋 action: " + action);
            
            if (LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION.equals(action)) {
                LogHelper.d(TAG, "🎵 处理打开背屏显示请求");
                
                // 确保TaskService已连接
                if (!ensureTaskServiceConnected()) {
                    LogHelper.e(TAG, "❌ TaskService未连接");
                    showToast("服务未就绪，请检查 Root 权限");
                    LogHelper.d(TAG, "🔵 MusicProjectionService onHandleIntent 结束（TaskService未连接）");
                    return;
                }
                
                String actionParam = intent.getStringExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP);
                if (actionParam == null) {
                    actionParam = LyricsIntents.VALUE_MUSIC_PROJECTION_OP_START;
                }

                if (LyricsIntents.VALUE_MUSIC_PROJECTION_OP_START.equalsIgnoreCase(actionParam)) {
                    startMusicProjection();
                } else if (LyricsIntents.VALUE_MUSIC_PROJECTION_OP_STOP.equalsIgnoreCase(actionParam)) {
                    stopMusicProjection();
                } else {
                    LogHelper.w(TAG, "⚠ 未知的投屏操作参数: " + actionParam);
                    showToast("未知的操作");
                }
            } else {
                LogHelper.w(TAG, "⚠ 未知的 action: " + action);
            }
            
            LogHelper.d(TAG, "🔵 MusicProjectionService onHandleIntent 结束（将自动停止）");
        } finally {
            // 在方法结束时释放WakeLock
            releaseWakeLock();
        }
    }

    private boolean ensureTaskServiceConnected() {
        if (taskService != null) {
            LogHelper.d(TAG, "✅ TaskService已连接");
            return true;
        }

        try {
            LogHelper.d(TAG, "🔄 TaskService未连接，开始绑定 RootTaskService...");
            // onCreate中已经绑定了，如果还没连接，再绑定一次（可能onCreate中的绑定还没完成）
            bindTaskService();

            // 等待连接（广播时应用可能在后台，Root 提权可能稍慢，延长等待）
            int attempts = 0;
            int maxAttempts = 20; // 20 x 250ms = 5s，兼容锁屏/后台广播启动
            while (taskService == null && attempts < maxAttempts) {
                Thread.sleep(250);
                attempts++;
                if (taskService == null && attempts % 4 == 0) {
                    LogHelper.d(TAG, "⏳ 等待TaskService连接中... (尝试 " + attempts + "/" + maxAttempts + ")");
                }
            }

            if (taskService != null) {
                LogHelper.d(TAG, "✅ TaskService连接成功 (尝试 " + attempts + "/" + maxAttempts + ")");
                return true;
            } else {
                LogHelper.e(TAG, "❌ TaskService连接超时 (尝试 " + attempts + "/" + maxAttempts + ")");
                return false;
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ TaskService重连失败", e);
            return false;
        }
    }

    private void bindTaskService() {
        if (taskService != null) {
            LogHelper.d(TAG, "ℹ️ TaskService已绑定，跳过");
            return;
        }

        try {
            LogHelper.d(TAG, "🔄 开始绑定 RootTaskService...");
            Intent intent = new Intent(this, RootTaskService.class);
            bindService(intent, taskServiceConnection, Context.BIND_AUTO_CREATE);
            LogHelper.d(TAG, "✅ 已调用 bindService 绑定 RootTaskService，等待连接回调...");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 绑定 RootTaskService 失败", e);
        }
    }

    /**
     * 启动音乐投屏：
     * {@link ProjectionHelper#startMusicProjection} 使用 {@code am start --display 1}；
     * 若已有实例卡在主屏，则 {@code am display move-stack} 迁到背屏。
     */
    private void startMusicProjection() {
        try {
            RearScreenLyricsActivity existingActivity = RearScreenLyricsActivity.getCurrentInstance();
            int displayId = 0;
            if (existingActivity != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        displayId = existingActivity.getDisplay().getDisplayId();
                    } catch (Exception e) {
                        LogHelper.w(TAG, "获取displayId失败", e);
                    }
                }
                if (displayId == 1) {
                    LogHelper.d(TAG, "⚠ 音乐投屏已在背屏运行");
                    return;
                }
            }

            if (existingActivity != null && displayId == 0) {
                try {
                    // 音乐投屏：已注释 disableSubScreenLauncher；仅保留背屏唤醒（与 ProjectionHelper 步骤 2 一致）
                    LogHelper.d(TAG, "move-stack 路径: 已跳过屏蔽官方手势，仅唤醒背屏");
                    // try {
                    //     taskService.disableSubScreenLauncher();
                    // } catch (Exception e) {
                    //     LogHelper.w(TAG, "disableSubScreenLauncher 异常（继续 move-stack）: " + e.getMessage());
                    // }
                    // try {
                    //     Thread.sleep(100);
                    // } catch (InterruptedException e) {
                    //     Thread.currentThread().interrupt();
                    // }
                    try {
                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    } catch (Exception e) {
                        LogHelper.w(TAG, "背屏唤醒命令失败（可忽略）: " + e.getMessage());
                    }

                    LogHelper.d(TAG, "🔍 音乐投屏在主屏运行，尝试获取 stackId 并用 am display move-stack 移到背屏");
                    int stackId = ProjectionHelper.getMusicProjectionStackId(taskService);
                    if (stackId != -1) {
                        String moveCmd = "am display move-stack " + stackId + " 1";
                        boolean moveSuccess = taskService.executeShellCommand(moveCmd);
                        if (moveSuccess) {
                            LogHelper.d(TAG, "✅ 音乐投屏已移动到背屏（am display move-stack）");

                            try {
                                Thread.sleep(800);

                                RearScreenLyricsActivity activity = RearScreenLyricsActivity.getCurrentInstance();
                                if (activity != null) {
                                    try {
                                        java.lang.reflect.Field lyricsViewField = RearScreenLyricsActivity.class.getDeclaredField("lyricsView");
                                        lyricsViewField.setAccessible(true);
                                        Object lyricsView = lyricsViewField.get(activity);
                                        if (lyricsView == null) {
                                            LogHelper.w(TAG, "⚠️ UI仍未初始化，可能需要更长时间");
                                        } else {
                                            LogHelper.d(TAG, "✅ UI已初始化");
                                        }
                                    } catch (Exception e) {
                                        LogHelper.w(TAG, "⚠️ 检查UI状态失败: " + e.getMessage());
                                    }
                                }
                            } catch (InterruptedException e) {
                                LogHelper.w(TAG, "等待被中断", e);
                            }

                            // 音乐投屏：已注释 — 原步骤 5 triggerLyricsGestureDisableAfterMove
                            // ProjectionHelper.triggerLyricsGestureDisableAfterMove(3000);
                            return;
                        } else {
                            LogHelper.w(TAG, "⚠️ am display move-stack 执行失败，尝试完整启动流程");
                        }
                    } else {
                        LogHelper.w(TAG, "⚠️ 未能解析 stackId，尝试完整启动流程");
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 检查或移动音乐投屏时发生异常", e);
                }
            }

            boolean success = ProjectionHelper.startMusicProjection(taskService, this);
            if (success) {
                // 已移除弹窗提示
            } else {
                // 已移除弹窗提示
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 启动音乐投屏失败", e);
            // 已移除弹窗提示
        }
    }

    /**
     * 停止音乐投屏
     * 与按钮启动的停止流程一致：注销窗口、恢复官方服务、关闭背屏常亮
     */
    private void stopMusicProjection() {
        try {
            LogHelper.d(TAG, "🛑 开始停止音乐投屏（广播启动）");

            // 获取当前运行的音乐投屏Activity
            RearScreenLyricsActivity existingActivity = RearScreenLyricsActivity.getCurrentInstance();
            if (existingActivity != null) {
                try {
                    // 检查Activity是否正在finishing或者已经destroyed
                    boolean isFinishing = existingActivity.isFinishing();
                    boolean isDestroyed = false;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        isDestroyed = existingActivity.isDestroyed();
                    }
                    
                    if (isFinishing || isDestroyed) {
                        LogHelper.d(TAG, "⚠️ Activity正在finishing或已destroyed，跳过停止操作，直接执行清理");
                    } else {
                        LogHelper.d(TAG, "→ 找到正在运行的音乐投屏Activity，直接调用finish()避免动画");

                        // 直接在主线程调用Activity的finish()方法，避免通过Intent导致的切换动画
                        // 注意：清理操作会在onDestroy()中的performCleanupAndExit()中执行，避免重复执行
                        final RearScreenLyricsActivity finalActivity = existingActivity;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                // 再次检查Activity是否存在且未finishing
                                RearScreenLyricsActivity currentActivity = RearScreenLyricsActivity.getCurrentInstance();
                                if (currentActivity != null && currentActivity == finalActivity && !currentActivity.isFinishing()) {
                                    // 直接调用finish()，Activity会在onDestroy()中调用performCleanupAndExit()进行清理
                                    currentActivity.finish();
                                    LogHelper.d(TAG, "✅ 已直接调用Activity.finish()（主线程，无动画）");
                                } else {
                                    LogHelper.w(TAG, "⚠️ Activity已不存在或正在finishing，无法调用finish()");
                                }
                            } catch (Exception e) {
                                LogHelper.e(TAG, "❌ 在主线程调用finish()失败", e);
                            }
                        });
                        
                        // 等待一小段时间，让Activity处理finish()和onDestroy()
                        Thread.sleep(300);
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 检查或停止Activity时发生异常", e);
                }
            } else {
                LogHelper.d(TAG, "⚠ 没有正在运行的音乐投屏（静态实例为null）");
            }

            // 注意：清理操作（停止服务等）已在 Activity 的 onDestroy() 中 performCleanupAndExit() 执行；不再恢复官方 Launcher
            // 这里不再重复执行，避免与 finish() 叠加
            // 只在Activity不存在时才执行清理（兜底逻辑）
            if (existingActivity == null) {
                LogHelper.d(TAG, "⚠️ Activity不存在，执行兜底清理");
                // 步骤1: 停止背屏常亮服务（使用统一的RearScreenWakeManager）
                try {
                    LogHelper.d(TAG, "步骤1: 停止背屏常亮服务（音乐投屏）");
                    RearScreenWakeManager.getInstance().stopWakeService(this, RearScreenLyricsActivity.class);
                    LogHelper.d(TAG, "✅ 背屏常亮服务已停止（音乐投屏）");
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 停止背屏常亮服务失败", e);
                }

                // 音乐投屏不再调用 enableSubScreenLauncher（不主动恢复官方副屏 Launcher）
            } else {
                LogHelper.d(TAG, "✅ Activity存在，清理操作已在onDestroy()中执行，跳过重复清理");
            }

            // Activity 为 null 时 onDestroy 不会跑，须显式同步「未投屏」；Activity 存在时多一次 false 无害。
            try {
                MainActivity.sendMusicProjectionStateBroadcast(this, false);
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 广播投屏已停止失败", e);
            }

            // 已移除弹窗提示

        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 停止音乐投屏失败", e);
            // 已移除弹窗提示
        }
    }

    /**
     * 获取WakeLock（保持CPU唤醒，确保锁屏时也能执行）
     * @param timeoutMs 超时时间（毫秒）
     */
    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:MusicProjectionWake");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(timeoutMs);
                    LogHelper.d(TAG, "🔒 PARTIAL_WAKE_LOCK acquired for " + timeoutMs + "ms");
                }
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "Failed to acquire wakelock: " + t.getMessage());
        }
    }

    /**
     * 释放WakeLock
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                LogHelper.d(TAG, "🔓 PARTIAL_WAKE_LOCK released");
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "Failed to release wakelock: " + t.getMessage());
        }
    }

    /**
     * 显示Toast提示（主线程）
     */
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroy() {
        LogHelper.d(TAG, "🔵 MusicProjectionService onDestroy");
        // 确保释放WakeLock
        releaseWakeLock();
        super.onDestroy();

        if (taskService != null) {
            try {
                unbindService(taskServiceConnection);
                LogHelper.d(TAG, "✓ TaskService已解绑（RootTaskService）");
            } catch (Exception e) {
                LogHelper.e(TAG, "解绑TaskService失败", e);
            }
            taskService = null;
        }
        LogHelper.d(TAG, "🔵 MusicProjectionService 已销毁");
    }
}

