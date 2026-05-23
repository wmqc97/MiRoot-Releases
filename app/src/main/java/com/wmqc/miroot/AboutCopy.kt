package com.wmqc.miroot

import android.content.Context

/** 与「关于」弹窗正文一致，供 [PermissionFragment] 等处复用。 */
object AboutCopy {

    fun buildBodyText(context: Context): String =
        buildString {
            append(context.getString(R.string.settings_about_section_intro))
            append("\n")
            append(context.getString(R.string.settings_about_app_desc))
            append("\n\n")
            append(context.getString(R.string.settings_about_section_software))
            append("\n")
            append(context.getString(R.string.settings_about_software))
        }
}
