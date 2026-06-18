package com.wmqc.miroot.car.ble

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
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.wmqc.miroot.lyrics.LogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class DigitalCarKeyState {
    IDLE,
    SCANNING,
    SCANNING_ALL,
    CONNECTING,
    DISCOVERING_SERVICES,
    AUTHENTICATING,
    READY,
    RECONNECTING,
    FAILED,
    DISCONNECTED,
}

enum class DigitalCarKeyError {
    BLUETOOTH_OFF,
    PERMISSION_DENIED,
    SCAN_TIMEOUT,
    SCAN_FAILED,
    CONNECT_TIMEOUT,
    CONNECT_FAILED,
    SERVICE_DISCOVERY_FAILED,
    SERVICE_NOT_FOUND,
    CONTROL_CHAR_NOT_FOUND,
    STATUS_CHAR_NOT_FOUND,
    AUTH_TIMEOUT,
    AUTH_FAILED,
    COMMAND_TIMEOUT,
    COMMAND_FAILED,
    INTERNAL_ERROR,
    NO_SIGNAL,
}

data class DigitalCarKeyEvent(
    val state: DigitalCarKeyState,
    val error: DigitalCarKeyError? = null,
    val message: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
)

data class BleCommand(
    val id: Int,
    val data: ByteArray,
    val characteristicUuid: UUID,
    val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
    val timeoutMs: Long = DigitalCarKeyConstants.COMMAND_TIMEOUT_MS,
    val onResult: ((Boolean, ByteArray?) -> Unit)? = null,
)

class DigitalCarKeyService(context: Context) {

    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bluetoothManager =
        app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private val connectedAddress = AtomicReference<String?>(null)
    private val reconnectAttempts = AtomicInteger(0)

    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var authCharacteristic: BluetoothGattCharacteristic? = null
    private var activeServiceUuid: UUID? = null
    private var matchedProfile: DigitalCarKeyConstants.DkBleProfile? = null
    private var authTimeoutTask: Runnable? = null
    private var serviceDiscoveryTimeoutTask: Runnable? = null
    private val notifyCharacteristics = mutableListOf<BluetoothGattCharacteristic>()

    private val commandQueue = ConcurrentLinkedQueue<BleCommand>()
    private val pendingCommand = AtomicReference<BleCommand?>(null)
    private var commandSequence = AtomicInteger(0)
    private var isProcessingQueue = false

    private val _state = MutableStateFlow(DigitalCarKeyEvent(DigitalCarKeyState.IDLE))
    val state: StateFlow<DigitalCarKeyEvent> = _state.asStateFlow()

    /** 扫描到车辆或 BLE 设备时触发*/
    var onDeviceFound: ((BluetoothDevice, Int, ByteArray?) -> Unit)? = null

    /** 扫描到设备时的增强日志回调，包含所有Service UUID 列表 */
    var onDeviceFoundDetailed: ((BluetoothDevice, Int, List<ParcelUuid>?) -> Unit)? = null

    var onCommandResult: ((BleCommand, Boolean, ByteArray?) -> Unit)? = null

    /** GATT 服务发现完成后的完整快照 */
    var onGattProfileDiscovered: ((GattProfileSnapshot) -> Unit)? = null

    /** 匹配到已知协议配置时回调 */
    var onProfileMatched: ((DigitalCarKeyConstants.DkBleProfile) -> Unit)? = null

    /** 特征读/通知探测结果 */
    var onProbeResult: ((GattProbeEntry) -> Unit)? = null

    private var running = false
    private var connectTimeoutTask: Runnable? = null
    private var scanTimeoutTask: Runnable? = null
    private var noSignalTask: Runnable? = null
    private var lastDataMs = 0L
    private var autoReconnect = true

