package com.wmqc.miroot.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wmqc.miroot.lyrics.LogHelper

/**
 * 车控外部广播入口：仅 [CarControlDeviceGate] 白名单设备会处理；投屏仅 `com.wmqc.miroot.car.ACTION_*_CAR_CONTROL_PROJECTION`；指令与查询支持 tgwgroup / MiRoot 两套 action。
 *
 * [CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION] 在未带 [CarControlIntents.EXTRA_CAR_PROJECTION_OP]（或为空）时：
 * 若车控背屏界面已在运行则停止投屏，否则启动；显式 `start`/`stop` 时仍按原语义转发。
 * [CarControlIntents.ACTION_STOP_CAR_CONTROL_PROJECTION] 行为不变。
 */
class CarControlBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (!CarControlDeviceGate.isAllowed(context)) {
            LogHelper.w(TAG, "设备未授权，忽略车控广播")
            return
        }
        val action = intent.action ?: return
        LogHelper.d(TAG, "📡 收到广播: $action")
        try {
            when (action) {
                CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION -> openCarControlProjection(context, intent)

                CarControlIntents.ACTION_STOP_CAR_CONTROL_PROJECTION -> stopCarControlProjection(context)

                CarControlIntents.ACTION_CAR_CONTROL_COMMAND,
                CarControlIntents.ACTION_CAR_CONTROL_COMMAND_MIROOT,
                -> {
                    VibrationHelper.vibrateOneShot(context, 45, "收到车控广播短震失败")
                    val serviceIntent = Intent(context, CarControlCommandService::class.java).apply {
                        setAction(CarControlIntents.ACTION_CAR_CONTROL_COMMAND)
                        intent.extras?.let { putExtras(it) }
                    }
                    context.startService(serviceIntent)
                }

                CarControlIntents.ACTION_QUERY_VEHICLE_STATUS,
                CarControlIntents.ACTION_QUERY_VEHICLE_STATUS_MIROOT,
                -> {
                    val serviceIntent = Intent(context, VehicleStatusQueryService::class.java).apply {
                        setAction(CarControlIntents.ACTION_QUERY_VEHICLE_STATUS)
                        intent.extras?.let { putExtras(it) }
                    }
                    context.startService(serviceIntent)
                }

                else -> LogHelper.w(TAG, "未处理 action: $action")
            }
        } catch (e: Exception) {
            LogHelper.e(TAG, "处理车控广播失败", e)
        }
    }

    private fun openCarControlProjection(context: Context, intent: Intent) {
        val rawOp = intent.getStringExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP)?.trim().orEmpty()
        if (rawOp.isEmpty() && ProjectionHelper.isCarControlProjectionUiActive()) {
            stopCarControlProjection(context)
            return
        }
        val serviceIntent = Intent(context, CarControlProjectionService::class.java).apply {
            setAction(CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION)
            if (rawOp.isNotEmpty()) {
                putExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP, rawOp)
            }
        }
        context.startService(serviceIntent)
    }

    private fun stopCarControlProjection(context: Context) {
        val serviceIntent = Intent(context, CarControlProjectionService::class.java).apply {
            setAction(CarControlIntents.ACTION_STOP_CAR_CONTROL_PROJECTION)
        }
        context.startService(serviceIntent)
    }

    private companion object {
        private const val TAG = "CarControlReceiver"
    }
}
