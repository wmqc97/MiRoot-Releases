package com.wmqc.miroot.rear.heartrate

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid

/** 解析标准蓝牙心率服务（0x180D）测量数据。 */
object HeartRateMeasurementParser {

    val HEART_RATE_SERVICE: ParcelUuid =
        ParcelUuid.fromString("0000180D-0000-1000-8000-00805f9b34fb")

    val HEART_RATE_MEASUREMENT: ParcelUuid =
        ParcelUuid.fromString("00002A37-0000-1000-8000-00805f9b34fb")

    val CLIENT_CONFIG: ParcelUuid =
        ParcelUuid.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun parse(data: ByteArray?): Int? {
        if (data == null || data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val formatUint16 = flags and 0x01 != 0
        val bpm =
            if (formatUint16) {
                if (data.size < 3) return null
                (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            } else {
                if (data.size < 2) return null
                data[1].toInt() and 0xFF
            }
        return bpm.takeIf { it in 30..250 }
    }

    fun parseFromScanRecord(record: ScanRecord?): Int? {
        if (record == null) return null
        val serviceData = record.getServiceData(HEART_RATE_SERVICE) ?: return null
        return parse(serviceData)
    }
}
