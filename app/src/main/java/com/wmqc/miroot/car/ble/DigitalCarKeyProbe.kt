package com.wmqc.miroot.car.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

data class GattCharacteristicInfo(
    val uuid: UUID,
    val properties: Int,
    val propertiesLabel: String,
)

data class GattServiceInfo(
    val uuid: UUID,
    val characteristics: List<GattCharacteristicInfo>,
)

data class GattProfileSnapshot(
    val services: List<GattServiceInfo>,
    val matchedProfile: DigitalCarKeyConstants.DkBleProfile?,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class GattProbeEntry(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val properties: Int,
    val value: ByteArray?,
    val source: String,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GattProbeEntry) return false
        return serviceUuid == other.serviceUuid
            && characteristicUuid == other.characteristicUuid
            && properties == other.properties
            && value.contentEquals(other.value)
            && source == other.source
    }

    override fun hashCode(): Int {
        var result = serviceUuid.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + properties
        result = 31 * result + (value?.contentHashCode() ?: 0)
        result = 31 * result + source.hashCode()
        return result
    }
}

object DigitalCarKeyProbe {

    fun buildSnapshot(
        services: List<BluetoothGattService>,
        matchedProfile: DigitalCarKeyConstants.DkBleProfile?,
    ): GattProfileSnapshot {
        val serviceInfos = services.map { svc ->
            GattServiceInfo(
                uuid = svc.uuid,
                characteristics = svc.characteristics.map { chr ->
                    GattCharacteristicInfo(
                        uuid = chr.uuid,
                        properties = chr.properties,
                        propertiesLabel = describeProperties(chr.properties),
                    )
                },
            )
        }
        return GattProfileSnapshot(serviceInfos, matchedProfile)
    }

    fun describeProperties(properties: Int): String {
        val labels = mutableListOf<String>()
        if ((properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) labels.add("R")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) labels.add("W")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) labels.add("Wn")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) labels.add("N")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) labels.add("I")
        return labels.joinToString("|").ifEmpty { "?" }
    }

    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        return bytes.joinToString("") { String.format("%02X", it) }
    }

    fun hexToBytes(hex: String): ByteArray? {
        val cleaned = hex.replace(" ", "").replace(":", "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/**
 * 极氪/吉利蓝牙钥匙鉴权辅助（需云端 bid + aes_key，当前仅提供帧构造骨架）。
 */
object GeelyZeekrAuthHelper {

  /** 云端下发的车辆 bid */
    var bid: String? = null

  /** AES 密钥（16/24/32 字节） */
    var aesKey: ByteArray? = null

    fun hasCredentials(): Boolean = !bid.isNullOrBlank() && aesKey != null

    fun buildBidPayload(): ByteArray? {
        val currentBid = bid ?: return null
        return currentBid.toByteArray(Charsets.UTF_8)
    }

    /**
     * 根据车端挑战数据构造 +ACQ: 鉴权帧（未加密版，供调试对比）。
     * 完整流程需 AES 加密，密钥来自 getBluetoothKey 接口。
     */
    fun buildAcqChallengePlain(arr: ByteArray): ByteArray? {
        if (arr.size < 13) return null
        val arr2 = byteArrayOf(
            arr[10],
            arr[8],
            arr[5],
            ((arr[6].toInt() and 0xFF) + (arr[7].toInt() and 0xFF)
                + (arr[9].toInt() and 0xFF) + (arr[12].toInt() and 0xFF)).toByte()
                .toInt().xor(arr[11].toInt() and 0xFF).toByte(),
        )
        val crc = crc16Modbus(arr2)
        val arr4 = byteArrayOf(
            (System.currentTimeMillis() and 0xFF).toByte(),
            ((System.currentTimeMillis() shr 8) and 0xFF).toByte(),
        )
        val arr5 = byteArrayOf(
            arr4[0],
            (arr[6].toInt() xor (crc[0].toInt() and 0xFF)).toByte(),
            crc[1],
            arr4[1],
        )
        return ("+ACQ:" + arr5.joinToString("") { String.format("%02X", it) })
            .toByteArray(Charsets.UTF_8)
    }

    fun isAuthSuccessNotification(value: ByteArray): Boolean {
        val text = value.toString(Charsets.UTF_8)
        return text.contains("#ACR=01") || text.contains("+ACR:01")
    }

    fun isAuthFailureNotification(value: ByteArray): Boolean {
        val text = value.toString(Charsets.UTF_8)
        return text.contains("ER01") || text.contains("+ACR:ER")
    }

    /** Modbus CRC16，极氪协议常用实现；若实车不匹配可再调整多项式 */
    fun crc16Modbus(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }
}
