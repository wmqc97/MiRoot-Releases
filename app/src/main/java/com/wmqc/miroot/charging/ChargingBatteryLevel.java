package com.wmqc.miroot.charging;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * 充电动画统一电量百分比：优先 {@link BatteryManager#BATTERY_PROPERTY_CAPACITY}，
 * 避免部分 MIUI/HyperOS 上 sticky {@link Intent#ACTION_BATTERY_CHANGED} 的 scale 异常
 *（例如 level=66、scale=550 → 误算为 12%）。
 */
public final class ChargingBatteryLevel {

    private ChargingBatteryLevel() {
    }

    public static int getPercent(Context context) {
        if (context == null) {
            return 0;
        }
        Context app = context.getApplicationContext();

        BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (isValidPercent(capacity)) {
                return capacity;
            }
        }

        Intent sticky = app.registerReceiver(null,
            new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky == null) {
            return 0;
        }

        int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0) {
            return 0;
        }
        if (scale <= 0) {
            scale = 100;
        }

        // 部分机型 EXTRA_LEVEL 已是 0–100，但 EXTRA_SCALE 并非 100（除法会得到错误百分比）
        if (level <= 100 && scale != 100 && isValidPercent(level)) {
            return level;
        }

        int pct = Math.round(level * 100f / scale);
        if (isValidPercent(pct)) {
            return pct;
        }
        if (isValidPercent(level)) {
            return level;
        }
        return 0;
    }

    static boolean isValidPercent(int value) {
        return value >= 0 && value <= 100;
    }

    /** 背屏充电动画大字号电量：&lt;10% 红，&lt;20% 橙，其余白。 */
    public static int largePercentTextColorArgb(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p < 10) {
            return 0xFFFF4444;
        }
        if (p < 20) {
            return 0xFFFF9800;
        }
        return 0xFFFFFFFF;
    }
}
