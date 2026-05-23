package com.wmqc.miroot.rear.desktop

import android.graphics.drawable.Drawable

enum class RearDesktopListMode {
    MANUAL,
    /**
     * 全部启动器应用（排除黑名单）；背屏由 [RearDesktopHoneycombScreen] 展示固定 4 列蜂窝交错网格，
     * 视口中心焦点缩放，仅上下平移与惯性。
     */
    ALL_BY_FREQUENCY,
}

data class RearDesktopAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)
