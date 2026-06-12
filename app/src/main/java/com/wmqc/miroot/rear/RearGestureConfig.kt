package com.wmqc.miroot.rear

/**
 * 单个背屏底部手势可配置动作。
 * [REAR_DESKTOP]、[MUSIC_LYRICS]、[CAR_CONTROL]、[FOREGROUND_APP_TO_REAR]、[CHARGING_PREVIEW]、[BALANCE_GAME]、[TRUTH_DARE_WHEEL]、[HEART_RATE] 为**互斥动作**：每种动作最多被一个槽位使用；
 * 其它槽的下拉中不再出现已被占用的该动作，避免两路手势唤起同一系统能力。由 [RearGesturePrefs] 与配置页共同约束。
 */
enum class RearGestureAction {
    NONE,
    REAR_DESKTOP,
    MUSIC_LYRICS,
    CAR_CONTROL,
    LAUNCH_APP,
    /** 同磁贴「切换至背屏」：前台应用迁背屏，Keeper 收口。 */
    FOREGROUND_APP_TO_REAR,
    /** 功能页预览同款：背屏充电动画（涨水/常亮等读当前设置）。 */
    CHARGING_PREVIEW,
    /** 背屏平衡球小游戏：倾斜手机控制小球留在竞技区内。 */
    BALANCE_GAME,
    /** 背屏真心话大冒险转盘。 */
    TRUTH_DARE_WHEEL,
    /** 背屏蓝牙心率广播：接收手环/手表标准心率广播并显示。 */
    HEART_RATE,
    ;

    val isExclusive: Boolean
        get() =
            this == REAR_DESKTOP ||
                this == MUSIC_LYRICS ||
                this == CAR_CONTROL ||
                this == FOREGROUND_APP_TO_REAR ||
                this == CHARGING_PREVIEW ||
                this == BALANCE_GAME ||
                this == TRUTH_DARE_WHEEL ||
                this == HEART_RATE
}

/**
 * 注入主题与运行时读取共用：三槽位对应 上滑 / 左滑 / 右滑。
 */
data class RearGestureInjectSpec(
    val slot1Up: RearGestureAction,
    val slot1LaunchPackage: String,
    val slot2Left: RearGestureAction,
    val slot2LaunchPackage: String,
    val slot3Right: RearGestureAction,
    val slot3LaunchPackage: String,
) {
    fun slotAction(slot: Int): RearGestureAction =
        when (slot) {
            1 -> slot1Up
            2 -> slot2Left
            3 -> slot3Right
            else -> RearGestureAction.NONE
        }

    fun launchPackage(slot: Int): String =
        when (slot) {
            1 -> slot1LaunchPackage
            2 -> slot2LaunchPackage
            3 -> slot3LaunchPackage
            else -> ""
        }.trim()

    companion object {
        /** 默认：上滑音乐、左滑充电动画、右滑背屏桌面。 */
        fun defaultCompat(): RearGestureInjectSpec =
            RearGestureInjectSpec(
                slot1Up = RearGestureAction.MUSIC_LYRICS,
                slot1LaunchPackage = "",
                slot2Left = RearGestureAction.CHARGING_PREVIEW,
                slot2LaunchPackage = "",
                slot3Right = RearGestureAction.REAR_DESKTOP,
                slot3LaunchPackage = "",
            )
    }
}
