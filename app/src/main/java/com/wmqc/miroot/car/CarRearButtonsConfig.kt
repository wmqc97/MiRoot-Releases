package com.wmqc.miroot.car

import android.content.Context
import org.json.JSONArray

/** 背屏 / 小组件共用的车控按钮顺序（SharedPreferences `rear_buttons_order`）。 */
object CarRearButtonsConfig {

    const val PREFS_KEY = "rear_buttons_order"

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREFS_KEY, null)
        if (raw.isNullOrBlank()) {
            return fallbackFromLegacySet(prefs) ?: defaultRearButtonsForFirstInstall()
        }
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }.getOrElse { defaultRearButtonsForFirstInstall() }
            .let { normalize(it) }
    }

    /** 小组件与背屏首屏一致：固定 4 格，不足时用默认按钮补齐。 */
    fun firstFour(context: Context): List<String> {
        val order = load(context)
        val defaults = defaultRearButtonsForFirstInstall()
        return List(4) { index ->
            order.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: defaults.getOrNull(index)
                ?: ""
        }
    }

    private fun fallbackFromLegacySet(prefs: android.content.SharedPreferences): List<String>? {
        val set = prefs.getStringSet("rear_buttons", null) ?: return null
        if (set.isEmpty()) return null
        return normalize(set.toList())
    }

    private fun normalize(buttons: List<String>): List<String> {
        val allowed = RearButtonConfigDialog.CONTROL_FUNCTIONS.toSet()
        return buttons.map { it.trim() }
            .filter { it.isNotEmpty() && it in allowed }
            .distinct()
            .ifEmpty { defaultRearButtonsForFirstInstall() }
    }
}
