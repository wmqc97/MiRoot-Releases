package com.wmqc.miroot.car

import android.content.Context
import android.widget.Toast
import com.wmqc.miroot.display.MainDisplayUi

/**
 * 小组件按钮执行车控指令；成功后乐观更新本地状态并多轮刷新小组件。
 */
object CarControlWidgetExecutor {

    private const val KEY_AC_DURATION = "ac_duration"
    private const val KEY_AC_TEMPERATURE = "ac_temperature"
    private const val KEY_SEAT_HEATING_DURATION = "seat_heating_duration"
    private const val KEY_SEAT_HEATING_LEVEL = "seat_heating_level"

    fun executeFunctionKey(context: Context, functionKey: String) {
        val appCtx = context.applicationContext
        val prefs = CarControlVehiclePrefsSync.carPrefs(appCtx)
        var remoteOnBefore = CarControlWidgetState.remoteOn(functionKey, prefs, null)
        try {
            val status = try {
                VehicleStatusService.getVehicleStatus(appCtx)
            } catch (_: Exception) {
                null
            }
            remoteOnBefore = CarControlWidgetState.remoteOn(functionKey, prefs, status)

            val result = when (functionKey) {
                "锁车/解锁" -> {
                    val locked = CarControlVehiclePrefsSync.isLocked(status)
                        || prefs.getBoolean(CarControlVehiclePrefsSync.KEY_IS_LOCKED, false)
                    if (locked) VehicleControlService.unlock(appCtx) else VehicleControlService.lock(appCtx)
                }
                "寻车" -> VehicleControlService.findCar(appCtx)
                "点火/熄火" -> {
                    val running = CarControlVehiclePrefsSync.isEngineOn(status)
                        || prefs.getBoolean(CarControlVehiclePrefsSync.KEY_IS_ENGINE_ON, false)
                    if (running) VehicleControlService.stopEngine(appCtx) else VehicleControlService.startEngine(appCtx, 10)
                }
                "空调" -> {
                    val isOn = prefs.getBoolean("ac_status", false)
                    if (isOn) VehicleControlService.closeAirConditioner(appCtx)
                    else {
                        val mins = prefs.getInt(KEY_AC_DURATION, 10)
                        val temp = prefs.getInt(KEY_AC_TEMPERATURE, 22)
                        VehicleControlService.openAirConditioner(appCtx, mins, temp)
                    }
                }
                "开窗/关窗" -> {
                    val open = CarControlVehiclePrefsSync.isWindowOpen(status)
                        || prefs.getBoolean(CarControlVehiclePrefsSync.KEY_IS_WINDOW_OPEN, false)
                    if (open) VehicleControlService.closeWindow(appCtx) else VehicleControlService.openWindow(appCtx)
                }
                "透气" -> VehicleControlService.ventilate(appCtx)
                "开后备箱" -> VehicleControlService.openTrunk(appCtx)
                "座椅加热" -> {
                    val isOn = prefs.getBoolean("seat_heating_status", false)
                    if (isOn) VehicleControlService.closeSeatHeating(appCtx)
                    else {
                        val mins = prefs.getInt(KEY_SEAT_HEATING_DURATION, 10)
                        val level = prefs.getInt(KEY_SEAT_HEATING_LEVEL, 1)
                        VehicleControlService.openSeatHeating(appCtx, mins, level)
                    }
                }
                "主驾加热" -> {
                    val isOn = prefs.getBoolean("seat_heating_status", false)
                    if (isOn) VehicleControlService.closeDriverSeatHeating(appCtx)
                    else {
                        val mins = prefs.getInt(KEY_SEAT_HEATING_DURATION, 10)
                        val level = prefs.getInt(KEY_SEAT_HEATING_LEVEL, 1)
                        VehicleControlService.openDriverSeatHeating(appCtx, mins, level)
                    }
                }
                "副驾加热" -> {
                    val isOn = prefs.getBoolean("seat_heating_status", false)
                    if (isOn) VehicleControlService.closePassengerSeatHeating(appCtx)
                    else {
                        val mins = prefs.getInt(KEY_SEAT_HEATING_DURATION, 10)
                        val level = prefs.getInt(KEY_SEAT_HEATING_LEVEL, 1)
                        VehicleControlService.openPassengerSeatHeating(appCtx, mins, level)
                    }
                }
                else -> null
            }
            if (result != null && result.success) {
                CarControlVehiclePrefsSync.applyOptimisticAfterSuccess(prefs, functionKey, remoteOnBefore)
            }
            if (result != null && !result.success) {
                MainDisplayUi.showToast(
                    context,
                    result.message.ifEmpty { "执行失败" },
                    Toast.LENGTH_SHORT,
                )
            }
        } catch (e: Exception) {
            MainDisplayUi.showToast(context, e.message ?: "执行失败", Toast.LENGTH_SHORT)
        }
        CarControlWidgetUpdater.scheduleRefreshAfterCommand(context)
    }
}
