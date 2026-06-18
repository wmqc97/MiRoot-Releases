package com.wmqc.miroot.rear

import com.wmqc.miroot.lyrics.LogHelper
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.PermissionCache
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.lyrics.RearScreenWakeManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 仅负责「始终常亮」（无投屏前台也定时 KEYCODE_WAKEUP）。
 * 背屏遮盖检测仅在投屏期间由 [RearProjectionProximitySession] 承担，无单独通知、不常驻本服务。
 */
class RearAssistService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val shellExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "miroot-rear-assist-shell").apply { isDaemon = true }
    }

    @Volatile
    private var alwaysWakeupEnabled = false
    @Volatile
    private var intervalMs = RearAssistPrefs.DEFAULT_INTERVAL_MS

    private val running = AtomicBoolean(false)
    private var screenOffReceiver: BroadcastReceiver? = null

    /** 无投屏注册时的降频间隔：背屏无内容，无需高频唤醒。 */
    private val idleIntervalMs get() = intervalMs.coerceAtLeast(IDLE_WAKE_INTERVAL_MS)

    /** 仅「始终常亮」：按间隔向背屏发送唤醒（与投屏常亮无关）。
     *  无投屏注册时自动降频到 [IDLE_WAKE_INTERVAL_MS]，有投屏时使用用户设置的间隔。 */
    private val wakeupRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (isChargingAssistPaused()) {
                mainHandler.postDelayed(this, intervalMs.toLong())
                return
            }
            val self = this
            if (!alwaysWakeupEnabled) {
                return
            }
            val hasProjection = RearScreenWakeManager.getInstance().hasRegisteredActivities()
            val effectiveInterval = if (hasProjection) intervalMs else idleIntervalMs
            shellExecutor.execute {
                try {
                    PrivilegedShell.execCmd("input -d 1 keyevent KEYCODE_WAKEUP")
                } catch (e: Exception) {
                    LogHelper.w(TAG, "wakeup shell failed", e)
                }
                mainHandler.postDelayed(self, effectiveInterval.toLong())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        registerScreenOff()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALWAYS_WAKE) {
            RearAssistPrefs.prefs(this).edit()
                .putBoolean(RearAssistPrefs.KEY_ALWAYS_WAKEUP, false)
                .commit()
            reloadPrefs()
            broadcastUiRefresh()
            if (!RearAssistPrefs.anyFeatureEnabled(this)) {
                stopForegroundGracefully()
                stopSelf()
                return START_NOT_STICKY
            }
            if (!running.getAndSet(true)) {
                mainHandler.post(wakeupRunnable)
            }
            startAsForeground()
            return START_STICKY
        }

        reloadPrefs()
        if (!RearAssistPrefs.anyFeatureEnabled(this)) {
            stopForegroundGracefully()
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!running.getAndSet(true)) {
            mainHandler.post(wakeupRunnable)
        } else {
            startAsForeground()
        }
        return START_STICKY
    }

    private fun reloadPrefs() {
        val p = RearAssistPrefs.prefs(this)
        alwaysWakeupEnabled = p.getBoolean(RearAssistPrefs.KEY_ALWAYS_WAKEUP, false)
        intervalMs = RearAssistPrefs.intervalMs(this)
    }

    private fun broadcastUiRefresh() {
        sendBroadcast(
            Intent(ACTION_UI_REAR_PREFS_CHANGED).setPackage(packageName),
        )
    }

    private fun startAsForeground() {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            LogHelper.e(TAG, "startForeground failed", e)
        }
    }

    private fun stopForegroundGracefully() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val always = NotificationChannel(
            CHANNEL_ALWAYS_ID,
            getString(R.string.rear_always_wake_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(always)
    }

    private fun buildNotification(): Notification {
        ensureChannels()
        return buildAlwaysWakeNotification()
    }

    private fun buildAlwaysWakeNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this,
            19,
            Intent(this, RearAssistService::class.java).setAction(ACTION_STOP_ALWAYS_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = getString(R.string.rear_always_wake_notif_title)
        val summary = getString(R.string.rear_always_wake_notif_summary)
        val lines = mutableListOf<String>()
        lines.add(getString(R.string.rear_always_wake_notif_big_line))
        lines.add(getString(R.string.rear_always_wake_notif_tap))
        val bigText = lines.joinToString("\n")
        return NotificationCompat.Builder(this, CHANNEL_ALWAYS_ID)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_stat_notify_record)
            .setContentIntent(stopPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun registerScreenOff() {
        if (screenOffReceiver != null) return
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                onScreenOff()
            }
        }
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, f)
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "register SCREEN_OFF failed", e)
        }
    }

    private fun unregisterScreenOff() {
        val r = screenOffReceiver ?: return
        try {
            unregisterReceiver(r)
        } catch (_: Exception) {
        }
        screenOffReceiver = null
    }

    private fun onScreenOff() {
        if (isChargingAssistPaused()) return
        if (!alwaysWakeupEnabled) return
        shellExecutor.execute {
            sendWakeBurst("screen_off")
        }
    }

    private fun sendWakeBurst(reason: String) {
        try {
            PrivilegedShell.execCmd("input -d 1 keyevent KEYCODE_WAKEUP")
        } catch (e: Exception) {
            LogHelper.w(TAG, "wake burst immediate failed ($reason)", e)
        }
        for (i in 1..WAKE_RETRY_COUNT) {
            try {
                Thread.sleep(WAKE_RETRY_DELAY_MS * i)
                PrivilegedShell.execCmd("input -d 1 keyevent KEYCODE_WAKEUP")
            } catch (_: Exception) {
                break
            }
        }
    }

    override fun onDestroy() {
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        unregisterScreenOff()
        stopForegroundGracefully()
        shellExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RearAssistService"
        private const val CHANNEL_ALWAYS_ID = "miroot_rear_always_wake"
        private const val NOTIF_ID = MiRootNotificationIds.ALWAYS_WAKE_NOTIFICATION_ID

        const val ACTION_STOP_ALWAYS_WAKE = "com.wmqc.miroot.rear.STOP_ALWAYS_WAKE"
        const val ACTION_UI_REAR_PREFS_CHANGED = "com.wmqc.miroot.rear.UI_REAR_PREFS_CHANGED"
        private const val WAKE_RETRY_DELAY_MS = 250L
        private const val WAKE_RETRY_COUNT = 4
        /** 无投屏注册时的最低唤醒间隔（10s）：背屏无内容，无需高频唤醒。 */
        private const val IDLE_WAKE_INTERVAL_MS = 10_000

        private val assistPauseForChargingCount = AtomicInteger(0)

        private fun isChargingAssistPaused(): Boolean = assistPauseForChargingCount.get() > 0

        @JvmStatic
        fun pauseMonitoringForCharging() {
            assistPauseForChargingCount.incrementAndGet()
        }

        @JvmStatic
        fun resumeMonitoringAfterCharging() {
            assistPauseForChargingCount.updateAndGet { v -> max(0, v - 1) }
        }

        const val ACTION_SYNC = "com.wmqc.miroot.rear.SYNC"

        @JvmStatic
        fun sync(context: Context, privilegedShellAvailable: Boolean) {
            val ctx = context.applicationContext
            if (RearScreenWakeManager.getInstance().hasRegisteredActivities()) {
                ctx.stopService(Intent(ctx, RearAssistService::class.java))
                return
            }
            if (!RearAssistPrefs.anyFeatureEnabled(ctx)) {
                ctx.stopService(Intent(ctx, RearAssistService::class.java))
                return
            }
            if (!privilegedShellAvailable) {
                ctx.stopService(Intent(ctx, RearAssistService::class.java))
                return
            }
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, RearAssistService::class.java).setAction(ACTION_SYNC),
            )
        }

        @JvmStatic
        fun sync(context: Context) {
            sync(context, PermissionCache.privileged)
        }
    }
}
