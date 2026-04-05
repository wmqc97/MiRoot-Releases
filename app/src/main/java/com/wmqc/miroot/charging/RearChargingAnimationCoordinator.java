package com.wmqc.miroot.charging;

import android.content.Context;
import android.content.Intent;

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
    private static volatile boolean interruptedChargingWasAlwaysOn;

    private RearChargingAnimationCoordinator() {
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

    public static synchronized void markInterruptedChargingAsAlwaysOn(boolean alwaysOn) {
        interruptedChargingWasAlwaysOn = alwaysOn;
        LogHelper.d(TAG, "被打断的充电动画常亮标记: " + alwaysOn);
    }

    public static synchronized boolean shouldResumeChargingAnimation() {
        return interruptedChargingWasAlwaysOn;
    }

    public static synchronized void clearChargingAlwaysOnFlag() {
        interruptedChargingWasAlwaysOn = false;
    }

    public static synchronized boolean endAnimation(AnimationType type) {
        if (currentAnimation != type) {
            LogHelper.w(TAG, "尝试结束动画[" + type + "]，但当前是[" + currentAnimation + "]");
            return false;
        }
        boolean shouldRestore = shouldRestoreOnDestroy;
        if (shouldRestore) {
            LogHelper.d(TAG, "动画[" + type + "]正常结束，需要恢复背屏");
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
