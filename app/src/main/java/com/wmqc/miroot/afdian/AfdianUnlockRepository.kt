package com.wmqc.miroot.afdian

import android.content.Context
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.license.LicenseCrypto

/**
 * 通过爱发电订单号校验后解锁的本地开关（[isUnlocked]）。
 * 其他功能可在业务处调用 [isUnlocked] 判断是否开放。
 */
object AfdianUnlockRepository {

    private const val PREFS = "afdian_unlock_prefs"
    private const val KEY_UNLOCKED_ENC = "afdian_unlocked_enc"
    private const val KEY_ORDER_ENC = "afdian_verified_out_trade_no_enc"

    // 旧版本（明文）字段名：不兼容读取，仅用于首次升级清理。
    private const val LEGACY_KEY_UNLOCKED = "afdian_unlocked"
    private const val LEGACY_KEY_ORDER = "afdian_verified_out_trade_no"

    /** 爱发电 Webhook 示例中已支付订单 status 为 2。 */
    private const val ORDER_STATUS_PAID = 2

    fun isUnlocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val unlockedEnc = prefs.getString(KEY_UNLOCKED_ENC, null)
        if (unlockedEnc.isNullOrBlank()) {
            // 不兼容老数据：尽量清理明文字段，避免“激活相关信息明文落盘”。
            val hasLegacy = !prefs.getString(LEGACY_KEY_UNLOCKED, null).isNullOrBlank() ||
                !prefs.getString(LEGACY_KEY_ORDER, null).isNullOrBlank()
            if (hasLegacy) {
                prefs.edit()
                    .remove(LEGACY_KEY_UNLOCKED)
                    .remove(LEGACY_KEY_ORDER)
                    .apply()
            }
            return false
        }

        val unlockedBytes = LicenseCrypto.decryptFromBase64(unlockedEnc) ?: return false
        return unlockedBytes.isNotEmpty() && unlockedBytes[0] == 1.toByte()
    }

    fun verifiedOrderNo(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val orderEnc = prefs.getString(KEY_ORDER_ENC, null) ?: return null
        val orderBytes = LicenseCrypto.decryptFromBase64(orderEnc) ?: return null
        return orderBytes.toString(Charsets.UTF_8)
    }

    /**
     * 调用开放接口校验订单号；成功则写入解锁状态。
     */
    suspend fun verifyOrderAndUnlock(context: Context, orderNo: String): Result<Unit> {
        val userId = BuildConfig.AFDIAN_USER_ID
        val token = BuildConfig.AFDIAN_TOKEN
        val result = AfdianOpenApi.queryOrderByOutTradeNo(userId, token, orderNo)
        if (result.ec != 200) {
            val msg = result.em.ifBlank { "接口错误 ec=${result.ec}" }
            return Result.failure(IllegalStateException(msg))
        }
        val order = result.orders.firstOrNull { it.outTradeNo.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未找到该订单，请核对订单号是否属于本店"))

        if (order.status != ORDER_STATUS_PAID) {
            return Result.failure(
                IllegalStateException("订单状态不可用（status=${order.status}），请确认已支付成功"),
            )
        }

        val unlockedEnc = LicenseCrypto.encryptToBase64(byteArrayOf(1)) ?: return Result.failure(
            IllegalStateException("无法加密解锁状态（Keystore 未就绪）"),
        )
        val orderEnc = LicenseCrypto.encryptToBase64(order.outTradeNo.toByteArray(Charsets.UTF_8)) ?: return Result.failure(
            IllegalStateException("无法加密订单号（Keystore 未就绪）"),
        )

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UNLOCKED_ENC, unlockedEnc)
            .putString(KEY_ORDER_ENC, orderEnc)
            .remove(LEGACY_KEY_UNLOCKED)
            .remove(LEGACY_KEY_ORDER)
            .apply()
        return Result.success(Unit)
    }
}
