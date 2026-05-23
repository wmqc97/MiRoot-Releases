package com.wmqc.miroot.license

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.wmqc.miroot.license.LicenseCrypto
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 离线激活：基于本机设备码生成并校验激活码。
 *
 * 激活相关落盘数据以密文形式落盘（不使用明文存储）。
 * 标记加密不依赖 AndroidKeyStore，避免“Keystore 未就绪”导致激活失败。
 * 同时不把“设备码”写入 SharedPreferences，避免本地留下可还原信息。
 *
 * 设备码：对若干稳定的 [Build] 字段做摘要（不含 ANDROID_ID / FINGERPRINT），
 * 同一机型在重装、切换 debug/release 签名后保持不变；OTA 若不改变这些字段通常也不变。
 *
 * 激活码为与设备码绑定的永久固定码，同一设备始终对应同一个激活码。
 */
object OfflineActivationRepository {
    private const val TAG = "OfflineActivationRepo"

    private const val PREFS = "offline_activation_prefs"
    private const val KEY_ACTIVATION_MARKER_ENC = "offline_activation_marker_enc"
    private const val KEY_ACTIVATION_MARKER_KEYSTORE_ENC = "offline_activation_marker_keystore_enc"

    // 旧版本（明文）字段名：只用于首次升级时清理；不提供兼容读取。
    private const val LEGACY_KEY_DEVICE_CODE = "offline_device_code"
    private const val LEGACY_KEY_ACTIVATION_MARKER = "offline_activation_marker"

    /**
     * 激活标记仅用于“已激活状态”判断，目的更多是避免 SharedPreferences 出现明文信息。
     * 为避免 AndroidKeyStore 在某些时序/ROM 上未就绪，激活标记改为纯本地 AES/GCM 加密：
     * - key：由 deviceCode + 固定盐派生
     * - 存储：Base64(IV(12B) + cipherText(含 GCM tag))
     */
    private const val MARKER_CIPHER = "AES/GCM/NoPadding"
    private const val MARKER_IV_SIZE_BYTES = 12
    private const val MARKER_TAG_SIZE_BITS = 128
    private const val MARKER_SALT = "MiRoot-Offline-Marker-V1"

    /**
     * 当前逻辑下的设备码（稳定硬件摘要，不落盘）。
     */
    fun getOrCreateDeviceCode(context: Context): String {
        return sha256(stableHardwareRaw()).take(16).uppercase(Locale.getDefault())
    }

    private fun stableHardwareRaw(): String {
        return listOf(
            Build.BRAND.orEmpty(),
            Build.MANUFACTURER.orEmpty(),
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty(),
            Build.HARDWARE.orEmpty(),
            Build.BOARD.orEmpty(),
            Build.PRODUCT.orEmpty(),
        ).joinToString("|")
    }

    /**
     * 激活状态判断：仅校验本地 AES/GCM 激活标记（以密文形式落盘）。
     */
    fun isActivated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val deviceCode = getOrCreateDeviceCode(context)
        val expected = markerPlainBytes(deviceCode)

        // 优先用 Keystore marker（更难被“复制 marker 数据”绕过）。
        val keystoreMarkerEnc = prefs.getString(KEY_ACTIVATION_MARKER_KEYSTORE_ENC, null)
        if (!keystoreMarkerEnc.isNullOrBlank()) {
            val stored = LicenseCrypto.decryptFromBase64(keystoreMarkerEnc)
            return stored != null && MessageDigest.isEqual(stored, expected)
        }

        // 否则回退到旧的纯本地 AES/GCM marker（兼容已激活用户数据）。
        val markerEnc = prefs.getString(KEY_ACTIVATION_MARKER_ENC, null)
        if (markerEnc.isNullOrBlank()) {
            // 不兼容老数据：同时尽量把明文字段清理掉，避免“激活相关信息明文落盘”。
            val hasLegacy = !prefs.getString(LEGACY_KEY_DEVICE_CODE, null).isNullOrBlank() ||
                !prefs.getString(LEGACY_KEY_ACTIVATION_MARKER, null).isNullOrBlank()
            if (hasLegacy) {
                prefs.edit()
                    .remove(LEGACY_KEY_DEVICE_CODE)
                    .remove(LEGACY_KEY_ACTIVATION_MARKER)
                    .apply()
            }
            return false
        }

