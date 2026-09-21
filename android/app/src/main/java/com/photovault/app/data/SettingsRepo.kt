package com.photovault.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "photovault")

data class NasConfig(
    /** 用户自己填的 NAS 地址，如 http://192.168.1.10:8765 或 192.168.1.10 */
    val baseUrl: String = "",
    /** 登录后拿到的 Bearer token；为空表示还没登录 */
    val authToken: String = "",
    val username: String = "",
    val role: String = "",
    val userId: String = "",
    val displayName: String = "",
    /** 仅在用户勾选「记住密码」时保存，用于 token 过期后静默重登 */
    val savedPassword: String = "",
    val deviceId: String = "",
    /** 自动备份总开关。**默认关** —— 配好地址不等于用户同意开始上传。 */
    val backupEnabled: Boolean = false,
    /**
     * 要备份的相册（BUCKET_DISPLAY_NAME，如 Camera / DCIM / WeiXin）。
     * - `null` = 还没挑过 → 全部相册都备份（首次的默认）
     * - 空集 = 用户明确一个都不选
     *
     * 必须用 null 和空集区分开：否则"全部取消勾选"会被当成"默认全部"。
     */
    val selectedAlbums: Set<String>? = null,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeVideo: Boolean = true,
    /** 后台轮询周期（分钟） */
    val periodMinutes: Long = 60,
    /**
     * 还原时本地已经有同一张怎么办：
     * - `skip`（默认）：跳过。还原是"把缺的补回来"，重复点是幂等的
     * - `duplicate`：再存一份（MediaStore 会自动改名成 xxx (1).jpg）
     * - `overwrite`：覆盖本地那份。**只对自己创建的文件有效**，
     *   系统相机/微信保存的照片在 Android 10+ 上覆盖不了，会自动降级成 duplicate
     */
    val restoreConflict: String = "skip",
    /** 上次同步到的时间点 */
    val lastSyncMs: Long = 0L,
) {
    val configured: Boolean get() = baseUrl.isNotBlank()
    val loggedIn: Boolean get() = authToken.isNotBlank()
    val isAdmin: Boolean get() = role == "admin"
    val who: String get() = displayName.ifBlank { username }.ifBlank { "兼容 Token" }
}

class SettingsRepo(private val context: Context) {

    private object K {
        val URL = stringPreferencesKey("nas_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val USERNAME = stringPreferencesKey("username")
        val ROLE = stringPreferencesKey("role")
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val SAVED_PW = stringPreferencesKey("saved_password")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val BACKUP = booleanPreferencesKey("backup_enabled")
        val ALBUMS = stringSetPreferencesKey("selected_albums")
        val WIFI = booleanPreferencesKey("wifi_only")
        val CHARGING = booleanPreferencesKey("charging_only")
        val VIDEO = booleanPreferencesKey("include_video")
        val PERIOD = longPreferencesKey("period_minutes")
        val CONFLICT = stringPreferencesKey("restore_conflict")
        val LAST_SYNC = longPreferencesKey("last_sync_ms")
    }

    val config: Flow<NasConfig> = context.dataStore.data.map { p ->
        NasConfig(
            baseUrl = p[K.URL] ?: "",
            authToken = p[K.AUTH_TOKEN] ?: "",
            username = p[K.USERNAME] ?: "",
            role = p[K.ROLE] ?: "",
            userId = p[K.USER_ID] ?: "",
            displayName = p[K.DISPLAY_NAME] ?: "",
            savedPassword = p[K.SAVED_PW] ?: "",
            deviceId = p[K.DEVICE_ID] ?: "",
            backupEnabled = p[K.BACKUP] ?: false,
            selectedAlbums = p[K.ALBUMS],
            wifiOnly = p[K.WIFI] ?: true,
            chargingOnly = p[K.CHARGING] ?: false,
            includeVideo = p[K.VIDEO] ?: true,
            periodMinutes = p[K.PERIOD] ?: 60,
            restoreConflict = p[K.CONFLICT] ?: "skip",
            lastSyncMs = p[K.LAST_SYNC] ?: 0L,
        )
    }

    suspend fun current(): NasConfig = config.first()

    suspend fun save(cfg: NasConfig) {
        context.dataStore.edit { p ->
            p[K.URL] = cfg.baseUrl.trim()
            p[K.AUTH_TOKEN] = cfg.authToken.trim()
            p[K.USERNAME] = cfg.username.trim()
            p[K.ROLE] = cfg.role.trim()
            p[K.USER_ID] = cfg.userId.trim()
            p[K.DISPLAY_NAME] = cfg.displayName.trim()
            p[K.SAVED_PW] = cfg.savedPassword
            p[K.BACKUP] = cfg.backupEnabled
            // null 表示"没挑过"，得把键删掉才能和"空集（一个都不选）"区分
            if (cfg.selectedAlbums == null) p.remove(K.ALBUMS) else p[K.ALBUMS] = cfg.selectedAlbums!!
            p[K.WIFI] = cfg.wifiOnly
            p[K.CHARGING] = cfg.chargingOnly
            p[K.VIDEO] = cfg.includeVideo
            p[K.PERIOD] = cfg.periodMinutes
            p[K.CONFLICT] = cfg.restoreConflict
            if (cfg.deviceId.isNotBlank()) p[K.DEVICE_ID] = cfg.deviceId
        }
    }

    /** 保存登录结果（地址保持不变）。 */
    suspend fun saveLogin(baseUrl: String, r: com.photovault.app.data.LoginResult, rememberPassword: String) {
        context.dataStore.edit { p ->
            p[K.URL] = baseUrl.trim()
            p[K.AUTH_TOKEN] = r.token
            p[K.USERNAME] = r.username
            p[K.ROLE] = r.role
            p[K.DISPLAY_NAME] = r.displayName
            p[K.SAVED_PW] = rememberPassword
        }
    }

    /** 退出登录：清掉身份，地址和备份偏好保留。 */
    suspend fun logout() {
        context.dataStore.edit { p ->
            p[K.AUTH_TOKEN] = ""
            p[K.USERNAME] = ""
            p[K.ROLE] = ""
            p[K.USER_ID] = ""
            p[K.DISPLAY_NAME] = ""
            p[K.SAVED_PW] = ""
        }
    }

    suspend fun deviceId(): String {
        val cur = current()
        if (cur.deviceId.isNotBlank()) return cur.deviceId
        val fresh = "android-" + UUID.randomUUID().toString().take(8)
        context.dataStore.edit { p -> p[K.DEVICE_ID] = fresh }
        return fresh
    }

    suspend fun markSynced(atMs: Long) {
        context.dataStore.edit { p -> p[K.LAST_SYNC] = atMs }
    }

    suspend fun resetSync() {
        context.dataStore.edit { p -> p[K.LAST_SYNC] = 0L }
    }
}
