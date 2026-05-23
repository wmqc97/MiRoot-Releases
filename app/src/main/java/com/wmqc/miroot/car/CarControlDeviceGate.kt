package com.wmqc.miroot.car

import android.content.Context
import com.wmqc.miroot.license.OfflineActivationRepository

/**
 * 车控设备白名单校验：仅列表内硬件码允许进入和使用车控能力（含外部广播触发的投屏/指令/查询）。
 */
object CarControlDeviceGate {
    private val ALLOWED_DEVICE_CODES: Set<String> = setOf(
        "234DB891E59CC405",
    )

    @JvmStatic
    fun isAllowed(context: Context): Boolean {
        val deviceCode = OfflineActivationRepository.getOrCreateDeviceCode(context.applicationContext)
        return ALLOWED_DEVICE_CODES.contains(deviceCode)
    }
}
