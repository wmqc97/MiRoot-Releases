/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Co-developed with AI assistants:
 * - Cursor
 */

package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import java.util.Locale;

/**
 * 吉利汽车 App 数字钥匙通知 → 车控解锁/锁车（靠近解锁）。
 * <p>
 * 仅在「未连接→已连接」时触发解锁；仅在「已连接→未连接」时触发锁车。
 * 与 {@link com.wmqc.miroot.service.MiRootNotificationListenerService} 共用通知监听权限。
 *
 * <p><b>实机格式（结论）</b>：FGS 常驻通知 {@link #GEELY_DK_FG_NOTIFICATION_ID}，key 形如
 * {@code 0|com.geely.consumer|5|null|uid}。中文状态在 RemoteViews；extras 常见英文摘要
 * {@code Geely dk running} 表示钥匙服务运行（已连接）。断开时系统常
 * {@code Cancel FGS} 撤掉该通知，故「未连接」以 {@link #handleNotificationRemoved} 为主；
 * 若将来 extras 出现中文「数字钥匙未连接」亦可从 posted 解析。
 */
public final class GeelyDigitalKeyProximityUnlock {

    private static final String TAG = "GeelyNearUnlock";

    public static final String PREFS_NAME = "CarControlPrefs";
    /** 功能总开关，与车控设置页 {@link SettingsActivity} 同步 */
    public static final String KEY_ENABLED = "near_unlock_geely_enabled";
    /** 上次解析出的数字钥匙状态：{@link #STATE_UNKNOWN} / {@link #STATE_CONNECTED} / {@link #STATE_DISCONNECTED} */
    public static final String KEY_LAST_STATE = "geely_digital_key_last_state";

    public static final String PACKAGE_GEELY = "com.geely.consumer";

    private static final String PHRASE_CONNECTED = "数字钥匙已连接";
    private static final String PHRASE_DISCONNECTED = "数字钥匙未连接";

    /**
     * 实机 log extras 仅含英文摘要（RemoteViews 里才是中文），例如：{@code Geely dk running}
     */
    private static final String PHRASE_CONNECTED_EN = "geely dk running";

    /**
     * 吉利 FGS 数字钥匙常驻通知 id（与系统 {@code Cancel FGS ... Id:5} 一致）；取消该通知视为未连接。
     */
    public static final int GEELY_DK_FG_NOTIFICATION_ID = 5;

    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_DISCONNECTED = 2;

    private GeelyDigitalKeyProximityUnlock() {
    }

