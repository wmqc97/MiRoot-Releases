/*
 * Co-developed with AI assistants (Cursor).
 */
package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 统一车控/广播场景的震动：主线程执行 + Android 12+ {@link VibratorManager} +
 * Android 13+ {@link VibrationAttributes}，减少 OEM 上后台/短震被吞的情况。
 */
public final class VibrationHelper {
    private static final String TAG = "VibrationHelper";

    private VibrationHelper() {
    }

    public static void vibrateOneShot(Context context, long durationMs, String failLogPrefix) {
        if (context == null || durationMs <= 0) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String prefix = failLogPrefix != null ? failLogPrefix : "震动失败";
        Runnable run = () -> doVibrate(app, durationMs, prefix);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run();
        } else {
            new Handler(Looper.getMainLooper()).post(run);
        }
    }

    private static void doVibrate(Context app, long durationMs, String failLogPrefix) {
        try {
            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vibrator = vm.getDefaultVibrator();
                }
            }
            if (vibrator == null) {
                vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                LogHelper.w(TAG, failLogPrefix + ": 设备无振动器或不可用");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 固定强度：部分机型上 DEFAULT_AMPLITUDE 体感极弱
                VibrationEffect effect = VibrationEffect.createOneShot(durationMs, 200);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    VibrationAttributes attrs = VibrationAttributes.createForUsage(
                            VibrationAttributes.USAGE_HARDWARE_FEEDBACK);
                    vibrator.vibrate(effect, attrs);
                } else {
                    vibrator.vibrate(effect);
                }
                LogHelper.d(TAG, "📳 vibrate " + durationMs + "ms (API26+)");
            } else {
                vibrator.vibrate(durationMs);
                LogHelper.d(TAG, "📳 vibrate " + durationMs + "ms (legacy)");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, failLogPrefix + ": " + e.getMessage());
        }
    }
}
