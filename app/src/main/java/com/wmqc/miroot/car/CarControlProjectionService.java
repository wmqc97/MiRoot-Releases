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
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.RearScreenWakeManager;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import com.wmqc.miroot.lyrics.RootTaskServiceConnector;
import android.os.PowerManager;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

/**
 * 车控投屏服务
 * 处理外部广播调用，打开背屏显示（车控投屏）
 */
public class CarControlProjectionService extends IntentService {
    private static final String TAG = "CarControlProjectionService";

    private ITaskService taskService;
    private PowerManager.WakeLock wakeLock;

    public CarControlProjectionService() {
        super("CarControlProjectionService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogHelper.d(TAG, "🔵 CarControlProjectionService onCreate");
        RootTaskServiceConnector.prewarm(this);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // 立即获取WakeLock，确保锁屏时也能执行（在方法开始时获取，保持30秒）
        acquireWakeLock(30000);
        try {
            LogHelper.d(TAG, "🔵 CarControlProjectionService onHandleIntent 开始");
            if (intent == null) {
                LogHelper.w(TAG, "⚠ intent 为 null");
                return;
            }

            String action = intent.getAction();
            LogHelper.d(TAG, "📋 action: " + action);

            if (CarControlIntents.isCarControlProjectionServiceAction(action)) {
                LogHelper.d(TAG, "🚗 处理车控投屏请求（含 MiRoot / 停止专用 action）");

                if (!CarControlDeviceGate.isAllowed(this)) {
                    LogHelper.w(TAG, "设备未授权，忽略车控投屏请求");
                    return;
                }

                if (!ensureTaskServiceConnected()) {
                    LogHelper.e(TAG, "❌ TaskService未连接");
                    showToast("服务未就绪，请检查 Root 权限");
                    LogHelper.d(TAG, "🔵 CarControlProjectionService onHandleIntent 结束（TaskService未连接）");
                    return;
                }

                final String actionParam;
                if (CarControlIntents.isCarProjectionStopAction(action)) {
                    actionParam = CarControlIntents.VALUE_CAR_PROJECTION_OP_STOP;
                } else {
                    String extra = intent.getStringExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP);
                    actionParam = (extra != null && !extra.isEmpty())
                            ? extra
                            : CarControlIntents.VALUE_CAR_PROJECTION_OP_START;
                }

                if (CarControlIntents.VALUE_CAR_PROJECTION_OP_START.equalsIgnoreCase(actionParam)) {
                    startCarControlProjection();
                } else if (CarControlIntents.VALUE_CAR_PROJECTION_OP_STOP.equalsIgnoreCase(actionParam)) {
                    stopCarControlProjection();
                } else {
                    LogHelper.w(TAG, "⚠ 未知的action参数: " + actionParam);
                    showToast("未知的操作");
                }
            } else {
                LogHelper.w(TAG, "⚠ 未知的 action: " + action);
            }
            
            LogHelper.d(TAG, "🔵 CarControlProjectionService onHandleIntent 结束（将自动停止）");
        } finally {
            // 在方法结束时释放WakeLock
            releaseWakeLock();
        }
    }

    private boolean ensureTaskServiceConnected() {
        taskService = RootTaskServiceConnector.ensureConnected(this, 2500L);
        if (taskService != null) {
            LogHelper.d(TAG, "✅ TaskService已连接");
            return true;
        }
        LogHelper.e(TAG, "❌ TaskService连接超时");
        return false;
    }

