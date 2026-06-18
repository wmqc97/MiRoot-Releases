package com.wmqc.miroot.ui.apps

import android.graphics.drawable.Drawable
import com.wmqc.miroot.rear.AppProjectionDisplayPrefs.AppDisplayConfig

data class InstalledAppRow(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val projectionConfig: AppDisplayConfig? = null,
    /** 背屏桌面「自选应用」列表中是否已勾选（应用列表页展示）。*/
    val rearDesktopPinned: Boolean = false,
    /** 是否已加入自动投屏音乐黑名单。*/
    val musicAutoProjectionBlacklisted: Boolean = false,
)
