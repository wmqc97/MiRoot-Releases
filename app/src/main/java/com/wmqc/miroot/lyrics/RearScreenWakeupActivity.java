
package com.wmqc.miroot.lyrics;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.ComponentActivity;

import com.wmqc.miroot.RearDisplayInputHelper;

/**
 * 专门用于点亮背屏的透明 Activity。
 * V2.1: 支持动态旋转控制
 */
public class RearScreenWakeupActivity extends ComponentActivity {
    private static final String TAG = "RearScreenWakeup";

    // 静态变量存储背屏旋转方向
    private static int sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    
    /**
     * V2.1: 设置背屏旋转方向（从外部调用）
     * @param rotation 旋转方向 (0=0°, 1=90°, 2=180°, 3=270°)
     */
    public static void setRearDisplayRotation(int rotation) {
        // 将rotation值转换为ActivityInfo常量
        switch (rotation) {
            case 0:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case 1:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case 2:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                break;
            case 3:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                break;
            default:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 应用旋转设置
        if (sRearDisplayRotation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            setRequestedOrientation(sRearDisplayRotation);
        }
        
        // 获取当前display
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        // 如果在主屏，什么都不做
        if (displayId == 0) {
            return;
        }
        
        // --- 以下代码只在背屏 (displayId == 1) 执行 ---
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);

        // 设置全屏显示
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        getWindow().setAttributes(params);

        // 仅隐藏状态栏，保留导航/手势区域（与车控/充电动画背屏策略一致）
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.navigationBars());
                controller.hide(android.view.WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
            }
        } else {
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
            decorView.setSystemUiVisibility(uiOptions);
        }
        
        // 关键：在背屏时点亮屏幕并保持常亮
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // 适配新API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        // 延迟关闭（给予足够时间点亮屏幕）
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            finish();
        }, 1000); // 1秒后关闭
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 再次确保点亮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
    }
    
    @Override
    public void finish() {
        super.finish();
        // 禁用转场动画
        overridePendingTransition(0, 0);
    }

}

