package com.wmqc.miroot.charging;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.wmqc.miroot.lyrics.LogHelper;

/**
 * 充电视与通知类背屏动画的互斥与打断广播。
 * MiRoot 当前仅充电动画使用该协调器；通知类动画接入后可复用同一套 Action。
 */
public final class RearChargingAnimationCoordinator {

    private static final String TAG = "RearChargingAnimCoord";

    public enum AnimationType {
        NONE,
        CHARGING,
        NOTIFICATION,
    }

    private static volatile AnimationType currentAnimation = AnimationType.NONE;
    private static volatile boolean shouldRestoreOnDestroy = true;
    /** 充电层销毁后仍保护下层投屏，避免 onStop 800ms 宽限误关歌词/车控。 */
    private static volatile long chargingProtectionUntilElapsedMs = 0L;
    private static final long CHARGING_PROTECTION_MS = 3_000L;

    private RearChargingAnimationCoordinator() {
    }

    /**
     * 充电动画正在播放，或刚结束仍在保护窗口内（供歌词 onStop 宽限判断）。
     */
    public static synchronized boolean isChargingProtectionActive() {
        if (currentAnimation == AnimationType.CHARGING) {
            return true;
        }
        return SystemClock.elapsedRealtime() < chargingProtectionUntilElapsedMs;
    }

    private static synchronized void markChargingProtectionWindow() {
        chargingProtectionUntilElapsedMs =
            SystemClock.elapsedRealtime() + CHARGING_PROTECTION_MS;
    }

    public static synchronized AnimationType startAnimation(AnimationType type) {
        if (type == AnimationType.NONE) {
            LogHelper.w(TAG, "尝试启动 NONE，忽略");
            return AnimationType.NONE;
        }
        AnimationType oldAnimation = currentAnimation;
        if (oldAnimation != AnimationType.NONE) {
            LogHelper.d(TAG, "新动画[" + type + "]打断旧动画[" + oldAnimation + "]");
            shouldRestoreOnDestroy = false;
        } else {
            LogHelper.d(TAG, "开始播放动画[" + type + "]");
        }
        currentAnimation = type;
        shouldRestoreOnDestroy = true;
        return oldAnimation;
    }

    public static synchronized boolean endAnimation(AnimationType type) {
        if (currentAnimation != type) {
            LogHelper.w(TAG, "尝试结束动画[" + type + "]，但当前是[" + currentAnimation + "]");
            return false;
        }
        boolean shouldRestore = shouldRestoreOnDestroy;
        if (shouldRestore) {
            LogHelper.d(TAG, "动画[" + type + "]正常结束，需要恢复背屏");
            if (type == AnimationType.CHARGING) {
                markChargingProtectionWindow();
            }
        } else {
            LogHelper.d(TAG, "动画[" + type + "]被打断结束，不恢复背屏");
        }
        currentAnimation = AnimationType.NONE;
        shouldRestoreOnDestroy = true;
        return shouldRestore;
    }

    public static synchronized boolean isAnimationPlaying() {
        return currentAnimation != AnimationType.NONE;
    }

    public static synchronized AnimationType getCurrentAnimation() {
        return currentAnimation;
    }

    public static void sendInterruptBroadcast(Context context, AnimationType type) {
        String action;
        if (type == AnimationType.CHARGING) {
            action = ChargingIntents.ACTION_INTERRUPT_CHARGING_ANIMATION;
        } else if (type == AnimationType.NOTIFICATION) {
            action = ChargingIntents.ACTION_INTERRUPT_NOTIFICATION_ANIMATION;
        } else {
            return;
        }
        try {
            Intent intent = new Intent(action);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            LogHelper.d(TAG, "已发送打断广播: " + action);
        } catch (Exception e) {
            LogHelper.e(TAG, "发送打断广播失败", e);
        }
    }
}
