package com.photovault.app.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "photovault")

data class NasConfig(
    /** 用户自己填的 NAS 地址，如 http://192.168.1.10:8765 或 192.168.1.10 */
    val baseUrl: String = "",
    /** 内网地址：优先使用，连不上自动切外网 */
    val lanUrl: String = "",
    /** 外网地址：内网不可达时使用 */
    val wanUrl: String = "",
    /** 登录后拿到的 Bearer token；为空表示还没登录 */
    val authToken: String = "",
    val username: String = "",
    val role: String = "",
    val userId: String = "",
    val displayName: String = "",
    /** 仅在用户勾选「记住密码」时保存，用于 token 过期后静默重登 */
    val savedPassword: String = "",
    val deviceId: String = "",
    /** 用户手动设置的设备 ID：为 true 时不再被 Android ID 派生值覆盖 */
    val deviceIdCustom: Boolean = false,
    /** 自动备份总开关。**默认关** —— 配好地址不等于用户同意开始上传。 */
    val backupEnabled: Boolean = false,
    /**
     * 要备份的相册（BUCKET_DISPLAY_NAME，如 Camera / DCIM / WeiXin）。
     * - `null` = 还没挑过 → 全部相册都备份（首次的默认）
     * - 空集 = 用户明确一个都不选
     */
    val selectedAlbums: Set<String>? = null,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeVideo: Boolean = true,
    /**
     * 手机本地删除照片后，是否把它们在 NAS 上也移入回收站（按设备记墓碑）。
     * 默认关：删除是破坏性操作，必须用户明确同意。
     */
    val syncDeletions: Boolean = false,
    /** 后台轮询周期（分钟） */
    val periodMinutes: Long = 60,
    /**
     * 还原时本地已经有同一张怎么办：
     * - `skip`（默认）：跳过
     * - `duplicate`：再存一份
     * - `overwrite`：覆盖本地那份（只能覆盖自己创建的文件）
     */
    val restoreConflict: String = "skip",
    /** 上次同步到的时间点 */
    val lastSyncMs: Long = 0L,
) {
    /** 兼容旧单地址：优先内网，其次外网，最后老 baseUrl。 */
    val primaryUrl: String get() = lanUrl.ifBlank { wanUrl }.ifBlank { baseUrl }
    val configured: Boolean get() = primaryUrl.isNotBlank()
    val hasLan: Boolean get() = lanUrl.isNotBlank()
    val hasWan: Boolean get() = wanUrl.isNotBlank()
    val loggedIn: Boolean get() = authToken.isNotBlank()
    val isAdmin: Boolean get() = role == "admin"
    val who: String get() = displayName.ifBlank { username }.ifBlank { "兼容 Token" }
}

class SettingsRepo(private val context: Context) {

    private object K {
        val URL = stringPreferencesKey("nas_url")
        val LAN_URL = stringPreferencesKey("nas_lan_url")
        val WAN_URL = stringPreferencesKey("nas_wan_url")
        // 旧版本明文键：只用于读取迁移，写入时一律加密并删除
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val SAVED_PW = stringPreferencesKey("saved_password")
        val AUTH_TOKEN_ENC = stringPreferencesKey("auth_token_enc")
        val SAVED_PW_ENC = stringPreferencesKey("saved_password_enc")
        val USERNAME = stringPreferencesKey("username")
        val ROLE = stringPreferencesKey("role")
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_ID_CUSTOM = booleanPreferencesKey("device_id_custom")
        val BACKUP = booleanPreferencesKey("backup_enabled")
        val ALBUMS = stringSetPreferencesKey("selected_albums")
        val WIFI = booleanPreferencesKey("wifi_only")
        val CHARGING = booleanPreferencesKey("charging_only")
        val VIDEO = booleanPreferencesKey("include_video")
        val SYNC_DELETIONS = booleanPreferencesKey("sync_deletions")
        val PERIOD = longPreferencesKey("period_minutes")
        val CONFLICT = stringPreferencesKey("restore_conflict")
        val LAST_SYNC = longPreferencesKey("last_sync_ms")
    }

    private fun readSecret(enc: String?, plain: String?): String {
        if (!enc.isNullOrBlank()) {
            val decoded = SecretBox.decrypt(enc)
            if (decoded != null) return decoded
        }
        return plain ?: ""
    }

    private fun putSecret(p: MutablePreferences, encKey: Preferences.Key<String>,
                          plainKey: Preferences.Key<String>, value: String) {
        val enc = SecretBox.encrypt(value)
        if (enc != null) {
            p[encKey] = enc
            p.remove(plainKey)
        } else {
            // Keystore 不可用时宁可退化为明文，也不能让用户直接登不进去
            p[plainKey] = value
            p.remove(encKey)
        }
    }

