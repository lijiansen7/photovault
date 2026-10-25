package com.photovault.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地保存的密码用 Android Keystore 里的 AES-GCM 密钥加密。
 *
 * 为什么值得做：DataStore 就是一个明文 XML/二进制文件，`adb backup`、
 * 云备份、root 之后的文件管理器都能直接读走 —— 里面躺着 NAS 的账号密码，
 * 等于把整台 NAS 交出去。密钥放在 Keystore 里导不出来（有 TEE 就在 TEE 里），
 * 拿到文件也解不开。
 *
 * 不引第三方库：androidx.security-crypto 已经不再维护，Keystore 是系统自带的。
 */
object SecretStore {

    private const val ALIAS = "photovault_secret_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"
    private const val IV_LEN = 12          // GCM 的推荐 IV 长度，Keystore 就这么给

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
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

    /** 加密成 `v1:<base64(iv+密文)>`。失败返回空串 —— 宁可让用户下次重输，也不明文落盘。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
        } catch (t: Throwable) {
            ""
        }
    }

    /** 认不出 `v1:` 前缀就当明文（老版本存下的），下次保存会自动转成密文。 */
    fun decrypt(stored: String): String {
        if (stored.isBlank()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return ""
            val iv = raw.copyOfRange(0, IV_LEN)
            val body = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }
}
