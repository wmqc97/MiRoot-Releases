package com.wmqc.miroot.rear.heartrate

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wmqc.miroot.lyrics.LogHelper

enum class HeartRateMonitorStatus {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    NO_SIGNAL,
}

data class HeartRateReading(
    val bpm: Int,
    val deviceName: String?,
    val address: String?,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * 扫描并订阅标准蓝牙心率广播（Heart Rate Service 0x180D）。
 * 优先解析广播包中的 Service Data；若无则连接设备并订阅 0x2A37 通知。
 */
class BleHeartRateMonitor(context: Context) {

    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager =
        app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    var onReading: ((HeartRateReading) -> Unit)? = null
    var onStatus: ((HeartRateMonitorStatus) -> Unit)? = null

    private var running = false
    private var gatt: BluetoothGatt? = null
    private var connectedAddress: String? = null
    private var lastReadingMs = 0L
    private var bestCandidate: ScanResult? = null
    private var connectAttemptMs = 0L

    private val noSignalRunnable =
        Runnable {
            if (!running) return@Runnable
            if (System.currentTimeMillis() - lastReadingMs > NO_SIGNAL_TIMEOUT_MS) {
                emitStatus(HeartRateMonitorStatus.NO_SIGNAL)
            }
        }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                LogHelper.w(TAG, "scan failed code=$errorCode")
                if (running) {
                    mainHandler.postDelayed({ restartScanIfNeeded() }, 1_500L)
                }
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!running) {
                    gatt.close()
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedAddress = gatt.device.address
                        emitStatus(HeartRateMonitorStatus.CONNECTING)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedAddress == gatt.device.address) {
                            connectedAddress = null
                        }
                        gatt.close()
                        if (this@BleHeartRateMonitor.gatt == gatt) {
                            this@BleHeartRateMonitor.gatt = null
                        }
                        if (running) {
                            emitStatus(HeartRateMonitorStatus.SCANNING)
                            restartScanIfNeeded()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || !running) return
                val service = gatt.getService(HeartRateMeasurementParser.HEART_RATE_SERVICE.uuid) ?: return
                val characteristic =
                    service.getCharacteristic(HeartRateMeasurementParser.HEART_RATE_MEASUREMENT.uuid)
                        ?: return
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor =
                    characteristic.getDescriptor(HeartRateMeasurementParser.CLIENT_CONFIG.uuid)
                if (descriptor != null) {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                emitStatus(HeartRateMonitorStatus.CONNECTED)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                deliverFromGatt(gatt.device, characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                deliverFromGatt(gatt.device, value)
            }
        }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!BlePermissionHelper.hasAll(app)) return
        if (!isBluetoothEnabled()) return
        running = true
        lastReadingMs = 0L
        bestCandidate = null
        emitStatus(HeartRateMonitorStatus.SCANNING)
        startScanInternal()
        scheduleNoSignalCheck()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        mainHandler.removeCallbacks(noSignalRunnable)
        stopScanInternal()
        disconnectGatt()
        emitStatus(HeartRateMonitorStatus.IDLE)
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal() {
        val leScanner = scanner ?: return
        val filter =
            ScanFilter.Builder()
                .setServiceUuid(HeartRateMeasurementParser.HEART_RATE_SERVICE)
                .build()
        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            LogHelper.w(TAG, "startScan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            LogHelper.w(TAG, "stopScan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartScanIfNeeded() {
        if (!running || gatt != null) return
        stopScanInternal()
        startScanInternal()
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        if (!running) return
        val fromAd = HeartRateMeasurementParser.parseFromScanRecord(result.scanRecord)
        if (fromAd != null) {
            publishReading(fromAd, result.device)
            return
        }
        val now = System.currentTimeMillis()
        val current = bestCandidate
        if (current == null || result.rssi > current.rssi) {
            bestCandidate = result
        }
        if (gatt != null) return
        if (now - connectAttemptMs < CONNECT_COOLDOWN_MS) return
        val candidate = bestCandidate ?: return
        connectAttemptMs = now
        bestCandidate = null
        connectDevice(candidate.device)
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        disconnectGatt()
        emitStatus(HeartRateMonitorStatus.CONNECTING)
        stopScanInternal()
        gatt =
            device.connectGatt(
                app,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            LogHelper.w(TAG, "disconnectGatt: ${e.message}")
        }
        gatt = null
        connectedAddress = null
    }

    private fun deliverFromGatt(device: BluetoothDevice, value: ByteArray?) {
        val bpm = HeartRateMeasurementParser.parse(value) ?: return
        publishReading(bpm, device)
    }

    @SuppressLint("MissingPermission")
    private fun publishReading(bpm: Int, device: BluetoothDevice) {
        lastReadingMs = System.currentTimeMillis()
        val name = device.name?.trim()?.takeIf { it.isNotEmpty() }
        onReading?.invoke(
            HeartRateReading(
                bpm = bpm,
                deviceName = name,
                address = device.address,
            ),
        )
        emitStatus(
            if (gatt != null) {
                HeartRateMonitorStatus.CONNECTED
            } else {
                HeartRateMonitorStatus.SCANNING
            },
        )
        scheduleNoSignalCheck()
    }

    private fun emitStatus(status: HeartRateMonitorStatus) {
        mainHandler.post { onStatus?.invoke(status) }
    }

    private fun scheduleNoSignalCheck() {
        mainHandler.removeCallbacks(noSignalRunnable)
        if (running) {
            mainHandler.postDelayed(noSignalRunnable, NO_SIGNAL_TIMEOUT_MS)
        }
    }

    private companion object {
        private const val TAG = "BleHeartRateMonitor"
        private const val NO_SIGNAL_TIMEOUT_MS = 12_000L
        private const val CONNECT_COOLDOWN_MS = 4_000L
    }
}
