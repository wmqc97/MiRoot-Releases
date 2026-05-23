package com.wmqc.miroot;

import android.view.MotionEvent;
import android.view.View;

/**
 * 背屏投屏「底部边缘左右滑退出」：将触摸映射到 {@link android.view.Window#getDecorView()} 的本地坐标，
 * 用屏幕 raw 坐标保证整条底边（左右）与系统对齐一致。
 */
public final class BottomSwipeExitHelper {
    private BottomSwipeExitHelper() {}

    /**
     * @param outXY 至少长度 2，写入 decor 局部 x、y（与 {@link View#getWidth()}/{@link View#getHeight()} 同空间）
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
}