    /**
     * 是否已在系统设置中为本应用开启通知使用权（{@link com.wmqc.miroot.service.MiRootNotificationListenerService}）。
     */
    public static boolean isNotificationListenerEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            String flat = Settings.Secure.getString(
                    context.getContentResolver(),
                    "enabled_notification_listeners"
            );
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            String pkg = context.getPackageName();
            String fullName = pkg + "/" + com.wmqc.miroot.service.MiRootNotificationListenerService.class.getName();
            for (String name : flat.split(":")) {
                if (fullName.equals(name)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "检查通知使用权失败", e);
        }
        return false;
    }

    /**
     * 打开系统「通知使用权」设置页（用户需勾选本应用的 {@link com.wmqc.miroot.service.MiRootNotificationListenerService}）。
     */
    public static void openNotificationListenerSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            LogHelper.w(TAG, "打开通知使用权设置失败", e);
        }
    }

    /**
     * 在 {@link android.service.notification.NotificationListenerService#onNotificationPosted} 中调用。
     */
    public static void handleNotification(Context context, StatusBarNotification sbn) {
        if (context == null || sbn == null) {
            return;
        }
        if (!PACKAGE_GEELY.equals(sbn.getPackageName())) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            return;
        }

        // 仅处理 FGS 数字钥匙常驻通知（与 dumpsys / Cancel FGS Id 一致），避免其它渠道通知干扰
        if (sbn.getId() != GEELY_DK_FG_NOTIFICATION_ID) {
            return;
        }

        LogHelper.i(TAG, "靠近解锁：收到吉利汽车通知 posted id=" + sbn.getId() + " key=" + sbn.getKey());

        Notification n = sbn.getNotification();
        if (n == null) {
            return;
        }

        String blob = buildNotificationText(n);
        int newState = parseKeyState(blob);
        if (newState == STATE_UNKNOWN) {
            String preview = blob.length() > 220 ? blob.substring(0, 220) + "…" : blob;
            LogHelper.i(TAG, "吉利 id=" + GEELY_DK_FG_NOTIFICATION_ID + " 未解析出已连接文案（未连接请依赖通知移除）。blobLen=" + blob.length() + " preview=" + preview);
            return;
        }

        int prev = prefs.getInt(KEY_LAST_STATE, STATE_UNKNOWN);
        if (newState == prev) {
            return;
        }

        prefs.edit().putInt(KEY_LAST_STATE, newState).apply();

        boolean unlock = prev == STATE_DISCONNECTED && newState == STATE_CONNECTED;
        boolean lock = prev == STATE_CONNECTED && newState == STATE_DISCONNECTED;

        if (unlock) {
            LogHelper.i(TAG, "数字钥匙状态变化 " + prev + " → " + newState + "，发送解锁车控（CarControlCommandService）");
            LogHelper.d(TAG, "数字钥匙状态变化: " + prev + " → " + newState + "，发送解锁车控（广播）");
            dispatchCarControl(context.getApplicationContext(), "unlock");
        } else if (lock) {
            LogHelper.i(TAG, "数字钥匙状态变化 " + prev + " → " + newState + "，发送锁车车控（CarControlCommandService）");
            LogHelper.d(TAG, "数字钥匙状态变化: " + prev + " → " + newState + "，发送锁车车控（广播）");
            dispatchCarControl(context.getApplicationContext(), "lock");
        } else {
            LogHelper.i(TAG, "数字钥匙状态变化 " + prev + " → " + newState + "，无车控动作（需未连接→已连接 或 已连接→未连接）");
            LogHelper.d(TAG, "数字钥匙状态变化: " + prev + " → " + newState + "，无车控动作");
        }
    }

    /**
     * FGS 数字钥匙通知被系统/应用取消时（断开常伴随 {@code Cancel FGS ... Id:5}），视为未连接。
     */
    public static void handleNotificationRemoved(Context context, StatusBarNotification sbn) {
        if (context == null || sbn == null) {
            return;
        }
        if (!PACKAGE_GEELY.equals(sbn.getPackageName())) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            return;
        }
        if (sbn.getId() != GEELY_DK_FG_NOTIFICATION_ID) {
            return;
        }

        LogHelper.i(TAG, "靠近解锁：吉利汽车通知 removed id=" + sbn.getId() + " key=" + sbn.getKey() + " → 视为数字钥匙未连接");

        int prev = prefs.getInt(KEY_LAST_STATE, STATE_UNKNOWN);
        int newState = STATE_DISCONNECTED;
        if (newState == prev) {
            return;
        }

        prefs.edit().putInt(KEY_LAST_STATE, newState).apply();

        boolean lock = prev == STATE_CONNECTED && newState == STATE_DISCONNECTED;
        if (lock) {
            LogHelper.i(TAG, "数字钥匙 FGS 通知移除（已连接→未连接），发送锁车车控");
            LogHelper.d(TAG, "数字钥匙通知 removed，发送锁车");
            dispatchCarControl(context.getApplicationContext(), "lock");
        } else {
            LogHelper.i(TAG, "数字钥匙 FGS 通知移除，prev=" + prev + "，无锁车动作");
        }
    }

    /**
     * 吉利汽车等常用自定义 RemoteViews，标准 EXTRA_TEXT 常为空；需拼接 ticker、Messaging 与各 extras 字符串。
     */
    private static String buildNotificationText(Notification n) {
        StringBuilder sb = new StringBuilder();
        appendExtra(sb, n.tickerText);

        Bundle extras = n.extras;
        if (extras == null) {
            return sb.toString();
        }

        appendExtra(sb, extras.getCharSequence(Notification.EXTRA_TITLE));
        appendExtra(sb, extras.getCharSequence(Notification.EXTRA_TEXT));
        appendExtra(sb, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        appendExtra(sb, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        appendExtra(sb, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        appendExtra(sb, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));

        // MessagingStyle
        Parcelable[] msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null) {
            for (Parcelable p : msgs) {
                if (p instanceof Bundle) {
                    appendExtra(sb, ((Bundle) p).getCharSequence("text"));
                }
            }
        }

        appendAllStringLikeValues(sb, extras);
        return sb.toString();
    }

    /**
     * 遍历 extras 中所有字符串类值（部分厂商/会把文案放在非标准 key 下）。
     */
    private static void appendAllStringLikeValues(StringBuilder sb, Bundle extras) {
        try {
            for (String key : extras.keySet()) {
                if (key == null) {
                    continue;
                }
                Object v = extras.get(key);
                if (v instanceof CharSequence) {
                    appendExtra(sb, (CharSequence) v);
                } else if (v instanceof String[]) {
                    for (String s : (String[]) v) {
                        appendExtra(sb, s);
                    }
                } else if (v instanceof CharSequence[]) {
                    for (CharSequence cs : (CharSequence[]) v) {
                        appendExtra(sb, cs);
                    }
                }
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "appendAllStringLikeValues", t);
        }
    }

    private static void appendExtra(StringBuilder sb, CharSequence cs) {
        if (cs != null && cs.length() > 0) {
            sb.append(cs).append(' ');
        }
    }

    /**
     * 解析「已连接」：中文「数字钥匙已连接」或英文摘要 {@code Geely dk running}（extras 实机如此）。
     * <p>「未连接」不在此依赖模糊英文词；以 {@link #handleNotificationRemoved}（撤掉 id=5 FGS）为主。
     * 若 extras 将来出现中文「数字钥匙未连接」，可在此返回 {@link #STATE_DISCONNECTED} 以触发 posted 路径锁车。</p>
     */
    static int parseKeyState(String blob) {
        if (TextUtils.isEmpty(blob)) {
            return STATE_UNKNOWN;
        }
        boolean hasConnected = blob.contains(PHRASE_CONNECTED);
        boolean hasDisconnected = blob.contains(PHRASE_DISCONNECTED);
        if (hasConnected && hasDisconnected) {
            int iConn = blob.indexOf(PHRASE_CONNECTED);
            int iDis = blob.indexOf(PHRASE_DISCONNECTED);
            return iConn <= iDis ? STATE_CONNECTED : STATE_DISCONNECTED;
        }
        if (hasConnected) {
            return STATE_CONNECTED;
        }
        if (hasDisconnected) {
            return STATE_DISCONNECTED;
        }

        String lower = blob.toLowerCase(Locale.ROOT);
        if (lower.contains(PHRASE_CONNECTED_EN)
                || (lower.contains("geely") && lower.contains("dk") && lower.contains("running"))) {
            return STATE_CONNECTED;
        }
        return STATE_UNKNOWN;
    }

    private static void dispatchCarControl(Context appContext, String function) {
        try {
            Intent serviceIntent = new Intent(appContext, CarControlCommandService.class);
            serviceIntent.setAction(CarControlIntents.ACTION_CAR_CONTROL_COMMAND);
            serviceIntent.putExtra(CarControlCommandService.EXTRA_FUNCTION, function);
            appContext.startService(serviceIntent);
        } catch (Exception e) {
            LogHelper.e(TAG, "启动 CarControlCommandService 失败: " + function, e);
        }
    }
}
