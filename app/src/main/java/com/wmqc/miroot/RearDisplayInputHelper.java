package com.wmqc.miroot;

import android.app.Activity;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/**
 * 背屏 Activity 若带有 {@link WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE} /
 * {@link WindowManager.LayoutParams#FLAG_NOT_FOCUSABLE} /
 * {@link WindowManager.LayoutParams#FLAG_NOT_TOUCH_MODAL}，系统无法把触摸与边缘返回派给本窗口。
 * <p>
 * 结束投屏等路径会临时加 {@code FLAG_NOT_TOUCHABLE}；正常展示副屏时应清除，避免「背屏完全不响应手势」。
 */
public final class RearDisplayInputHelper {

    private RearDisplayInputHelper() {}

    /**
     * 清除会阻止窗口接收输入的标志，保证为标准可聚焦、可触摸的应用窗口行为。
     */
    public static void ensureApplicationWindowReceivesInput(Activity activity) {
        if (activity == null) {
            return;
        }
        if (activity.isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return;
        }
        Window w = activity.getWindow();
        if (w == null) {
            return;
        }
        w.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
    }
}
