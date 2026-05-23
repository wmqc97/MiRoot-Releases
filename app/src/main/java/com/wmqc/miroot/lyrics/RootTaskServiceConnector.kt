package com.wmqc.miroot.lyrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock

/**
 * 全局复用 [RootTaskService] 绑定，避免手势/广播投屏每次冷绑 + 250ms 轮询等待。
 */
object RootTaskServiceConnector {

    private const val TAG = "RootTaskSvcConnector"
    private const val POLL_MS = 30L
    private const val DEFAULT_AWAIT_MS = 2_500L

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var taskService: ITaskService? = null

    @Volatile
    private var bindRequested = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                taskService = ITaskService.Stub.asInterface(binder)
                LogHelper.d(TAG, "connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                taskService = null
                bindRequested = false
                LogHelper.d(TAG, "disconnected")
            }
        }

    /** Application 或手势广播入口预热，后续投屏可立即拿到 TaskService。 */
    @JvmStatic
    fun prewarm(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext == null) {
                appContext = app
            }
            bindLocked(app)
        }
    }

    @JvmStatic
    fun getIfConnected(): ITaskService? = taskService

    /**
     * @return 已连接的 [ITaskService]，超时返回 null
     */
    @JvmStatic
    @JvmOverloads
    fun ensureConnected(context: Context, timeoutMs: Long = DEFAULT_AWAIT_MS): ITaskService? {
        prewarm(context)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(POLL_MS)
        while (SystemClock.elapsedRealtime() < deadline) {
            taskService?.let { return it }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return taskService
    }

    private fun bindLocked(app: Context) {
        if (taskService != null || bindRequested) return
        try {
            val ok =
                app.bindService(
                    Intent(app, RootTaskService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            if (ok) {
                bindRequested = true
                LogHelper.d(TAG, "bindService requested")
            } else {
                LogHelper.w(TAG, "bindService returned false")
            }
        } catch (e: Exception) {
            LogHelper.e(TAG, "bindService failed", e)
        }
    }
}
