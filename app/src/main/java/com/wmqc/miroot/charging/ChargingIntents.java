package com.wmqc.miroot.charging;

/**
 * 充电动画相关显式广播 Action（均 {@code setPackage} 包内投递）。
 */
public final class ChargingIntents {

    public static final String ACTION_FINISH_CHARGING_ANIMATION =
        "com.wmqc.miroot.charging.ACTION_FINISH_CHARGING_ANIMATION";

    /** 功能页修改充电动画相关开关后由应用内发送，{@link ChargingService} 重新读取 SharedPreferences */
    public static final String ACTION_RELOAD_CHARGING_SETTINGS =
        "com.wmqc.miroot.charging.RELOAD_CHARGING_SETTINGS";

    /** 通知等场景结束后恢复充电动画。 */
    public static final String ACTION_RESUME_CHARGING_ANIMATION =
        "com.wmqc.miroot.charging.RESUME_CHARGING_ANIMATION";

    public static final String ACTION_INTERRUPT_CHARGING_ANIMATION =
        "com.wmqc.miroot.charging.INTERRUPT_CHARGING_ANIMATION";

    public static final String ACTION_INTERRUPT_NOTIFICATION_ANIMATION =
        "com.wmqc.miroot.charging.INTERRUPT_NOTIFICATION_ANIMATION";

    public static final String ACTION_UPDATE_CHARGING_BATTERY =
        "com.wmqc.miroot.charging.UPDATE_CHARGING_BATTERY";

    /**
     * 充电动画 task 已迁移到背屏：用于解决某些机型/时序下 {@code getDisplay()} / onConfigurationChanged 更新滞后，
     * 导致背屏短暂黑屏（Activity 仍处在主屏透明占位阶段、未 inflate UI）。
     */
    public static final String ACTION_NOTIFY_CHARGING_TASK_MOVED_TO_REAR =
        "com.wmqc.miroot.charging.NOTIFY_CHARGING_TASK_MOVED_TO_REAR";

    private ChargingIntents() {
    }
}