        val storedLocal = decryptMarkerBytes(markerEnc, deviceCode)
        return storedLocal != null && MessageDigest.isEqual(storedLocal, expected)
    }

    /**
     * 校验离线激活码：算法一致时，传入正确激活码将本地标记为已激活。
     */
    fun verifyAndActivate(context: Context, userCode: String): Result<Unit> {
        val trimmed = normalizeUserCode(userCode)
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("激活码不能为空"))
        }
        val deviceCode = getOrCreateDeviceCode(context)
        val ok = verifyActivationCode(deviceCode = deviceCode, userCode = trimmed)
        if (!ok) return Result.failure(IllegalArgumentException("激活码不匹配，请核对后重试"))

        // 写入“仅用于避免明文”的激活标记密文。
        // - 本地 AES/GCM：用于兼容与 Keystore 时序异常兜底
        // - Keystore AES/GCM：用于提高复制/篡改成本（优先校验）
        val markerPlain = markerPlainBytes(deviceCode)
        val markerEnc = encryptMarkerBytes(markerPlain, deviceCode)
        if (markerEnc == null) {
            Log.e(TAG, "写激活标记失败：本地 AES/GCM 加密返回 null")
            return Result.failure(IllegalStateException("无法加密激活标记（本地加密失败）"))
        }

        val markerEncKeyStore = runCatching {
            LicenseCrypto.encryptToBase64(markerPlain)
        }.getOrNull()

        Log.d(TAG, "写激活标记：本地 AES/GCM + Keystore（可用时）")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVATION_MARKER_ENC, markerEnc)
            .apply {
                // Keystore 不可用时不失败：只要本地 marker 写入成功仍可激活。
                if (!markerEncKeyStore.isNullOrBlank()) {
                    putString(KEY_ACTIVATION_MARKER_KEYSTORE_ENC, markerEncKeyStore)
                }
            }
            .remove(LEGACY_KEY_ACTIVATION_MARKER)
            .remove(LEGACY_KEY_DEVICE_CODE)
            .apply()
        return Result.success(Unit)
    }

    /**
     * 根据设备码生成永久激活码；同一设备始终对应同一激活码。
     */
    fun buildActivationCode(deviceCode: String): String {
        val normalized = normalizeDeviceCode(deviceCode)
        val base = buildActivationCodeBase(normalized)
        return base.chunked(ACTIVATION_GROUP_SIZE).joinToString("-")
    }

    fun verifyActivationCode(deviceCode: String, userCode: String): Boolean {
        val normalizedCode = normalizeUserCode(userCode)
        if (normalizedCode.isEmpty()) return false
        val device = normalizeDeviceCode(deviceCode)
        return normalizedCode == buildActivationCode(device)
    }

    private fun buildActivationCodeBase(deviceCode: String): String {
        val password = "MiRoot-Permanent|$deviceCode|$INTERNAL_SALT".toCharArray()
        val salt = "MiRoot-Offline-Activation|$deviceCode|v2".toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(
            password,
            salt,
            ACTIVATION_KDF_ITERATIONS,
            ACTIVATION_KDF_OUTPUT_BYTES * 8,
        )
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val derived = factory.generateSecret(spec).encoded
            derived.joinToString(separator = "") { b -> "%02X".format(b) }
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }

    private fun sha256(raw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    /**
     * 内置盐值：拆分为多段以降低直接搜索可读性的简单混淆。
     */
    private const val INTERNAL_SALT_PART1 = "c8c4b7c9"
    private const val INTERNAL_SALT_PART2 = "fa3d91e2"
    private const val INTERNAL_SALT_PART3 = "5a7f29bd"
    private const val INTERNAL_SALT =
        INTERNAL_SALT_PART1 + INTERNAL_SALT_PART2 + INTERNAL_SALT_PART3

    private const val ACTIVATION_KDF_ITERATIONS = 80_000
    private const val ACTIVATION_KDF_OUTPUT_BYTES = 32
    private const val ACTIVATION_GROUP_SIZE = 8

    private fun normalizeUserCode(userCode: String): String =
        userCode
            .trim()
            .uppercase(Locale.getDefault())
            .replace("[^0-9A-Z]".toRegex(), "")
            .chunked(ACTIVATION_GROUP_SIZE)
            .joinToString("-")

    private fun normalizeDeviceCode(deviceCode: String): String =
        deviceCode
            .trim()
            .uppercase(Locale.getDefault())
            .replace("\\s+".toRegex(), "")

    private fun markerPlainBytes(deviceCode: String): ByteArray {
        val raw = "$MARKER_SALT|$deviceCode"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun deriveMarkerKey(deviceCode: String): SecretKey {
        val raw = "$MARKER_SALT|key|$deviceCode"
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(raw.toByteArray(StandardCharsets.UTF_8)) // 32 bytes -> 256-bit AES key
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptMarkerBytes(plain: ByteArray, deviceCode: String): String? = runCatching {
        val key = deriveMarkerKey(deviceCode)
        val iv = ByteArray(MARKER_IV_SIZE_BYTES)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(MARKER_CIPHER)
        val gcmSpec = GCMParameterSpec(MARKER_TAG_SIZE_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val cipherText = cipher.doFinal(plain)
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    }.getOrNull()

    private fun decryptMarkerBytes(encryptedBase64: String, deviceCode: String): ByteArray? = runCatching {
        val key = deriveMarkerKey(deviceCode)
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (combined.size <= MARKER_IV_SIZE_BYTES) return@runCatching null
        val iv = combined.copyOfRange(0, MARKER_IV_SIZE_BYTES)
        val cipherText = combined.copyOfRange(MARKER_IV_SIZE_BYTES, combined.size)

        val cipher = Cipher.getInstance(MARKER_CIPHER)
        val gcmSpec = GCMParameterSpec(MARKER_TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        cipher.doFinal(cipherText)
    }.getOrNull()
}

