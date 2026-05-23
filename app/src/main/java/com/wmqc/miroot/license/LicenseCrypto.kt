package com.wmqc.miroot.license

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed 的对称加密工具，用于把“激活/解锁相关信息”以密文形式落盘。
 *
 * - 使用 AES/GCM/NoPadding
 * - 存储格式：Base64(IV(12 bytes) + cipherText(含 GCM tag))
 */
object LicenseCrypto {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "miroot_license_aes_gcm_key"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12 // 96-bit
    private const val TAG_SIZE_BITS = 128

    private fun getOrCreateAesKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER,
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    fun encryptToBase64(plain: ByteArray): String? = runCatching {
        val key = getOrCreateAesKey() ?: return@runCatching null
        val iv = ByteArray(IV_SIZE_BYTES)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val cipherText = cipher.doFinal(plain)
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    }.getOrNull()

    fun decryptFromBase64(encryptedBase64: String): ByteArray? = runCatching {
        val key = getOrCreateAesKey() ?: return@runCatching null
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (combined.size <= IV_SIZE_BYTES) return@runCatching null

        val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
        val cipherText = combined.copyOfRange(IV_SIZE_BYTES, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        cipher.doFinal(cipherText)
    }.getOrNull()
}

