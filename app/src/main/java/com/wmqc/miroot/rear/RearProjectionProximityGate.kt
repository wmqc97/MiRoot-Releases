package com.wmqc.miroot.rear

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 充电动画等场景暂停投屏期间的背屏遮盖检测，与 [RearAssistService] 充电暂停计数对齐使用。
 */
object RearProjectionProximityGate {
    private val pauseCount = AtomicInteger(0)

    @JvmStatic
    fun isPausedForCharging(): Boolean = pauseCount.get() > 0

    @JvmStatic
    fun pauseForCharging() {
        pauseCount.incrementAndGet()
    }

    @JvmStatic
    fun resumeAfterCharging() {
        pauseCount.updateAndGet { v -> max(0, v - 1) }
    }
}
