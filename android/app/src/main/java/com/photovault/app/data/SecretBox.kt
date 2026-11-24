package com.photovault.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密本地保存的 token / 密码。
 *
 * 之前 DataStore 里是明文；`allowBackup=true` 时还会随系统备份被带走。
 * 密钥由 Keystore 保管，应用卸载即销毁，不需要额外依赖。
 */
object SecretBox {
    private const val TAG = "PhotoVault/Secret"
    private const val ALIAS = "photovault.secret.v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** 加密失败返回 null（调用方退化为明文，但仍然可用）。 */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "$PREFIX:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt failed", t)
            null
        }
    }

    /** 解密失败返回 null（密钥失效 / 数据损坏），调用方当作未登录处理。 */
    fun decrypt(blob: String?): String? {
        if (blob.isNullOrEmpty()) return ""
        if (!blob.startsWith("$PREFIX:")) return null
        return try {
            val parts = blob.split(":")
            if (parts.size != 3) return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ct = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed", t)
            null
        }
    }
}
