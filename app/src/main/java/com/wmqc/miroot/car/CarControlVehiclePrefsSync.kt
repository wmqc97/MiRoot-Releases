package com.wmqc.miroot.car

import android.content.Context
import android.content.SharedPreferences

/** 车控 SharedPreferences 车辆状态位，供背屏、设置页与桌面小组件共用。 */
object CarControlVehiclePrefsSync {

    const val KEY_IS_LOCKED = "is_locked"
    const val KEY_IS_ENGINE_ON = "is_engine_on"
    const val KEY_IS_WINDOW_OPEN = "is_window_open"
    const val KEY_IS_TRUNK_OPEN = "is_trunk_open"
    const val KEY_IS_VENT_MODE = "is_vent_mode"

    fun carPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)

    /** 从云端车辆状态写回本地位并持久化展示缓存/历史（历史按车辆更新时间去重）。 */
    fun refreshFromVehicleStatus(context: Context) {
        val appCtx = context.applicationContext
        try {
            val status = VehicleStatusService.getVehicleStatus(appCtx)
            CarVehicleDisplayCache.save(appCtx, status)
        } catch (_: Exception) { }
    }

    fun applyFromStatus(context: Context, status: VehicleStatusService.VehicleStatusInfo?) {
        if (status == null) return
        carPrefs(context.applicationContext).edit()
            .putBoolean(KEY_IS_LOCKED, isLocked(status))
            .putBoolean(KEY_IS_ENGINE_ON, isEngineOn(status))
            .putBoolean(KEY_IS_WINDOW_OPEN, isWindowOpen(status))
            .putBoolean(KEY_IS_TRUNK_OPEN, isTrunkOpen(status))
            .putBoolean(KEY_IS_VENT_MODE, isVentMode(status))
            .apply()
    }

    fun isLocked(status: VehicleStatusService.VehicleStatusInfo?): Boolean =
        status != null && "已锁" == VehicleStatusService.translateDoorLockStatus(status.doorLockStatusDriver)

    fun isEngineOn(status: VehicleStatusService.VehicleStatusInfo?): Boolean =
        status != null && "运行中" == VehicleStatusService.translateEngineStatus(status.engineStatus)

    fun isWindowOpen(status: VehicleStatusService.VehicleStatusInfo?): Boolean =
        status != null && "已开" == status.winStatusDriver

    fun isTrunkOpen(status: VehicleStatusService.VehicleStatusInfo?): Boolean =
        status != null && "已开" == VehicleStatusService.translateTrunkStatus(status.trunkOpenStatus)

    fun isVentMode(status: VehicleStatusService.VehicleStatusInfo?): Boolean =
        isVentPosition(status?.winPosDriver)

    /** 指令成功后乐观翻转，便于小组件/背屏立即更新颜色。 */
    fun applyOptimisticAfterSuccess(
        prefs: SharedPreferences,
        functionKey: String,
        remoteOnBeforeCommand: Boolean,
    ) {
        val editor = prefs.edit()
        when (functionKey) {
            "锁车/解锁" -> editor.putBoolean(KEY_IS_LOCKED, !remoteOnBeforeCommand)
            "点火/熄火" -> editor.putBoolean(KEY_IS_ENGINE_ON, !remoteOnBeforeCommand)
            "开窗/关窗" -> editor.putBoolean(KEY_IS_WINDOW_OPEN, !remoteOnBeforeCommand)
            "开后备箱" -> editor.putBoolean(KEY_IS_TRUNK_OPEN, true)
            "透气" -> editor.putBoolean(KEY_IS_VENT_MODE, true)
            "空调" -> editor.putBoolean("ac_status", !prefs.getBoolean("ac_status", false))
            "座椅加热", "主驾加热", "副驾加热" ->
                editor.putBoolean("seat_heating_status", !prefs.getBoolean("seat_heating_status", false))
        }
        editor.apply()
    }

    private fun isVentPosition(winPosDriver: String?): Boolean {
        if (winPosDriver.isNullOrEmpty() || winPosDriver == "未知") return false
        return try {
            val pos = winPosDriver.toInt()
            pos > 0 && pos <= 50
        } catch (_: NumberFormatException) {
            false
        }
    }
}
