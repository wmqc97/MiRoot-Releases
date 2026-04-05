package com.wmqc.miroot.rear

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wmqc.miroot.R

/**
 * 「投屏常亮」总开关关闭时，仅展示「投屏显示」通知（不含投屏常亮文案）；与 [RearScreenWakeService] 合并通知互斥。
 */
object ProjectionOnlyNotificationHelper {

    const val MUSIC_PROJECTION_ONLY_ID = 10026
    const val CAR_PROJECTION_ONLY_ID = 10027

    private const val CH_MUSIC = "projection_display_music"
    private const val CH_CAR = "projection_display_car"

    private fun ensureChannel(ctx: Context, channelId: String, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT)
        ch.setShowBadge(false)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
    }

    @JvmStatic
    fun showMusic(activity: Activity) {
        val ctx = activity.applicationContext
        ensureChannel(ctx, CH_MUSIC, activity.getString(R.string.projection_display_channel_music))
        val intent = Intent(ctx, ProjectionNotificationStopReceiver::class.java).apply {
            action = ProjectionNotificationStopReceiver.ACTION_STOP_MUSIC_PROJECTION_FROM_NOTIFICATION
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            30,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = activity.getString(R.string.projection_notify_music_only_title)
        val summary = activity.getString(R.string.projection_notify_music_only_summary)
        val bigText = activity.getString(R.string.projection_notify_music_only_big)
        val builder = NotificationCompat.Builder(ctx, CH_MUSIC)
        val notification = builder
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_stat_notify_record)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(MUSIC_PROJECTION_ONLY_ID, notification)
    }

    @JvmStatic
    fun showCar(activity: Activity) {
        val ctx = activity.applicationContext
        ensureChannel(ctx, CH_CAR, activity.getString(R.string.projection_display_channel_car))
        val intent = Intent(ctx, ProjectionNotificationStopReceiver::class.java).apply {
            action = ProjectionNotificationStopReceiver.ACTION_STOP_CAR_PROJECTION_FROM_NOTIFICATION
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            31,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = activity.getString(R.string.projection_notify_car_only_title)
        val summary = activity.getString(R.string.projection_notify_car_only_summary)
        val bigText = activity.getString(R.string.projection_notify_car_only_big)
        val builder = NotificationCompat.Builder(ctx, CH_CAR)
        val notification = builder
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_stat_notify_record)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(CAR_PROJECTION_ONLY_ID, notification)
    }

    @JvmStatic
    fun cancelMusic(ctx: Context) {
        val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.cancel(MUSIC_PROJECTION_ONLY_ID)
    }

    @JvmStatic
    fun cancelCar(ctx: Context) {
        val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.cancel(CAR_PROJECTION_ONLY_ID)
    }
}