    /**
     * 启动车控投屏（直接在背屏启动车控Activity）
     * 使用ProjectionHelper统一流程，确保广播启动和按钮启动行为一致
     * 如果车控投屏已在运行，则获取stackId并使用am display move-stack移动到背屏
     */
    private void startCarControlProjection() {
        // 注意：WakeLock已在onHandleIntent开始时获取，这里不需要再次获取
        try {
            // 如果车控投屏未运行，或移动失败，使用统一流程启动
            boolean success = ProjectionHelper.startCarControlProjection(taskService, this);
            if (success) {
                // 保持静默，避免多余提示
            } else {
                // 启动失败时仅记录日志，不弹出“已在运行”类提示
                LogHelper.w(TAG, "⚠ 车控投屏启动失败（可能已在运行）");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 启动车控投屏失败", e);
            showToast("启动车控投屏失败");
        }
    }

    /**
     * 停止车控投屏
     * 与按钮启动的停止流程一致：注销窗口、恢复官方服务、关闭背屏常亮
     */
    private void stopCarControlProjection() {
        // 注意：WakeLock已在onHandleIntent开始时获取，这里不需要再次获取
        try {
            LogHelper.d(TAG, "🛑 开始停止车控投屏（广播启动）");

            // 获取当前运行的车控投屏Activity
            RearScreenCarControlActivity existingActivity = RearScreenCarControlActivity.getCurrentInstance();
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
                        LogHelper.d(TAG, "→ 找到正在运行的车控投屏Activity，直接调用finish()避免动画");

                        // 直接在主线程调用Activity的finish()方法，避免通过Intent导致的切换动画
                        // 注意：清理操作会在onDestroy()中的performCleanupAndExit()中执行，避免重复执行
                        final RearScreenCarControlActivity finalActivity = existingActivity;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                // 再次检查Activity是否存在且未finishing
                                RearScreenCarControlActivity currentActivity = RearScreenCarControlActivity.getCurrentInstance();
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
                LogHelper.d(TAG, "⚠ 没有正在运行的车控投屏（静态实例为null）");
            }

            // 注意：清理操作（停止服务、恢复Launcher）已在Activity的onDestroy()中的performCleanupAndExit()中执行
            // 这里不再重复执行，避免触发两次动画（finish()的动画 + 恢复Launcher的动画）
            // 只在Activity不存在时才执行清理（兜底逻辑）
            if (existingActivity == null) {
                LogHelper.d(TAG, "⚠️ Activity不存在，执行兜底清理");
                // 步骤1: 停止背屏常亮服务（使用统一的RearScreenWakeManager）
                try {
                    LogHelper.d(TAG, "步骤1: 停止背屏常亮服务（车控投屏）");
                    RearScreenWakeManager.getInstance().stopWakeService(this, RearScreenCarControlActivity.class);
                    LogHelper.d(TAG, "✅ 背屏常亮服务已停止（车控投屏）");
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 停止背屏常亮服务失败", e);
                }

                // 步骤2: 恢复官方手势服务和Launcher（通过enableSubScreenLauncher）
                try {
                    if (taskService != null) {
                        LogHelper.d(TAG, "步骤2: 恢复官方手势服务和Launcher");
                        boolean success = taskService.enableSubScreenLauncher();
                        if (success) {
                            LogHelper.d(TAG, "✅ 已恢复官方手势服务和Launcher");
                        } else {
                            LogHelper.w(TAG, "⚠️ 恢复官方手势服务和Launcher失败（返回false）");
                        }
                    } else {
                        LogHelper.w(TAG, "⚠️ TaskService为null，无法恢复官方手势服务和Launcher");
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 恢复官方手势服务和Launcher失败", e);
                }
            } else {
                LogHelper.d(TAG, "✅ Activity存在，清理操作已在onDestroy()中执行，跳过重复清理");
            }

            showToast("车控投屏已关闭");

        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 停止车控投屏失败", e);
            showToast("停止车控投屏失败");
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiRoot:CarControlProjectionWake");
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
            MainDisplayUi.showToast(getApplicationContext(), message, Toast.LENGTH_SHORT);
        });
    }

    @Override
    public void onDestroy() {
        LogHelper.d(TAG, "🔵 CarControlProjectionService onDestroy");
        
        // 确保释放WakeLock
        releaseWakeLock();
        
        super.onDestroy();

        taskService = null;
        LogHelper.d(TAG, "🔵 CarControlProjectionService 已销毁");
    }
}
