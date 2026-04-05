package com.wmqc.miroot.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wmqc.miroot.lyrics.LogHelper

/**
 * 车控外部广播入口：投屏仅 `com.wmqc.miroot.car.ACTION_*_CAR_CONTROL_PROJECTION`；指令与查询支持 tgwgroup / MiRoot 两套 action。
 */
class CarControlBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
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
        val serviceIntent = Intent(context, CarControlProjectionService::class.java).apply {
            setAction(CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION)
            intent.getStringExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP)?.let { op ->
                putExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP, op)
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
