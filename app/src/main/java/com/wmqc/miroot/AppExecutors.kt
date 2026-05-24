package com.wmqc.miroot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用级共享资源：统一后台线程调度与 HTTP 客户端，减少线程总数与虚拟内存占用。
 *
 * 设计决策：
 * - Kotlin 后台工作使用协程 + [Dispatchers.IO.limitedParallelism] 替代 raw thread()；
 * - Java 文件使用 [backgroundExecutor]（固定大小线程池 + daemon 线程），替代 new Thread()；
 * - OkHttp 全局单例复用连接池与调度器线程。
 */
object AppExecutors {

    /** Daemon 线程工厂：低优先级、短命名，降低线程栈感知开销。 */
    private val daemonThreadFactory: ThreadFactory = object : ThreadFactory {
        private val counter = AtomicInteger(1)
        override fun newThread(r: Runnable): Thread =
            Thread(r, "MiRoot-Bg-${counter.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
    }

    /**
     * Java 文件使用的共享后台线程池（4 条 daemon 线程）。
     * 所有 fire-and-forget `new Thread(() -> { ... }).start()` 应改用此处。
     */
    @JvmField
    val backgroundExecutor: ExecutorService = Executors.newFixedThreadPool(4, daemonThreadFactory)

    /**
     * 协程后台作用域，用于 Kotlin fire-and-forget 操作替代 raw thread()。
     * 使用 [SupervisorJob] 确保单个协程失败不影响其他。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))

    /**
     * 全局 OkHttpClient 单例：长超时（30s 连接 / 30s 读写），适用于 Afdian API 与 GitHub Release API。
     * 连接池复用减少重复新建 Dispatcher 线程。
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Kotlin：在后台协程中执行阻塞操作，替代 [kotlin.concurrent.thread]。
     */
    fun runInBackground(block: suspend () -> Unit) {
        backgroundScope.launch {
            block()
        }
    }

    /**
     * Java：提交 fire-and-forget 任务到共享后台线程池。
     * 用法：AppExecutors.runInBackground(() -> { ... });
     */
    @JvmStatic
    fun runInBackground(runnable: Runnable) {
        backgroundExecutor.execute(runnable)
    }
}
