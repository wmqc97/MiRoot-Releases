package com.wmqc.miroot.rear

import android.content.Context
import com.wmqc.miroot.car.CarControlDeviceGate

/**
 * 背屏三手势配置（持久化）；[readInjectSpec] 供 [RearBottomGestureBroadcastReceiver] 分发与配置页 UI 使用。
 * 主题 zip 的「注入手势」为通用层，**不读取**本对象。
 */
object RearGesturePrefs {
    private const val PREF = "miroot_rear_bottom_gestures"
    private const val K_VER = "cfg_version"
    private const val K_S1 = "s1"
    private const val K_S1_PKG = "s1_pkg"
    private const val K_S2 = "s2"
    private const val K_S2_PKG = "s2_pkg"
    private const val K_S3 = "s3"
    private const val K_S3_PKG = "s3_pkg"

    private const val VER = 1

    fun readInjectSpec(context: Context): RearGestureInjectSpec {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains(K_VER)) {
            val def = RearGestureInjectSpec.defaultCompat()
            writeInjectSpec(app, def)
            return def
        }
        fun a(key: String, def: RearGestureAction): RearGestureAction {
            val o = p.getInt(key, def.ordinal)
            return RearGestureAction.entries.getOrNull(o) ?: def
        }
        var s1 = a(K_S1, RearGestureAction.MUSIC_LYRICS)
        var s2 = a(K_S2, RearGestureAction.NONE)
        var s3 = a(K_S3, RearGestureAction.NONE)
        var p1 = p.getString(K_S1_PKG, "").orEmpty().trim()
        var p2 = p.getString(K_S2_PKG, "").orEmpty().trim()
        var p3 = p.getString(K_S3_PKG, "").orEmpty().trim()
        if (!CarControlDeviceGate.isAllowed(app)) {
            if (s1 == RearGestureAction.CAR_CONTROL) s1 = RearGestureAction.NONE
            if (s2 == RearGestureAction.CAR_CONTROL) s2 = RearGestureAction.NONE
            if (s3 == RearGestureAction.CAR_CONTROL) s3 = RearGestureAction.NONE
        }
        return normalizeExclusive(
            RearGestureInjectSpec(
                slot1Up = s1,
                slot1LaunchPackage = p1,
                slot2Left = s2,
                slot2LaunchPackage = p2,
                slot3Right = s3,
                slot3LaunchPackage = p3,
            ),
        )
    }

    fun writeInjectSpec(context: Context, spec: RearGestureInjectSpec) {
        val app = context.applicationContext
        val n = normalizeExclusive(spec)
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putInt(K_VER, VER)
            putInt(K_S1, n.slot1Up.ordinal)
            putString(K_S1_PKG, n.slot1LaunchPackage.trim())
            putInt(K_S2, n.slot2Left.ordinal)
            putString(K_S2_PKG, n.slot2LaunchPackage.trim())
            putInt(K_S3, n.slot3Right.ordinal)
            putString(K_S3_PKG, n.slot3LaunchPackage.trim())
            apply()
        }
    }

    /**
     * 规范化：
     * 1. 未选包名的「指定应用」视为无动作。
     * 2. 背屏桌面 / 音乐投屏 / 车控 **每种动作最多占一个槽位**；若多槽选了同一动作，按槽位优先级 **1（上滑）> 2（左滑）> 3（右滑）** 仅保留一处，其余清空为 [NONE]。
     *    不同槽位可选用不同互斥动作（例如 1=音乐、2=车控、3=桌面）。
     */
    fun normalizeExclusive(spec: RearGestureInjectSpec): RearGestureInjectSpec {
        var s1 = spec.slot1Up
        var s2 = spec.slot2Left
        var s3 = spec.slot3Right
        if (s1 == RearGestureAction.LAUNCH_APP && spec.slot1LaunchPackage.isBlank()) {
            s1 = RearGestureAction.NONE
        }
        if (s2 == RearGestureAction.LAUNCH_APP && spec.slot2LaunchPackage.isBlank()) {
            s2 = RearGestureAction.NONE
        }
        if (s3 == RearGestureAction.LAUNCH_APP && spec.slot3LaunchPackage.isBlank()) {
            s3 = RearGestureAction.NONE
        }
        for (ex in RearGestureAction.entries.filter { it.isExclusive }) {
            val slots = ArrayList<Int>(3)
            if (s1 == ex) slots.add(1)
            if (s2 == ex) slots.add(2)
            if (s3 == ex) slots.add(3)
            if (slots.size <= 1) continue
            val keep = slots.min()
            if (keep != 1 && s1 == ex) s1 = RearGestureAction.NONE
            if (keep != 2 && s2 == ex) s2 = RearGestureAction.NONE
            if (keep != 3 && s3 == ex) s3 = RearGestureAction.NONE
        }
        return RearGestureInjectSpec(
            slot1Up = s1,
            slot1LaunchPackage = spec.slot1LaunchPackage.trim(),
            slot2Left = s2,
            slot2LaunchPackage = spec.slot2LaunchPackage.trim(),
            slot3Right = s3,
            slot3LaunchPackage = spec.slot3LaunchPackage.trim(),
        )
    }
}
