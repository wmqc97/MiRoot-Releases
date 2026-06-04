package com.wmqc.miroot.car

import android.content.SharedPreferences

/**
 * 小组件按钮文案/高亮与 [CarButtonInfo]、[CarButtonStateManager] 一致：
 * remoteOn=true 表示已锁、已开窗、已点火等「活跃态」，显示蓝色。
 */
object CarControlWidgetState {

    fun remoteOn(
        functionKey: String,
        prefs: SharedPreferences,
        vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
    ): Boolean = when (functionKey) {
        "锁车/解锁" -> prefs.getBoolean(
            CarControlVehiclePrefsSync.KEY_IS_LOCKED,
            CarControlVehiclePrefsSync.isLocked(vehicleStatus),
        )
        "点火/熄火" -> prefs.getBoolean(
            CarControlVehiclePrefsSync.KEY_IS_ENGINE_ON,
            CarControlVehiclePrefsSync.isEngineOn(vehicleStatus),
        )
        "开窗/关窗" -> prefs.getBoolean(
            CarControlVehiclePrefsSync.KEY_IS_WINDOW_OPEN,
            CarControlVehiclePrefsSync.isWindowOpen(vehicleStatus),
        )
        "开后备箱" -> prefs.getBoolean(
            CarControlVehiclePrefsSync.KEY_IS_TRUNK_OPEN,
            CarControlVehiclePrefsSync.isTrunkOpen(vehicleStatus),
        )
        "透气" -> prefs.getBoolean(
            CarControlVehiclePrefsSync.KEY_IS_VENT_MODE,
            CarControlVehiclePrefsSync.isVentMode(vehicleStatus),
        )
        "空调" -> prefs.getBoolean("ac_status", false)
        "座椅加热", "主驾加热", "副驾加热" -> prefs.getBoolean("seat_heating_status", false)
        else -> false
    }

    fun displayText(functionKey: String, remoteOn: Boolean): String = when (functionKey) {
        "锁车/解锁" -> if (remoteOn) "解锁" else "锁车"
        "点火/熄火" -> if (remoteOn) "熄火" else "点火"
        "开窗/关窗" -> if (remoteOn) "关窗" else "开窗"
        "空调" -> if (remoteOn) "关闭空调" else "打开空调"
        "透气" -> "透气"
        "开后备箱" -> if (remoteOn) "关后备箱" else "开后备箱"
        "座椅加热" -> if (remoteOn) "关闭座椅加热" else "打开座椅加热"
        "主驾加热" -> if (remoteOn) "关闭主驾加热" else "主驾加热"
        "副驾加热" -> if (remoteOn) "关闭副驾加热" else "副驾加热"
        "寻车" -> "寻车"
        else -> functionKey
    }

    /**
     * 图标/文案高亮：与背屏一致——文案为「锁车/关窗/熄火…」时表示车辆处于开启/未锁等活跃态（蓝色）。
     * 锁车按钮的 remoteOn 表示「已锁」，与窗/空调等语义相反，需单独取反。
     */
    fun isButtonActive(functionKey: String, remoteOn: Boolean): Boolean = when (functionKey) {
        "寻车" -> false
        "锁车/解锁" -> !remoteOn
        "透气", "点火/熄火", "开窗/关窗", "开后备箱",
        "空调", "座椅加热", "主驾加热", "副驾加热" -> remoteOn
        else -> false
    }
}
