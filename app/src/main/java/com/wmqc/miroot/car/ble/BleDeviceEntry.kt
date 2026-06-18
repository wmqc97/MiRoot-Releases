package com.wmqc.miroot.car.ble

import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid

data class BleDeviceEntry(
    val device: BluetoothDevice,
    val rssi: Int,
    val serviceUuids: List<ParcelUuid>?,
    val timestampMs: Long = System.currentTimeMillis(),
)
