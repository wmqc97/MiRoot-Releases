package com.wmqc.miroot.rear

/**
 * 单个背屏底部手势可配置动作。
 * [REAR_DESKTOP]、[MUSIC_LYRICS]、[CAR_CONTROL]、[FOREGROUND_APP_TO_REAR] 为**互斥动作**：每种动作最多被一个槽位使用；
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
    ;

    val isExclusive: Boolean
        get() =
            this == REAR_DESKTOP ||
                this == MUSIC_LYRICS ||
                this == CAR_CONTROL ||
                this == FOREGROUND_APP_TO_REAR
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
        /** 与旧版「仅上滑音乐」接近的单一独占默认。 */
        fun defaultCompat(): RearGestureInjectSpec =
            RearGestureInjectSpec(
                slot1Up = RearGestureAction.MUSIC_LYRICS,
                slot1LaunchPackage = "",
                slot2Left = RearGestureAction.NONE,
                slot2LaunchPackage = "",
                slot3Right = RearGestureAction.NONE,
                slot3LaunchPackage = "",
            )
    }
}
