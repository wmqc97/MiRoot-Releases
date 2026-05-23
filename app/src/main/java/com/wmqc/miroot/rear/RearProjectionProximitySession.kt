package com.wmqc.miroot.rear
import com.wmqc.miroot.display.MainDisplayUi

import com.wmqc.miroot.lyrics.LogHelper
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.wmqc.miroot.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅在投屏 Activity 位于背屏时挂接背屏接近传感器；遮盖时迁回主屏。
 * 不启动 [RearAssistService]，无单独前台通知。
 */
class RearProjectionProximitySession(
    host: Context,
) : SensorEventListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = host.applicationContext
    private val shellExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "miroot-rear-proj-prox").apply { isDaemon = true }
    }

    private val attached = AtomicBoolean(false)
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityRegistered = false

    private var isProximityCovered = false
    private var lastProximityTime = 0L
    private val proximityDebounceMs = 1500L

    private val proximityDebounceRunnable = Runnable {
        if (!attached.get() || !RearAssistPrefs.isProximityEnabled(appContext)) return@Runnable
        if (isProximityCovered &&
            System.currentTimeMillis() - lastProximityTime >= proximityDebounceMs
        ) {
            onProximityConfirmedCovered()
        }
    }

    fun attach() {
        if (!RearAssistPrefs.isProximityEnabled(appContext)) {
            detach()
            return
        }
        if (!attached.compareAndSet(false, true)) {
            return
        }
        initProximitySensorIfNeeded()
    }

    fun detach() {
        if (!attached.compareAndSet(true, false)) {
            return
        }
        unregisterProximitySensor()
        isProximityCovered = false
        mainHandler.removeCallbacks(proximityDebounceRunnable)
    }

    fun releaseExecutor() {
        detach()
        shellExecutor.shutdownNow()
    }

    private fun initProximitySensorIfNeeded() {
        if (!RearAssistPrefs.isProximityEnabled(appContext) || proximityRegistered) return
        try {
            val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager = sm
            if (sm == null) return
            val all = sm.getSensorList(Sensor.TYPE_ALL)
            var wakeup: Sensor? = null
            var nonWakeup: Sensor? = null
            for (s in all) {
                val name = s.name
                if (name.contains("Proximity", ignoreCase = true) &&
                    name.contains("Back", ignoreCase = true)
                ) {
                    if (name.contains("Wakeup", ignoreCase = true)) {
                        wakeup = s
                    } else {
                        nonWakeup = s
                    }
                }
            }
            proximitySensor = when {
                wakeup != null -> wakeup
                nonWakeup != null -> nonWakeup
                else -> sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            }
            val sensor = proximitySensor ?: return
            val ok = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
            if (ok) {
                proximityRegistered = true
                LogHelper.d(TAG, "projection proximity registered")
            }
        } catch (e: Exception) {
            LogHelper.e(TAG, "init proximity failed", e)
        }
    }

    private fun unregisterProximitySensor() {
        try {
            if (sensorManager != null && proximityRegistered) {
                sensorManager?.unregisterListener(this)
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "unregister proximity failed", e)
        }
        proximityRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !attached.get() || !RearAssistPrefs.isProximityEnabled(appContext)) return
        if (event.sensor != proximitySensor) return
        val sensor = proximitySensor ?: return
        if (RearProjectionProximityGate.isPausedForCharging()) return
        val distance = event.values[0]
        val maxRange = sensor.maximumRange
        val covered = distance < maxRange * 0.2f
        val now = System.currentTimeMillis()
        if (covered && !isProximityCovered) {
            isProximityCovered = true
            lastProximityTime = now
            mainHandler.removeCallbacks(proximityDebounceRunnable)
            mainHandler.postDelayed(proximityDebounceRunnable, proximityDebounceMs)
        } else if (!covered && isProximityCovered) {
            isProximityCovered = false
            mainHandler.removeCallbacks(proximityDebounceRunnable)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onProximityConfirmedCovered() {
        if (!attached.get() || !RearAssistPrefs.isProximityEnabled(appContext)) return
        if (RearProjectionProximityGate.isPausedForCharging()) return
        shellExecutor.execute {
            val spec = try {
                RearDisplayTaskCommands.foregroundOnDisplay(1)
            } catch (_: Exception) {
                null
            } ?: return@execute
            val parsed = RearDisplayTaskCommands.parsePackageTaskId(spec) ?: return@execute
            val (_, taskId) = parsed
            val ok = RearDisplayTaskCommands.moveTaskToDisplay(taskId, 0)
            mainHandler.post {
                if (ok) {
                    ProjectionOngoingNotifications.cancelAll(appContext)
                }
                val msg = if (ok) {
                    appContext.getString(R.string.rear_assist_returned_main_ok)
                } else {
                    appContext.getString(R.string.rear_assist_returned_main_fail)
                }
                MainDisplayUi.showToast(appContext, msg, Toast.LENGTH_SHORT)
            }
        }
    }

    companion object {
        private const val TAG = "RearProjProximity"
    }
}