    val config: Flow<NasConfig> = context.dataStore.data.map { p ->
        NasConfig(
            baseUrl = p[K.URL] ?: "",
            lanUrl = p[K.LAN_URL] ?: "",
            // 旧版本只有一个地址：默认当外网地址，用户可以在设置里再补内网地址。
            wanUrl = p[K.WAN_URL] ?: (p[K.URL] ?: ""),
            authToken = readSecret(p[K.AUTH_TOKEN_ENC], p[K.AUTH_TOKEN]),
            username = p[K.USERNAME] ?: "",
            role = p[K.ROLE] ?: "",
            userId = p[K.USER_ID] ?: "",
            displayName = p[K.DISPLAY_NAME] ?: "",
            savedPassword = readSecret(p[K.SAVED_PW_ENC], p[K.SAVED_PW]),
            deviceId = p[K.DEVICE_ID] ?: "",
            deviceIdCustom = p[K.DEVICE_ID_CUSTOM] ?: false,
            backupEnabled = p[K.BACKUP] ?: false,
            selectedAlbums = p[K.ALBUMS],
            wifiOnly = p[K.WIFI] ?: true,
            chargingOnly = p[K.CHARGING] ?: false,
            includeVideo = p[K.VIDEO] ?: true,
            syncDeletions = p[K.SYNC_DELETIONS] ?: false,
            periodMinutes = p[K.PERIOD] ?: 60,
            restoreConflict = p[K.CONFLICT] ?: "skip",
            lastSyncMs = p[K.LAST_SYNC] ?: 0L,
        )
    }

    suspend fun current(): NasConfig = config.first()

    suspend fun save(cfg: NasConfig) {
        context.dataStore.edit { p ->
            p[K.URL] = cfg.lanUrl.ifBlank { cfg.wanUrl }.trim()
            p[K.LAN_URL] = cfg.lanUrl.trim()
            p[K.WAN_URL] = cfg.wanUrl.trim()
            putSecret(p, K.AUTH_TOKEN_ENC, K.AUTH_TOKEN, cfg.authToken.trim())
            putSecret(p, K.SAVED_PW_ENC, K.SAVED_PW, cfg.savedPassword)
            p[K.USERNAME] = cfg.username.trim()
            p[K.ROLE] = cfg.role.trim()
            p[K.USER_ID] = cfg.userId.trim()
            p[K.DISPLAY_NAME] = cfg.displayName.trim()
            p[K.BACKUP] = cfg.backupEnabled
            // null 表示"没挑过"，得把键删掉才能和"空集（一个都不选）"区分
            if (cfg.selectedAlbums == null) p.remove(K.ALBUMS) else p[K.ALBUMS] = cfg.selectedAlbums!!
            p[K.WIFI] = cfg.wifiOnly
            p[K.CHARGING] = cfg.chargingOnly
            p[K.VIDEO] = cfg.includeVideo
            p[K.SYNC_DELETIONS] = cfg.syncDeletions
            p[K.PERIOD] = cfg.periodMinutes
            p[K.CONFLICT] = cfg.restoreConflict
            if (cfg.deviceId.isNotBlank()) p[K.DEVICE_ID] = cfg.deviceId
            p[K.DEVICE_ID_CUSTOM] = cfg.deviceIdCustom
        }
    }

    /** 保存登录结果（内外网地址都由用户在设置里维护，这里不动地址）。 */
    suspend fun saveLogin(r: LoginResult, rememberPassword: String) {
        context.dataStore.edit { p ->
            putSecret(p, K.AUTH_TOKEN_ENC, K.AUTH_TOKEN, r.token)
            putSecret(p, K.SAVED_PW_ENC, K.SAVED_PW, rememberPassword)
            p[K.USERNAME] = r.username
            p[K.ROLE] = r.role
            p[K.USER_ID] = r.id
            p[K.DISPLAY_NAME] = r.displayName
        }
    }

    /** 退出登录：清掉身份，地址和备份偏好保留。 */
    suspend fun logout() {
        context.dataStore.edit { p ->
            p.remove(K.AUTH_TOKEN)
            p.remove(K.AUTH_TOKEN_ENC)
            p.remove(K.SAVED_PW)
            p.remove(K.SAVED_PW_ENC)
            p[K.USERNAME] = ""
            p[K.ROLE] = ""
            p[K.USER_ID] = ""
            p[K.DISPLAY_NAME] = ""
        }
    }

    suspend fun deviceId(): String {
        val cur = current()
        // 用户手动设过就优先用用户的值
        if (cur.deviceIdCustom && cur.deviceId.isNotBlank()) return cur.deviceId

        // 默认用 ANDROID_ID 派生稳定设备 ID：同一台手机、同一签名、同一用户下，
        // 卸载重装/清数据都不会变。原始 ANDROID_ID 不落盘、不上传。
        val stable = stableDeviceId()
        if (stable.isNotBlank()) {
            if (cur.deviceId != stable) {
                context.dataStore.edit { p -> p[K.DEVICE_ID] = stable }
            }
            return stable
        }
        // 极少数取不到 ANDROID_ID 的设备才退回随机 ID
        if (cur.deviceId.isNotBlank()) return cur.deviceId
        val fresh = "android-" + UUID.randomUUID().toString().take(8)
        context.dataStore.edit { p -> p[K.DEVICE_ID] = fresh }
        return fresh
    }

    /** 保存用户自定义的 ID；下次启动/同步都优先用它。 */
    suspend fun setDeviceId(id: String, custom: Boolean = true) {
        context.dataStore.edit { p ->
            p[K.DEVICE_ID] = id.trim()
            p[K.DEVICE_ID_CUSTOM] = custom
        }
    }

    private fun stableDeviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8))
        return "android-" + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    suspend fun markSynced(atMs: Long) {
        context.dataStore.edit { p -> p[K.LAST_SYNC] = atMs }
    }

    suspend fun resetSync() {
        context.dataStore.edit { p -> p[K.LAST_SYNC] = 0L }
    }
}
