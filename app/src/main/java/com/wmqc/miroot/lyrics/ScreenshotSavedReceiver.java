package com.wmqc.miroot.lyrics;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.wmqc.miroot.R;

/**
 * 背屏截图保存后的用户反馈：TaskService 通过显式 {@link Intent} 调用，不依赖 MainActivity 是否已注册动态广播。
 */
public class ScreenshotSavedReceiver extends BroadcastReceiver {

    static final String EXTRA_FILEPATH = "filepath";
    private static final String CHANNEL_ID = "screenshot_notification_channel";
    private static final int NOTIFICATION_ID = 10004;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String filepath = intent.getStringExtra(EXTRA_FILEPATH);
        if (filepath == null || filepath.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        boolean canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (canPostNotification) {
            showNotification(app, filepath);
        } else {
            showToastOnMainDisplay(app, app.getString(R.string.toast_screenshot_saved));
        }
    }

    private static void showNotification(Context context, String filepath) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "截图通知",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("截图保存通知");
                channel.enableLights(true);
                channel.enableVibration(false);
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }

            Intent openIntent = new Intent(Intent.ACTION_VIEW);
            openIntent.setDataAndType(Uri.parse("file://" + filepath), "image/*");
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            PendingIntent pendingIntent = null;
            try {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
                pendingIntent = PendingIntent.getActivity(context, 0, openIntent, flags);
            } catch (Exception e) {
                LogHelper.w("ScreenshotSavedRx", "PendingIntent: " + e.getMessage());
            }

            NotificationCompat.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new NotificationCompat.Builder(context, CHANNEL_ID);
            } else {
                builder = new NotificationCompat.Builder(context);
            }
            builder.setContentTitle("截图已保存")
                    .setContentText("点击查看截图")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS);
            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent);
            }

            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, builder.build());
            }
        } catch (Exception e) {
            LogHelper.w("ScreenshotSavedRx", "通知失败，改用 Toast: " + e.getMessage());
            showToastOnMainDisplay(context, context.getString(R.string.toast_screenshot_saved));
        }
    }

    /** 与 {@link RearScreenshotTileService} 一致：尽量在主屏显示 Toast。 */
    private static void showToastOnMainDisplay(Context appCtx, String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                Context mainDisplayContext = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        android.hardware.display.DisplayManager dm =
                                (android.hardware.display.DisplayManager)
                                        appCtx.getSystemService(Context.DISPLAY_SERVICE);
                        if (dm != null) {
                            android.view.Display mainDisplay = dm.getDisplay(0);
                            if (mainDisplay != null) {
                                mainDisplayContext = appCtx.createDisplayContext(mainDisplay);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                Context ctx = mainDisplayContext != null ? mainDisplayContext : appCtx;
                Toast toast = Toast.makeText(ctx, message, Toast.LENGTH_SHORT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        java.lang.reflect.Method m = Toast.class.getMethod("setDisplayId", int.class);
                        m.invoke(toast, 0);
                    } catch (Exception ignored) {
                    }
                }
                toast.show();
            } catch (Exception e) {
                try {
                    Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
            }
        });
    }
}
