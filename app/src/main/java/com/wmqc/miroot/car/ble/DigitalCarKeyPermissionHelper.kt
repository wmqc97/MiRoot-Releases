package com.wmqc.miroot.car.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 数字车钥匙 BLE 权限检查与蓝牙状态接口。
 *
 * 屏蔽 Android 12+ 访问附近设备权限时，同时兼容较旧版本的位置权限。
 */
object DigitalCarKeyPermissionHelper {

    /** 当前 API 级所需 BLE 权限数组 */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 是否已拥有所有必需 BLE 权限 */
    fun hasAll(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** 检查系统蓝牙是否开启 */
    fun isBluetoothEnabled(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }

    /** 检查当前设备是否支持 BLE 硬件 */
    fun isLeSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
}
