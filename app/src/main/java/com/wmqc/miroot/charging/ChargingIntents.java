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

    private ChargingIntents() {
    }
}
