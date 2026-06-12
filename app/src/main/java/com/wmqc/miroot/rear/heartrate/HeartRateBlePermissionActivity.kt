package com.wmqc.miroot.rear.heartrate

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.wmqc.miroot.R
import com.wmqc.miroot.display.MainDisplayUi

/**
 * 主屏透明页：请求蓝牙权限与开启蓝牙，完成后再启动背屏心率界面。
 * 避免在背屏 Activity 上弹出系统授权框。
 */
class HeartRateBlePermissionActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (BlePermissionHelper.hasAll(this)) {
                requestEnableBluetoothIfNeeded()
            } else {
                MainDisplayUi.showToast(
                    this,
                    R.string.rear_heart_rate_permission_denied,
                    Toast.LENGTH_LONG,
                )
                finish()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (HeartRateBleGate.isReady(this)) {
                launchRearAndFinish()
            } else {
                MainDisplayUi.showToast(
                    this,
                    R.string.rear_heart_rate_bluetooth_off,
                    Toast.LENGTH_LONG,
                )
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            },
        )

        if (HeartRateBleGate.isReady(this)) {
            launchRearAndFinish()
            return
        }
        if (!BlePermissionHelper.hasAll(this)) {
            permissionLauncher.launch(BlePermissionHelper.requiredPermissions())
            return
        }
        requestEnableBluetoothIfNeeded()
    }

    private fun requestEnableBluetoothIfNeeded() {
        if (BleHeartRateMonitor(this).isBluetoothEnabled()) {
            launchRearAndFinish()
            return
        }
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            MainDisplayUi.showToast(
                this,
                R.string.rear_heart_rate_bluetooth_off,
                Toast.LENGTH_LONG,
            )
            finish()
        }
    }

    private fun launchRearAndFinish() {
        RearHeartRateLaunchHelper.launchHeartRateAfterBleReady(applicationContext)
        finish()
    }
}
