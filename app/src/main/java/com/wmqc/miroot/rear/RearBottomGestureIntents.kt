package com.wmqc.miroot.rear

/**
 * 背屏底部三手势统一广播（主题 MAML 注入发送 slot，由 [RearBottomGestureBroadcastReceiver] 按 App 内配置分发）。
 *
 * 手势与 slot 固定对应：1=底部上滑，2=底部左滑，3=底部右滑。
 */
object RearBottomGestureIntents {
    const val ACTION_REAR_BOTTOM_GESTURE = "com.wmqc.miroot.rear.ACTION_REAR_BOTTOM_GESTURE"

    /** int 1 / 2 / 3，与 [RearGestureInjectSpec] 一致。 */
    const val EXTRA_GESTURE_SLOT = "com.wmqc.miroot.rear.EXTRA_GESTURE_SLOT"
}
