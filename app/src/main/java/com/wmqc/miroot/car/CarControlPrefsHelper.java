package com.wmqc.miroot.car;

/**
 * 车控相关 SharedPreferences（如 {@link CarControlSettingsActivity} 中吉利数字钥匙等）。
 * 是否屏蔽小米背屏官方服务仅由功能页 {@link com.wmqc.miroot.rear.OfficialSubscreenServiceGate} 控制，不再使用车控独立项。
 */
public final class CarControlPrefsHelper {
    private CarControlPrefsHelper() {}

    public static final String PREFS_NAME = "CarControlPrefs";
}
