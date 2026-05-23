package com.wmqc.miroot.rear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.wmqc.miroot.lyrics.LogHelper

/**
 * 磁贴 Keeper（[RearSwitchKeeperService]）的接近传感器：背屏遮盖检测与防抖。
 * 与 Service 生命周期解耦，便于单独调整机型适配与传感器策略。
 */
class RearSwitchProximityController(
    private val context: Context,
    private val mainHandler: Handler,
    private val onCoverConfirmed: Runnable,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isProximityCovered = false
    private var lastProximityTime = 0L

    private var proximitySensorEnabled = false
    var isProximitySensorRegistered = false
        private set

    fun updateFromPrefs() {
        proximitySensorEnabled = RearAssistPrefs.isProximityEnabled(context)
        LogHelper.d(TAG, "🔧 传感器开关状态已恢复: $proximitySensorEnabled")
    }

    fun isProximityEnabled(): Boolean = proximitySensorEnabled

    /**
     * 按当前开关与 [monitoredTaskInfo] 注册或注销接近传感器（有背屏监控任务时才注册）。
     */
    fun applyRegistrationState(monitoredTaskInfo: String?) {
        if (!proximitySensorEnabled) {
            unregisterSensor()
            isProximityCovered = false
            LogHelper.d(TAG, "⏸️ 遮盖检测已关，传感器已注销")
            return
        }
        if (monitoredTaskInfo != null) {
            initProximitySensor()
            LogHelper.d(TAG, "✅ 遮盖检测已开，尝试注册接近传感器")
        } else {
            LogHelper.d(TAG, "⏸️ 遮盖检测已开，暂无背屏任务，待监控启动后再注册")
        }
    }

    /**
     * 确认应用在背屏后调用：仅在开关开启且尚未注册时初始化。
     */
    fun initSensorIfNeeded() {
        if (!isProximitySensorRegistered && proximitySensorEnabled) {
            initProximitySensor()
        }
    }

    private fun initProximitySensor() {
        if (!proximitySensorEnabled) {
            LogHelper.d(TAG, "⏸️ 接近传感器开关已关闭，跳过初始化")
            if (isProximitySensorRegistered) {
                unregisterSensor()
            }
            return
        }
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
            val sm = sensorManager ?: run {
                LogHelper.w(TAG, "⚠ SensorManager not available")
                return
            }
            val allSensors = sm.getSensorList(Sensor.TYPE_ALL)
            var wakeupSensor: Sensor? = null
            var nonWakeupSensor: Sensor? = null
            for (sensor in allSensors) {
                val name = sensor.name
                if (name.contains("Proximity") && name.contains("Back")) {
                    if (name.contains("Wakeup")) {
                        wakeupSensor = sensor
                    } else {
                        nonWakeupSensor = sensor
                    }
                }
            }
            proximitySensor = when {
                wakeupSensor != null -> wakeupSensor
                nonWakeupSensor != null -> {
                    LogHelper.w(TAG, "→ Using NON-WAKEUP sensor (may not provide continuous data)")
                    nonWakeupSensor
                }
                else -> {
                    LogHelper.w(TAG, "⚠ Rear proximity sensor not found, using default")
                    sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                }
            }
            val ps = proximitySensor
            if (ps == null) {
                LogHelper.w(TAG, "⚠ No proximity sensor available")
                isProximitySensorRegistered = false
                return
            }
            if (proximitySensorEnabled) {
                if (isProximitySensorRegistered) {
                    sm.unregisterListener(this)
                    isProximitySensorRegistered = false
                }
                val registered = sm.registerListener(this, ps, SensorManager.SENSOR_DELAY_NORMAL)
                if (registered) {
                    isProximitySensorRegistered = true
                    LogHelper.d(TAG, "✅ 接近传感器已注册 (开关状态: $proximitySensorEnabled)")
                } else {
                    isProximitySensorRegistered = false
                    LogHelper.w(TAG, "⚠ Failed to register proximity sensor")
                }
            } else {
                LogHelper.d(TAG, "⏸️ 接近传感器已禁用，跳过注册")
                isProximitySensorRegistered = false
            }
        } catch (e: Exception) {
            LogHelper.e(TAG, "✗ Error initializing proximity sensor", e)
        }
    }

    fun unregisterSensor() {
        try {
            val sm = sensorManager
            if (sm != null && isProximitySensorRegistered) {
                sm.unregisterListener(this)
                isProximitySensorRegistered = false
                LogHelper.d(TAG, "✅ 接近传感器已注销（优化：仅在需要时启用）")
            } else if (!isProximitySensorRegistered) {
                LogHelper.d(TAG, "ℹ️ 接近传感器未注册，无需注销")
            }
        } catch (e: Exception) {
            LogHelper.e(TAG, "✗ Error unregistering proximity sensor", e)
            isProximitySensorRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!proximitySensorEnabled) return
        val ps = proximitySensor ?: return
        if (event.sensor != ps) return
        val distance = event.values[0]
        val maxRange = ps.maximumRange
        val isCovered = distance < maxRange * 0.2f
        val currentTime = System.currentTimeMillis()
        if (isCovered && !isProximityCovered) {
            isProximityCovered = true
            lastProximityTime = currentTime
            LogHelper.w(TAG, "👋 PROXIMITY COVERED! Distance: $distance cm")
            LogHelper.w(TAG, "👋 Starting debounce timer (${PROXIMITY_DEBOUNCE_MS}ms)...")
            mainHandler.postDelayed({
                if (!proximitySensorEnabled) return@postDelayed
                if (isProximityCovered &&
                    System.currentTimeMillis() - lastProximityTime >= PROXIMITY_DEBOUNCE_MS
                ) {
                    LogHelper.w(TAG, "👋 Debounce timer expired - triggering return to main display!")
                    onCoverConfirmed.run()
                }
            }, PROXIMITY_DEBOUNCE_MS)
        } else if (!isCovered && isProximityCovered) {
            isProximityCovered = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    companion object {
        private const val TAG = "RearSwitchProximity"
        private const val PROXIMITY_DEBOUNCE_MS = 1500L
    }
}