    /** 是否扫描所有BLE 设备（而非屏蔽 UUID）*/
    private var scanAllMode = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (s) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        LogHelper.w(TAG, "Bluetooth turned off")
                        handleBluetoothOff()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        LogHelper.d(TAG, "Bluetooth turned on")
                        if (running) restartScanIfNeeded()
                    }
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            app.registerReceiver(bluetoothStateReceiver, filter)
        } catch (e: Exception) {
            LogHelper.w(TAG, "register BT receiver failed: " + e.message)
        }
    }

    // ============ Public API ============

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun getConnectedDeviceAddress(): String? = connectedAddress.get()

    fun getMatchedProfile(): DigitalCarKeyConstants.DkBleProfile? = matchedProfile

    fun setAutoReconnect(enable: Boolean) { autoReconnect = enable }

    /** 手动触发 GATT 特征探测（读可读特征、开启通知） */
    fun probeGattProfile() {
        val g = gatt ?: return
        probeReadableCharacteristics(g)
    }

    /**
     * 指定服务 UUID 扫描（默认）。
     * 只扫描包含配置的 digitalKeyServiceUuid 的设备。
     */
    fun startScan() {
        if (!checkPreconditions()) return
        scanAllMode = false
        running = true
        emitState(DigitalCarKeyState.SCANNING)
        lastDataMs = System.currentTimeMillis()
        startScanInternal(createVehicleFilter())
        scheduleScanTimeout()
    }

    /**
     * 扫描所有BLE 设备（无 UUID 屏蔽）。
     * 用于发现实际车辆宣传的服务UUID，帮助确认参数。
     */
    fun startScanAllDevices() {
        if (!checkPreconditions()) return
        scanAllMode = true
        running = true
        emitState(DigitalCarKeyState.SCANNING_ALL)
        lastDataMs = System.currentTimeMillis()
        startScanInternal(emptyList()) // 无滤点时扫描所有
        scheduleScanTimeout()
    }

    /**
     * 使用自定义滤点扫描/>
     */
    fun startScanWithFilter(filter: ScanFilter) {
        if (!checkPreconditions()) return
        scanAllMode = false
        running = true
        emitState(DigitalCarKeyState.SCANNING)
        lastDataMs = System.currentTimeMillis()
        startScanInternal(listOf(filter))
        scheduleScanTimeout()
    }

    fun stopScan() {
        cancelScanTimeout()
        stopScanInternal()
    }

    fun shutdown() {
        running = false
        autoReconnect = false
        stopScanInternal()
        disconnectGatt()
        commandQueue.clear()
        pendingCommand.set(null)
        isProcessingQueue = false
        cancelAllTimeouts()
        emitState(DigitalCarKeyState.DISCONNECTED)
        try { app.unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) { }
    }

    fun connectToDevice(device: BluetoothDevice) {
        if (!checkPreconditions()) return
        stopScanInternal()
        cancelScanTimeout()
        running = true
        connectDevice(device)
    }

    fun sendCommand(
        data: ByteArray,
        characteristicUuid: UUID = DigitalCarKeyConstants.controlCharacteristicUuid.uuid,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        timeoutMs: Long = DigitalCarKeyConstants.COMMAND_TIMEOUT_MS,
        onResult: ((Boolean, ByteArray?) -> Unit)? = null,
    ): Int {
        val id = commandSequence.incrementAndGet()
        val cmd = BleCommand(
            id = id,
            data = data,
            characteristicUuid = characteristicUuid,
            writeType = writeType,
            timeoutMs = timeoutMs,
            onResult = onResult,
        )
        commandQueue.add(cmd)
        LogHelper.d(TAG, "Cmd " + id + " queued, size=" + commandQueue.size)
        processCommandQueue()
        return id
    }

    fun disconnect() {
        autoReconnect = false
        disconnectGatt()
        commandQueue.clear()
        pendingCommand.set(null)
        emitState(DigitalCarKeyState.DISCONNECTED)
    }

    // ============ Internal Helpers ============

    private fun checkPreconditions(): Boolean {
        if (!DigitalCarKeyPermissionHelper.isLeSupported(app)) {
            emitError(DigitalCarKeyError.INTERNAL_ERROR, "No BLE support")
            return false
        }
        if (!DigitalCarKeyPermissionHelper.hasAll(app)) {
            emitError(DigitalCarKeyError.PERMISSION_DENIED, "Missing BLE permissions")
            return false
        }
        if (!isBluetoothEnabled()) {
            emitError(DigitalCarKeyError.BLUETOOTH_OFF, "Bluetooth is off")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal(filters: List<ScanFilter>) {
        val leScanner = scanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(DigitalCarKeyConstants.DEFAULT_SCAN_MODE)
            .build()
        try {
            leScanner.startScan(filters, settings, scanCallback)
            if (filters.isEmpty()) {
                LogHelper.d(TAG, "Scan all BLE devices started")
            } else {
                LogHelper.d(TAG, "Scan started with " + filters.size + " filter(s)")
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "Scan start failed: " + e.message)
            emitError(DigitalCarKeyError.SCAN_FAILED, e.message ?: "")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        try { scanner?.stopScan(scanCallback) }
        catch (e: Exception) { LogHelper.w(TAG, "Scan stop: " + e.message) }
    }

    @SuppressLint("MissingPermission")
    private fun restartScanIfNeeded() {
        if (!running || gatt != null) return
        stopScanInternal()
        if (scanAllMode) {
            startScanInternal(emptyList())
        } else {
            startScanInternal(createVehicleFilter())
        }
    }

    private fun createVehicleFilter(): List<ScanFilter> {
        return listOf(
            ScanFilter.Builder()
                .setServiceUuid(DigitalCarKeyConstants.digitalKeyServiceUuid)
                .build()
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        cancelConnectTimeout()
        disconnectGatt()
        emitState(DigitalCarKeyState.CONNECTING, "Connecting: " + device.address)
        LogHelper.d(TAG, "Connecting to " + device.address)
        gatt = device.connectGatt(
            app,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
        scheduleConnectTimeout(device.address)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        try { gatt?.disconnect(); gatt?.close() }
        catch (e: Exception) { LogHelper.w(TAG, "Gatt close: " + e.message) }
        gatt = null
        connectedAddress.set(null)
        controlCharacteristic = null
        statusCharacteristic = null
        authCharacteristic = null
        activeServiceUuid = null
        matchedProfile = null
        notifyCharacteristics.clear()
        cancelAuthTimeout()
        cancelServiceDiscoveryTimeout()
    }

    // ============ Command Queue ============

    @SuppressLint("MissingPermission")
    private fun processCommandQueue() {
        if (isProcessingQueue || gatt == null) return
        val gatt = gatt ?: return
        val cmd = commandQueue.poll() ?: return
        isProcessingQueue = true
        pendingCommand.set(cmd)
        val serviceUuid = activeServiceUuid ?: DigitalCarKeyConstants.digitalKeyServiceUuid.uuid
        val characteristic = gatt.getService(serviceUuid)
            ?.getCharacteristic(cmd.characteristicUuid)
        if (characteristic == null) {
            LogHelper.e(TAG, "Cmd " + cmd.id + ": char not found " + cmd.characteristicUuid)
            completeCommand(cmd, false, null)
            return
        }
        characteristic.writeType = cmd.writeType
        characteristic.value = cmd.data
        val success = try { gatt.writeCharacteristic(characteristic) }
            catch (e: Exception) { LogHelper.e(TAG, "Cmd " + cmd.id + " write: " + e.message); false }
        if (!success) {
            LogHelper.w(TAG, "Cmd " + cmd.id + " write failed")
            completeCommand(cmd, false, null)
        } else {
            LogHelper.d(TAG, "Cmd " + cmd.id + " written")
            if (cmd.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                completeCommand(cmd, true, null)
            }
        }
    }

    private fun completeCommand(cmd: BleCommand, success: Boolean, response: ByteArray?) {
        cmd.onResult?.invoke(success, response)
        onCommandResult?.invoke(cmd, success, response)
        pendingCommand.compareAndSet(cmd, null)
        isProcessingQueue = false
        processCommandQueue()
    }

    // ============ Timeout Management ============

    private fun scheduleScanTimeout() {
        cancelScanTimeout()
        val task = Runnable {
            if (!running) return@Runnable
            if (gatt == null) {
                LogHelper.w(TAG, "Scan timeout")
                emitError(DigitalCarKeyError.SCAN_TIMEOUT, "No vehicle found")
            }
        }
        scanTimeoutTask = task
        mainHandler.postDelayed(task, DigitalCarKeyConstants.SCAN_TIMEOUT_MS)
    }

    private fun cancelScanTimeout() {
        scanTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutTask = null
    }

    private fun scheduleConnectTimeout(deviceAddress: String) {
        cancelConnectTimeout()
        val task = Runnable {
            if (connectedAddress.get() == deviceAddress) {
                LogHelper.w(TAG, "Connect timeout: " + deviceAddress)
                emitError(DigitalCarKeyError.CONNECT_TIMEOUT, "Connect timeout")
                disconnectGatt()
                attemptReconnect()
            }
        }
        connectTimeoutTask = task
        mainHandler.postDelayed(task, DigitalCarKeyConstants.CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutTask = null
    }

    private fun scheduleNoSignalCheck() {
        cancelNoSignalCheck()
        val task = Runnable {
            if (!running) return@Runnable
            val elapsed = System.currentTimeMillis() - lastDataMs
            if (elapsed > DigitalCarKeyConstants.NO_SIGNAL_TIMEOUT_MS && gatt != null) {
                LogHelper.w(TAG, "No signal for " + elapsed + "ms")
                emitState(DigitalCarKeyState.RECONNECTING, "No signal")
                disconnectGatt()
                attemptReconnect()
            }
        }
        noSignalTask = task
        mainHandler.postDelayed(task, DigitalCarKeyConstants.NO_SIGNAL_TIMEOUT_MS)
    }

    private fun cancelNoSignalCheck() {
        noSignalTask?.let { mainHandler.removeCallbacks(it) }
        noSignalTask = null
    }

    private fun cancelAllTimeouts() {
        cancelScanTimeout()
        cancelConnectTimeout()
        cancelNoSignalCheck()
        cancelAuthTimeout()
        cancelServiceDiscoveryTimeout()
    }

    private fun scheduleServiceDiscoveryTimeout() {
        cancelServiceDiscoveryTimeout()
        val task = Runnable {
            if (gatt != null && _state.value.state == DigitalCarKeyState.DISCOVERING_SERVICES) {
                LogHelper.w(TAG, "Service discovery timeout")
                emitError(DigitalCarKeyError.SERVICE_DISCOVERY_FAILED, "Discovery timeout")
                disconnectGatt()
                attemptReconnect()
            }
        }
        serviceDiscoveryTimeoutTask = task
        mainHandler.postDelayed(task, DigitalCarKeyConstants.SERVICE_DISCOVERY_TIMEOUT_MS)
    }

    private fun cancelServiceDiscoveryTimeout() {
        serviceDiscoveryTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        serviceDiscoveryTimeoutTask = null
    }

    private fun scheduleAuthTimeout() {
        cancelAuthTimeout()
        val task = Runnable {
            if (_state.value.state == DigitalCarKeyState.AUTHENTICATING) {
                LogHelper.w(TAG, "Auth timeout")
                emitError(DigitalCarKeyError.AUTH_TIMEOUT, "Auth timeout")
            }
        }
        authTimeoutTask = task
        mainHandler.postDelayed(task, DigitalCarKeyConstants.AUTH_TIMEOUT_MS)
    }

    private fun cancelAuthTimeout() {
        authTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        authTimeoutTask = null
    }

    // ============ Reconnect ============

    private fun attemptReconnect() {
        if (!autoReconnect || !running) return
        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts > DigitalCarKeyConstants.MAX_RECONNECT_ATTEMPTS) {
            LogHelper.e(TAG, "Reconnect exhausted: " + attempts)
            emitError(DigitalCarKeyError.CONNECT_FAILED, "Reconnect failed")
            return
        }
        val delay = calculateBackoff(attempts)
        LogHelper.d(TAG, "Reconnect " + attempts + " in " + delay + "ms")
        emitState(DigitalCarKeyState.RECONNECTING, "Retry in " + delay + "ms")
        mainScope.launch {
            kotlinx.coroutines.delay(delay)
            if (running && autoReconnect) {
                if (scanAllMode) startScanAllDevices() else startScan()
            }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val base = DigitalCarKeyConstants.RECONNECT_BASE_DELAY_MS
        val exponential = base shl (attempt - 1).coerceAtMost(4)
        return exponential.coerceAtMost(DigitalCarKeyConstants.RECONNECT_MAX_DELAY_MS)
    }

    private fun resetReconnectState() { reconnectAttempts.set(0) }

    private fun handleBluetoothOff() {
        emitError(DigitalCarKeyError.BLUETOOTH_OFF, "Bluetooth off")
        disconnectGatt()
        commandQueue.clear()
        pendingCommand.set(null)
        isProcessingQueue = false
    }

    // ============ State Flow ============

    private fun emitState(state: DigitalCarKeyState, message: String = "") {
        _state.value = DigitalCarKeyEvent(state = state, message = message)
    }

    private fun emitError(error: DigitalCarKeyError, message: String = "") {
        _state.value = DigitalCarKeyEvent(
            state = when (error) {
                DigitalCarKeyError.BLUETOOTH_OFF -> DigitalCarKeyState.FAILED
                DigitalCarKeyError.PERMISSION_DENIED -> DigitalCarKeyState.FAILED
                DigitalCarKeyError.SCAN_TIMEOUT -> DigitalCarKeyState.FAILED
                DigitalCarKeyError.SCAN_FAILED -> DigitalCarKeyState.FAILED
                DigitalCarKeyError.CONNECT_TIMEOUT -> DigitalCarKeyState.FAILED
                DigitalCarKeyError.CONNECT_FAILED -> DigitalCarKeyState.FAILED
                DigitalCarKeyError.NO_SIGNAL -> DigitalCarKeyState.RECONNECTING
                else -> DigitalCarKeyState.FAILED
            },
            error = error,
            message = message,
        )
    }

    // ============ BLE Scan Callback ============

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            lastDataMs = System.currentTimeMillis()
            val device = result.device
            val rssi = result.rssi
            val scanRecord = result.scanRecord
            val scanRecordBytes = scanRecord?.bytes

            // 日志记录详细信息
            val deviceName = device.name ?: "(unnamed)"
            val serviceUuids = scanRecord?.serviceUuids
            var serviceUuidStr = ""
            if (serviceUuids != null && serviceUuids.isNotEmpty()) {
                serviceUuidStr = serviceUuids.joinToString(",") { it.uuid.toString() }
            }

            // 获取制造商数据 (对于提示车辆芯片厂商有帮助
             val manufacturerMap = scanRecord?.manufacturerSpecificData
            var mfrStr = ""
            if (manufacturerMap != null && manufacturerMap.size() > 0) {
                mfrStr = (0 until manufacturerMap.size()).joinToString(",") {
                    String.format("0x%04X", manufacturerMap.keyAt(it))
                }
            }

            LogHelper.d(TAG, "[SCAN] " + deviceName + " (" + device.address + ") RSSI=" + rssi
                + " UUIDs=[" + serviceUuidStr + "] MFR=[" + mfrStr + "]")

            // 所有设备通知 (无论是否匹配)
            onDeviceFound?.invoke(device, rssi, scanRecordBytes)
            onDeviceFoundDetailed?.invoke(device, rssi, serviceUuids)

            // 在scanAllMode 下不自动连接，只记录
            if (!scanAllMode && gatt == null) {
                val matchesConfigured = serviceUuids?.any { pu ->
                    pu.uuid == DigitalCarKeyConstants.digitalKeyServiceUuid.uuid
                        || DigitalCarKeyConstants.knownServiceUuids.any {
                            it.equals(pu.uuid.toString(), ignoreCase = true)
                        }
                } == true
                if (matchesConfigured || serviceUuids == null) {
                    LogHelper.d(TAG, "Auto-connecting to DK candidate: " + device.address)
                    cancelScanTimeout()
                    connectDevice(device)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            LogHelper.w(TAG, "Scan failed code=" + errorCode)
            if (running) {
                emitError(DigitalCarKeyError.SCAN_FAILED, "Scan error " + errorCode)
                mainHandler.postDelayed({ restartScanIfNeeded() }, 1500L)
            }
        }
    }

    // ============ GATT Callback ============

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!running && newState != BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LogHelper.d(TAG, "GATT connected: " + gatt.device.address)
                    cancelConnectTimeout()
                    connectedAddress.set(gatt.device.address)
                    emitState(DigitalCarKeyState.DISCOVERING_SERVICES)
                    scheduleServiceDiscoveryTimeout()
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(DigitalCarKeyConstants.REQUESTED_MTU)
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "Connection tuning failed: " + e.message)
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val addr = gatt.device.address
                    LogHelper.d(TAG, "GATT disconnected: " + addr + " status=" + status)
                    if (connectedAddress.get() == addr) connectedAddress.set(null)
                    gatt.close()
                    if (this@DigitalCarKeyService.gatt == gatt) {
                        this@DigitalCarKeyService.gatt = null
                    }
                    controlCharacteristic = null
                    statusCharacteristic = null
                    authCharacteristic = null
                    activeServiceUuid = null
                    matchedProfile = null
                    notifyCharacteristics.clear()
                    cancelAuthTimeout()
                    cancelServiceDiscoveryTimeout()
                    isProcessingQueue = false
                    pendingCommand.set(null)
                    if (running) attemptReconnect()
                    else emitState(DigitalCarKeyState.DISCONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            cancelServiceDiscoveryTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LogHelper.w(TAG, "Service discovery failed: status=" + status)
                logAllServices(gatt)
                emitError(DigitalCarKeyError.SERVICE_DISCOVERY_FAILED, "Discovery failed")
                disconnectGatt()
                attemptReconnect()
                return
            }
            if (!running) return
            handleServicesDiscovered(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            LogHelper.d(TAG, "MTU changed: " + mtu + " status=" + status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) { handleCharacteristicChanged(gatt, characteristic, characteristic.value) }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) { handleCharacteristicChanged(gatt, characteristic, value) }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val cmd = pendingCommand.get()
            if (cmd != null && characteristic.uuid == cmd.characteristicUuid) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                if (ok) LogHelper.d(TAG, "Cmd " + cmd.id + " write success")
                else LogHelper.w(TAG, "Cmd " + cmd.id + " write fail status=" + status)
                completeCommand(cmd, ok, null)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value
                reportProbe(
                    characteristic = characteristic,
                    value = value,
                    source = "read",
                )
                onCommandResult?.invoke(
                    BleCommand(id = -1, data = ByteArray(0), characteristicUuid = characteristic.uuid),
                    true, value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) { LogHelper.d(TAG, "Descriptor write " + descriptor.uuid + " status=" + status) }
    }

    // ============ Service Discovery Helpers ============

    private fun handleServicesDiscovered(gatt: BluetoothGatt) {
        logAllServices(gatt)
        val profile = DigitalCarKeyConstants.matchProfile(gatt.services)
        val snapshot = DigitalCarKeyProbe.buildSnapshot(gatt.services, profile)
        onGattProfileDiscovered?.invoke(snapshot)

        if (profile != null) {
            LogHelper.d(TAG, "Matched profile: " + profile.displayName)
            val service = gatt.getService(UUID.fromString(profile.serviceUuid))
            if (service != null) {
                applyProfile(profile, service, gatt)
                return
            }
        }

        for (knownUuidStr in DigitalCarKeyConstants.knownServiceUuids) {
            val svc = gatt.getService(UUID.fromString(knownUuidStr))
            if (svc != null) {
                LogHelper.d(TAG, "Found known service UUID: " + knownUuidStr)
                DigitalCarKeyConstants.digitalKeyServiceUuid = ParcelUuid.fromString(knownUuidStr)
                activeServiceUuid = svc.uuid
                discoverCharacteristics(svc, gatt)
                return
            }
        }

        val heuristic = DigitalCarKeyConstants.findHeuristicDkService(gatt.services)
        if (heuristic != null) {
            LogHelper.w(TAG, "Heuristic DK service: " + heuristic.uuid)
            activeServiceUuid = heuristic.uuid
            DigitalCarKeyConstants.digitalKeyServiceUuid = ParcelUuid(heuristic.uuid)
            discoverCharacteristics(heuristic, gatt)
            return
        }

        emitError(DigitalCarKeyError.SERVICE_NOT_FOUND, "DK service not found")
        disconnectGatt()
        attemptReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun applyProfile(
        profile: DigitalCarKeyConstants.DkBleProfile,
        service: android.bluetooth.BluetoothGattService,
        gatt: BluetoothGatt,
    ) {
        matchedProfile = profile
        activeServiceUuid = service.uuid
        DigitalCarKeyConstants.applyProfile(profile)

        controlCharacteristic = profile.controlCharacteristicUuid?.let {
            service.getCharacteristic(UUID.fromString(it))
        } ?: DigitalCarKeyConstants.autoDetectWriteCharacteristic(service)

        authCharacteristic = profile.authCharacteristicUuid?.let {
            service.getCharacteristic(UUID.fromString(it))
        }

        statusCharacteristic = profile.statusCharacteristicUuid?.let {
            service.getCharacteristic(UUID.fromString(it))
        } ?: DigitalCarKeyConstants.autoDetectNotifyCharacteristic(service)

        notifyCharacteristics.clear()
        for (uuidStr in profile.allNotifyUuids()) {
            service.getCharacteristic(UUID.fromString(uuidStr))?.let { chr ->
                notifyCharacteristics.add(chr)
                enableNotification(gatt, chr)
            }
        }

        onProfileMatched?.invoke(profile)
        finalizeConnectionSetup(gatt, service)
    }

    private fun logAllServices(gatt: BluetoothGatt) {
        try {
            LogHelper.d(TAG, "=== All Services (post-disconnect) ===")
            for (svc in gatt.services) {
                LogHelper.d(TAG, "  Service: " + svc.uuid)
                for (chr in svc.characteristics) {
                    LogHelper.d(TAG, "    Char: " + chr.uuid + " props=" + chr.properties)
                }
            }
            LogHelper.d(TAG, "=== End ===")
        } catch (_: Exception) { }
    }

    private fun discoverCharacteristics(
        service: android.bluetooth.BluetoothGattService,
        gatt: BluetoothGatt,
    ) {
        controlCharacteristic = service.getCharacteristic(
            DigitalCarKeyConstants.controlCharacteristicUuid.uuid)
        statusCharacteristic = service.getCharacteristic(
            DigitalCarKeyConstants.statusNotificationUuid.uuid)
        authCharacteristic = service.getCharacteristic(
            DigitalCarKeyConstants.authCharacteristicUuid.uuid)

        if (controlCharacteristic == null) {
            controlCharacteristic = DigitalCarKeyConstants.autoDetectWriteCharacteristic(service)
            controlCharacteristic?.let {
                LogHelper.d(TAG, "Auto-detected control char: " + it.uuid)
                DigitalCarKeyConstants.controlCharacteristicUuid = ParcelUuid(it.uuid)
            }
        }

        if (statusCharacteristic == null) {
            statusCharacteristic = DigitalCarKeyConstants.autoDetectNotifyCharacteristic(service)
            statusCharacteristic?.let {
                LogHelper.d(TAG, "Auto-detected notify char: " + it.uuid)
                DigitalCarKeyConstants.statusNotificationUuid = ParcelUuid(it.uuid)
            }
        }

        finalizeConnectionSetup(gatt, service)
    }

    @SuppressLint("MissingPermission")
    private fun finalizeConnectionSetup(
        gatt: BluetoothGatt,
        service: android.bluetooth.BluetoothGattService,
    ) {
        if (controlCharacteristic == null) {
            LogHelper.w(TAG, "Control char not found")
            emitError(DigitalCarKeyError.CONTROL_CHAR_NOT_FOUND, "No control char")
            return
        }

        if (notifyCharacteristics.isEmpty() && statusCharacteristic != null) {
            notifyCharacteristics.add(statusCharacteristic!!)
            enableNotification(gatt, statusCharacteristic!!)
        }

        probeReadableCharacteristics(gatt)

        when (matchedProfile?.id) {
            DigitalCarKeyConstants.GEELY_ZEEKR_PROFILE.id -> performZeekrAuthentication(gatt)
            else -> {
                if (authCharacteristic != null) {
                    emitState(DigitalCarKeyState.AUTHENTICATING, "Authenticating...")
                    performGenericAuth(gatt, authCharacteristic!!)
                } else {
                    markReady("Ready (probe mode)")
                }
            }
        }
        cancelNoSignalCheck()
        scheduleNoSignalCheck()
    }

    // ============ Notification & Authentication ============

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(
                DigitalCarKeyConstants.clientConfigDescriptor.uuid)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
                LogHelper.d(TAG, "Notification enabled for " + characteristic.uuid)
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "Enable notification failed: " + e.message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun performZeekrAuthentication(gatt: BluetoothGatt) {
        emitState(DigitalCarKeyState.AUTHENTICATING, "极氪鉴权...")
        scheduleAuthTimeout()

        val authChar = authCharacteristic
        if (authChar == null) {
            cancelAuthTimeout()
            markReady("Ready (no auth char)")
            return
        }

        if (!GeelyZeekrAuthHelper.hasCredentials()) {
            LogHelper.w(TAG, "Zeekr auth skipped: no bid/aes_key configured")
            cancelAuthTimeout()
            markReady("探测模式（未配置 bid）")
            return
        }

        val bidPayload = GeelyZeekrAuthHelper.buildBidPayload()
        if (bidPayload == null) {
            cancelAuthTimeout()
            markReady("探测模式（bid 无效）")
            return
        }

        authChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        authChar.value = bidPayload
        val ok = try { gatt.writeCharacteristic(authChar) } catch (e: Exception) {
            LogHelper.e(TAG, "Zeekr bid write failed: " + e.message)
            false
        }
        if (!ok) {
            cancelAuthTimeout()
            emitError(DigitalCarKeyError.AUTH_FAILED, "Bid write failed")
        } else {
            LogHelper.d(TAG, "Zeekr bid sent, waiting challenge...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun performGenericAuth(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        scheduleAuthTimeout()
        val readable = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
        if (readable) {
            try { gatt.readCharacteristic(characteristic) }
            catch (e: Exception) { LogHelper.w(TAG, "Auth read failed: " + e.message) }
        } else {
            cancelAuthTimeout()
            markReady("Auth skipped (not readable)")
        }
    }

    private fun markReady(message: String) {
        resetReconnectState()
        emitState(DigitalCarKeyState.READY, message)
    }

    @SuppressLint("MissingPermission")
    private fun probeReadableCharacteristics(gatt: BluetoothGatt) {
        for (svc in gatt.services) {
            for (chr in svc.characteristics) {
                val props = chr.properties
                if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    try {
                        gatt.readCharacteristic(chr)
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "Probe read " + chr.uuid + ": " + e.message)
                    }
                }
            }
        }
    }

    private fun reportProbe(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        source: String,
    ) {
        val entry = GattProbeEntry(
            serviceUuid = characteristic.service.uuid,
            characteristicUuid = characteristic.uuid,
            properties = characteristic.properties,
            value = value,
            source = source,
        )
        LogHelper.d(
            TAG,
            "[PROBE/$source] " + characteristic.uuid
                + " props=" + DigitalCarKeyProbe.describeProperties(characteristic.properties)
                + " hex=" + DigitalCarKeyProbe.bytesToHex(value),
        )
        onProbeResult?.invoke(entry)
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        lastDataMs = System.currentTimeMillis()
        scheduleNoSignalCheck()
        val uuid = characteristic.uuid
        LogHelper.d(TAG, "Notification: " + uuid + " size=" + value.size
            + " hex=" + bytesToHex(value))
        reportProbe(characteristic, value, "notify")

        if (_state.value.state == DigitalCarKeyState.AUTHENTICATING) {
            when {
                GeelyZeekrAuthHelper.isAuthSuccessNotification(value) -> {
                    cancelAuthTimeout()
                    markReady("鉴权成功")
                }
                GeelyZeekrAuthHelper.isAuthFailureNotification(value) -> {
                    cancelAuthTimeout()
                    emitError(DigitalCarKeyError.AUTH_FAILED, "鉴权失败")
                }
                matchedProfile?.id == DigitalCarKeyConstants.GEELY_ZEEKR_PROFILE.id
                    && GeelyZeekrAuthHelper.hasCredentials() -> {
                    val acq = GeelyZeekrAuthHelper.buildAcqChallengePlain(value)
                    if (acq != null && controlCharacteristic != null) {
                        LogHelper.d(TAG, "Zeekr ACQ challenge built, sending...")
                        sendCommand(
                            data = acq,
                            characteristicUuid = controlCharacteristic!!.uuid,
                            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        )
                    }
                }
            }
        }

        val cmd = pendingCommand.get()
        if (cmd != null && uuid == cmd.characteristicUuid) {
            completeCommand(cmd, true, value)
        }
        onCommandResult?.invoke(
            BleCommand(id = -2, data = ByteArray(0), characteristicUuid = uuid),
            true, value)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02X", it) }
    }

    private companion object {
        private const val TAG = "DigitalCarKeyService"
    }
}

