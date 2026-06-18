
package com.wmqc.miroot.lyrics;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.wmqc.miroot.rear.RearAssistService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投屏常亮服务管理类（单例模式）
 * 统一管理所有投屏的投屏常亮服务，确保只有一个服务实例运行。
 * 通过 {@link Application.ActivityLifecycleCallbacks} 自动清理已销毁 Activity 的残留注册。
 */
public class RearScreenWakeManager {
    private static final String TAG = "RearScreenWakeManager";
    private static volatile RearScreenWakeManager instance;
    
    // 注册的Activity类名集合（线程安全）
    // 注意：ConcurrentHashMap 不允许 null key/value，之前用 put(name, null) 会直接抛 NPE
    private final Set<String> registeredActivities = ConcurrentHashMap.newKeySet();

    /** 已注册到 Application 的 callbacks 实例，避免重复注册 */
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private boolean lifecycleCallbacksRegistered = false;
    
    private RearScreenWakeManager() {
    }
    
    /**
     * 获取单例实例
     */
    public static RearScreenWakeManager getInstance() {
        if (instance == null) {
            synchronized (RearScreenWakeManager.class) {
                if (instance == null) {
                    instance = new RearScreenWakeManager();
                }
            }
        }
        return instance;
    }

    /**
     * 注册 Activity 生命周期回调：Activity.onDestroy 时自动清除残留注册，
     * 防止进程存活但 Activity 被系统回收后服务泄漏。
     */
    private void ensureLifecycleCallbacksRegistered(Context app) {
        if (lifecycleCallbacksRegistered) return;
        synchronized (this) {
            if (lifecycleCallbacksRegistered) return;
            lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle s) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle o) {}

                @Override
                public void onActivityDestroyed(Activity a) {
                    String name = a.getClass().getName();
                    if (registeredActivities.remove(name)) {
                        LogHelper.d(TAG, "REG: auto-removed destroyed activity " + name
                                + " (count=" + registeredActivities.size() + ")");
                        if (registeredActivities.isEmpty()) {
                            stopWakeServiceInternal(a.getApplicationContext());
                        }
                    }
                }
            };
            try {
                Application application = (Application) app.getApplicationContext();
                application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
                lifecycleCallbacksRegistered = true;
                LogHelper.d(TAG, "LCB: lifecycle callbacks registered");
            } catch (Exception e) {
                LogHelper.w(TAG, "LCB: register failed: " + e.getMessage());
            }
        }
    }

    /**
     * 停止 Wake 服务并触发 RearAssistService.sync。
     */
    private void stopWakeServiceInternal(Context app) {
        try {
            app.stopService(new Intent(app, RearScreenWakeService.class));
            LogHelper.d(TAG, "SVC: stopped RearScreenWakeService (count=0)");
        } catch (Exception e) {
            LogHelper.w(TAG, "stop RearScreenWakeService failed: " + e.getMessage());
        }
        try {
            RearAssistService.sync(app);
            LogHelper.d(TAG, "SVC: RearAssistService.sync after projection wake ended");
        } catch (Exception e) {
            LogHelper.w(TAG, "RearAssistService.sync failed: " + e.getMessage());
        }
    }
    
    /**
     * 启动投屏常亮服务：注册一个投屏 Activity 并拉起 {@link RearScreenWakeService}；结束投屏须 {@link #stopWakeService}。
     *
     * @param context       上下文
     * @param activityClass 投屏 Activity 类
     */
    public void startWakeService(Context context, Class<?> activityClass) {
        if (context == null || activityClass == null) {
            LogHelper.e(TAG, "REG: invalid args (null)");
            return;
        }
        Context app = context.getApplicationContext();
        ensureLifecycleCallbacksRegistered(app);

        String className = activityClass.getName();
        synchronized (registeredActivities) {
            if (registeredActivities.contains(className)) {
                LogHelper.d(TAG, "REG: already added " + className);
                return;
            }
            
            registeredActivities.add(className);
            int size = registeredActivities.size();
            LogHelper.d(TAG, "REG: added " + className + " (count=" + size + ")");

            // 只在"第一个注册"时启动服务，避免频繁 startService
            if (size == 1) {
                try {
                    app.stopService(new Intent(app, RearAssistService.class));
                    LogHelper.d(TAG, "SVC: stopped RearAssistService (projection wake owns foreground)");
                } catch (Exception e) {
                    LogHelper.w(TAG, "stop RearAssistService failed: " + e.getMessage());
                }
                Intent serviceIntent = new Intent(app, RearScreenWakeService.class);
                app.startService(serviceIntent);
                LogHelper.d(TAG, "SVC: started RearScreenWakeService (count=1)");
            } else {
                LogHelper.d(TAG, "SVC: already running, skip startService (count=" + size + ")");
            }
        }
    }
    
    /**
     * 停止投屏常亮服务（注销一个Activity）
     * @param context Context
     * @param activityClass Activity类
     */
    public void stopWakeService(Context context, Class<?> activityClass) {
        if (context == null || activityClass == null) {
            LogHelper.e(TAG, "REG: invalid args (null)");
            return;
        }
        Context app = context.getApplicationContext();

        String className = activityClass.getName();
        synchronized (registeredActivities) {
            if (!registeredActivities.contains(className)) {
                LogHelper.d(TAG, "REG: not found " + className);
                return;
            }
            
            registeredActivities.remove(className);
            int size = registeredActivities.size();
            LogHelper.d(TAG, "REG: removed " + className + " (count=" + size + ")");
            
            // 如果没有注册的Activity了，停止服务
            if (registeredActivities.isEmpty()) {
                stopWakeServiceInternal(app);
            }
        }
    }

    /**
     * 注册表残留清理（例如异常路径）；正常结束投屏应走 {@link #stopWakeService}。
     */
    public void clearStaleRegistrationsWhenNoProjection(Context context) {
        boolean hadAny = false;
        synchronized (registeredActivities) {
            if (registeredActivities.isEmpty()) {
                return;
            }
            LogHelper.w(TAG, "REG: clear stale (" + registeredActivities.size() + "): " + registeredActivities);
            registeredActivities.clear();
            hadAny = true;
        }
        if (hadAny && context != null) {
            try {
                RearAssistService.sync(context.getApplicationContext());
                LogHelper.d(TAG, "SVC: RearAssistService.sync after stale registration clear");
            } catch (Exception e) {
                LogHelper.w(TAG, "RearAssistService.sync failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * 检查是否有注册的Activity
     */
    public boolean hasRegisteredActivities() {
        return !registeredActivities.isEmpty();
    }
    
    /**
     * 获取注册的Activity类名集合（用于服务检查）
     */
    public Set<String> getRegisteredActivities() {
        return registeredActivities;
    }
}
