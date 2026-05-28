package com.wmqc.miroot;

import android.app.Activity;
import android.view.MotionEvent;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 背屏投屏「底部边缘左右滑退出」手势辅助。
 * <p>
 * {@link #decorLocalXY(View, MotionEvent, float[])} — 将触摸 raw 坐标映射到 decor 本地坐标（原工具方法）。
 * <p>
 * {@link Handler} — 完整手势处理器，封装触摸事件分发、状态管理、阈值判断和触发回调，
 * 消除车控/歌词/桌面/测试 4 个 Activity 中重复的 {@code tryTrackBottomSwipeExit / maybeFireBottomSwipeExit}。
 * <p>
 * 使用方式：
 * <pre>{@code
 * private final BottomSwipeExitHelper.Handler bottomSwipeHandler =
 *     new BottomSwipeExitHelper.Handler(this, () -> finishProjectionFromUser("bottom-swipe"));
 *
 * @Override
 * public boolean dispatchTouchEvent(MotionEvent ev) {
 *     bottomSwipeHandler.handleTouchEvent(ev);
 *     return super.dispatchTouchEvent(ev);
 * }
 * }</pre>
 */
public final class BottomSwipeExitHelper {

    private BottomSwipeExitHelper() {}

    /**
     * 将触摸事件 raw 坐标映射到 {@code decor} 本地坐标（与 {@link View#getWidth()}/{@link View#getHeight()} 同空间）。
     *
     * @param outXY 至少长度 2，写入 decor 局部 x、y
     * @return 是否成功写入
     */
    public static boolean decorLocalXY(View decor, MotionEvent ev, float[] outXY) {
        if (decor == null || outXY == null || outXY.length < 2) return false;
        int[] loc = new int[2];
        decor.getLocationOnScreen(loc);
        outXY[0] = ev.getRawX() - loc[0];
        outXY[1] = ev.getRawY() - loc[1];
        return true;
    }

    // region Handler — 完整手势处理

    /** 屏底触发区域占比（底部 10%）。 */
    private static final float ZONE_FRACTION = 0.10f;

    /** 最小水平滑动距离（dp）。 */
    private static final float MIN_HORIZ_DP = 48f;

    /** 水平位移须大于垂直位移的倍数，避免与竖直滑动混淆。 */
    private static final float HORIZONTAL_DOMINANCE = 1.35f;

    /**
     * 底部左右滑结束投屏手势处理器。
     * <p>
     * 每个 Activity 持有一个实例，在 {@link Activity#dispatchTouchEvent} 中调用
     * {@link #handleTouchEvent(MotionEvent)} 即可。
     */
    public static final class Handler {

        private final java.lang.ref.WeakReference<Activity> mActivity;
        private final Runnable mOnTrigger;
        private final float mMinHorizPx;

        private boolean mPointerDownInZone;
        private float mStartY;
        private float mStartX;
        private boolean mPending;

        /**
         * @param activity 宿主 Activity（仅弱引用持有，不阻止 GC）
         * @param onTrigger 手势触发时的回调（如 {@code () -> finishProjectionFromUser("bottom-swipe")}）
         */
        public Handler(Activity activity, Runnable onTrigger) {
            mActivity = new java.lang.ref.WeakReference<>(activity);
            mOnTrigger = onTrigger;
            mMinHorizPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    MIN_HORIZ_DP,
                    activity.getResources().getDisplayMetrics());
        }

        /**
         * 在 {@link Activity#dispatchTouchEvent} 中调用，驱动手势状态机。
         * <p>
         * 建议在调用前由 Activity 自行判断 displayId 等前置条件（如 {@code if (getDisplayIdSafe() == 1)}），
         * 但也可无条件调用——本方法在 Activity 无效/已 finishing/已触发后直接返回。
         */
        public void handleTouchEvent(MotionEvent ev) {
            Activity a = mActivity.get();
            if (a == null || a.isFinishing() || mPending) return;

            View decor = a.getWindow().getDecorView();
            if (decor == null) return;

            float[] xy = new float[2];
            if (!decorLocalXY(decor, ev, xy)) return;

            int action = ev.getActionMasked();
            float h = decor.getHeight();
            float y = xy[1];
            float x = xy[0];

            if (action == MotionEvent.ACTION_DOWN && ev.getPointerCount() == 1) {
                mPointerDownInZone = h > 0 && y > h * (1f - ZONE_FRACTION);
                mStartY = y;
                mStartX = x;
                return;
            }

            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                mPointerDownInZone = false;
                return;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (mPointerDownInZone && ev.getPointerCount() == 1) {
                    maybeFire(y, x);
                }
                return;
            }

            if (action == MotionEvent.ACTION_UP) {
                if (mPointerDownInZone && ev.getPointerCount() == 1) {
                    maybeFire(y, x);
                }
                mPointerDownInZone = false;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                mPointerDownInZone = false;
            }
        }

        /**
         * 检查位移是否达到触发阈值，是则执行回调。
         */
        private void maybeFire(float endY, float endX) {
            float horizDist = Math.abs(endX - mStartX);
            float vertDist = Math.abs(endY - mStartY);
            Activity a = mActivity.get();
            if (a == null) return;
            float touchSlop = ViewConfiguration.get(a).getScaledTouchSlop();
            if (vertDist < touchSlop * 2.5f) {
                vertDist = 0f;
            }
            if (horizDist < mMinHorizPx || horizDist < vertDist * HORIZONTAL_DOMINANCE) {
                return;
            }
            mPending = true;
            mPointerDownInZone = false;
            mOnTrigger.run();
        }
    }

    // endregion
}
