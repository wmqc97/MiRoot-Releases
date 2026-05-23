package com.wmqc.miroot.lyrics;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
/**
 * 使用 su Root 权限运行的本地 Service 包装器。
 * 内部持有原有的 ITaskService 实现（TaskService），对外通过 AIDL 暴露。
 */
public class RootTaskService extends Service {

    // 统一使用带前缀的日志 TAG，便于在 logcat 中过滤
    private static final String TAG = "RootTaskService";

    // 直接复用现有的 TaskService（ITaskService.Stub 实现）
    private final ITaskService.Stub binder = new TaskService();

    @Override
    public void onCreate() {
        super.onCreate();
        LogHelper.d(TAG, "✅ RootTaskService created");
    }

    @Override
    public IBinder onBind(Intent intent) {
        LogHelper.d(TAG, "✅ RootTaskService onBind");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        LogHelper.d(TAG, "✅ RootTaskService onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        LogHelper.d(TAG, "✅ RootTaskService destroyed");
        super.onDestroy();
    }
}

