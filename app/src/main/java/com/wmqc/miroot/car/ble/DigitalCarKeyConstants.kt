package com.wmqc.miroot.car.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.os.ParcelUuid
import java.util.Locale
import java.util.UUID

/**
 * 吉利系数字蓝牙车钥匙 BLE UUID 与超时配置。
 *
 * 已知协议来源：
 * - 极氪/吉利蓝牙钥匙：service 6f410000-...，特征 6f410001(bid) / 6f410003(notify) / 6f410004(data)
 * - CCC Digital Key R3：service 0000FD81-...
 */
object DigitalCarKeyConstants {

    data class DkBleProfile(
        val id: String,
        val displayName: String,
        val serviceUuid: String,
        val authCharacteristicUuid: String? = null,
        val controlCharacteristicUuid: String? = null,
        val statusCharacteristicUuid: String? = null,
        val notifyCharacteristicUuids: List<String> = emptyList(),
    ) {
        fun allNotifyUuids(): List<String> {
            val list = mutableListOf<String>()
            statusCharacteristicUuid?.let { list.add(it) }
            list.addAll(notifyCharacteristicUuids)
            return list.distinct()
        }
    }

    val GEELY_ZEEKR_PROFILE = DkBleProfile(
        id = "geely_zeekr",
        displayName = "吉利/极氪蓝牙钥匙",
        serviceUuid = "6f410000-b5a3-f393-e0a9-e50e24dcca9e",
        authCharacteristicUuid = "6f410001-b5a3-f393-e0a9-e50e24dcca9e",
        statusCharacteristicUuid = "6f410003-b5a3-f393-e0a9-e50e24dcca9e",
        controlCharacteristicUuid = "6f410004-b5a3-f393-e0a9-e50e24dcca9e",
        notifyCharacteristicUuids = listOf("6f410003-b5a3-f393-e0a9-e50e24dcca9e"),
    )

    /** 当前生效的主服务 UUID（连接成功后可能被自动探测结果覆盖） */
    var digitalKeyServiceUuid: ParcelUuid =
        ParcelUuid.fromString(GEELY_ZEEKR_PROFILE.serviceUuid)

    var controlCharacteristicUuid: ParcelUuid =
        ParcelUuid.fromString(GEELY_ZEEKR_PROFILE.controlCharacteristicUuid!!)

    var statusNotificationUuid: ParcelUuid =
        ParcelUuid.fromString(GEELY_ZEEKR_PROFILE.statusCharacteristicUuid!!)

    var authCharacteristicUuid: ParcelUuid =
        ParcelUuid.fromString(GEELY_ZEEKR_PROFILE.authCharacteristicUuid!!)

    val CCC_DIGITAL_KEY_PROFILE = DkBleProfile(
        id = "ccc_fd81",
        displayName = "CCC Digital Key (FD81)",
        serviceUuid = "0000FD81-0000-1000-8000-00805F9B34FB",
    )

    val knownProfiles: List<DkBleProfile> = listOf(
        GEELY_ZEEKR_PROFILE,
        CCC_DIGITAL_KEY_PROFILE,
    )

    /** 扫描阶段用于高亮匹配的 Service UUID */
    val knownServiceUuids: List<String> = knownProfiles.map { it.serviceUuid } + listOf(
        "0000FD82-0000-1000-8000-00805F9B34FB",
    )

    val clientConfigDescriptor: ParcelUuid =
        ParcelUuid.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val DEFAULT_SCAN_MODE = android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
    const val SCAN_TIMEOUT_MS = 15_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val SERVICE_DISCOVERY_TIMEOUT_MS = 8_000L
    const val COMMAND_TIMEOUT_MS = 5_000L
    const val AUTH_TIMEOUT_MS = 15_000L
    const val NO_SIGNAL_TIMEOUT_MS = 30_000L
    const val RECONNECT_BASE_DELAY_MS = 2_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val COMMAND_QUEUE_POLL_INTERVAL_MS = 100L
    const val REQUESTED_MTU = 517

    fun applyProfile(profile: DkBleProfile) {
        digitalKeyServiceUuid = ParcelUuid.fromString(profile.serviceUuid)
        profile.controlCharacteristicUuid?.let {
            controlCharacteristicUuid = ParcelUuid.fromString(it)
        }
        profile.statusCharacteristicUuid?.let {
            statusNotificationUuid = ParcelUuid.fromString(it)
        }
        profile.authCharacteristicUuid?.let {
            authCharacteristicUuid = ParcelUuid.fromString(it)
        }
    }

    fun matchProfile(services: List<BluetoothGattService>): DkBleProfile? {
        val serviceIds = services.map { it.uuid.toString().uppercase(Locale.US) }
        return knownProfiles.firstOrNull { profile ->
            serviceIds.any { it.equals(profile.serviceUuid.uppercase(Locale.US), ignoreCase = true) }
        }
    }

    fun findHeuristicDkService(services: List<BluetoothGattService>): BluetoothGattService? {
        return services
            .filter { svc ->
                val chars = svc.characteristics
                val hasWrite = chars.any { hasWriteProperty(it) }
                val hasNotify = chars.any { hasNotifyProperty(it) }
                hasWrite && hasNotify
            }
            .maxByOrNull { it.characteristics.size }
    }

    fun autoDetectWriteCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        return service.characteristics.firstOrNull { hasWriteProperty(it) }
    }

    fun autoDetectNotifyCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        return service.characteristics.firstOrNull { hasNotifyProperty(it) }
    }

    fun autoDetectReadCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        return service.characteristics.firstOrNull {
            (it.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
        }
    }

    private fun hasWriteProperty(chr: BluetoothGattCharacteristic): Boolean {
        val props = chr.properties
        return (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            || (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private fun hasNotifyProperty(chr: BluetoothGattCharacteristic): Boolean {
        val props = chr.properties
        return (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            || (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }
}
