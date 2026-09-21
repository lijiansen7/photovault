package com.photovault.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.photovault.app.data.NasApi
import com.photovault.app.data.NasConfig
import com.photovault.app.data.SettingsRepo
import com.photovault.app.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)

    val stored: StateFlow<NasConfig> =
        repo.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NasConfig())

    var draft by mutableStateOf(NasConfig())
        private set

    var testing by mutableStateOf(false)
    var testResult by mutableStateOf<String?>(null)
    var savedTick by mutableStateOf(0)

    /** 回收站数量（登录后拉取，清空后归零）。 */
    var trashCount by mutableStateOf(0)
        private set
    var emptying by mutableStateOf(false)
        private set
    var trashMsg by mutableStateOf<String?>(null)
        private set

    /** 登录表单（不落盘，除非用户勾选记住密码） */
    var loginUser by mutableStateOf("")
        private set
    var loginPass by mutableStateOf("")
        private set
    var rememberPw by mutableStateOf(true)
        private set
    var loggingIn by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    private var loaded = false

    init {
        viewModelScope.launch {
            repo.config.collect {
                if (!loaded) {
                    draft = it
                    loginUser = it.username
                    if (it.savedPassword.isNotBlank()) loginPass = it.savedPassword
                    loaded = true
                    if (it.loggedIn) refreshTrash()
                }
            }
        }
    }

    /** 拉取回收站数量。未登录 / 网络异常时静默失败（保持 0），不影响其它设置。 */
    fun refreshTrash() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { NasApi(draft).trashCount() } }
                .onSuccess { trashCount = it }
        }
    }

    /** 一键清空回收站（不可逆）。成功后会把数量归零。 */
    fun emptyTrash() {
        viewModelScope.launch {
            emptying = true
            trashMsg = runCatching {
                val n = withContext(Dispatchers.IO) { NasApi(draft).emptyTrash() }
                trashCount = 0
                "已清空回收站，共删除 $n 张"
            }.getOrElse { "清空失败：${it.message}" }
            emptying = false
        }
    }

    fun edit(fn: (NasConfig) -> NasConfig) {
        draft = fn(draft)
        testResult = null
    }

    /**
     * 备份相关设置（开关/周期/还原策略）改动后立即落盘并应用调度，
     * 避免用户改了「检查周期」却忘点顶部「保存」而丢失。
     * 只持久化备份相关字段，绝不覆盖服务器卡片里可能正在改、但还没点保存的地址/登录态。
     */
    fun editBackup(fn: (NasConfig) -> NasConfig) {
        draft = fn(draft)
        testResult = null
        viewModelScope.launch {
            val storedCfg = repo.current()
            val merged = storedCfg.copy(
                backupEnabled = draft.backupEnabled,
                wifiOnly = draft.wifiOnly,
                chargingOnly = draft.chargingOnly,
                includeVideo = draft.includeVideo,
                periodMinutes = draft.periodMinutes,
                restoreConflict = draft.restoreConflict,
            )
            repo.save(merged)
            UploadScheduler.apply(getApplication(), merged)
            savedTick++
        }
    }

    // 方法名不能叫 setXxx：`var loginUser by mutableStateOf()` 已经生成了 setLoginUser
    fun typeUser(v: String) { loginUser = v; loginError = null }
    fun typePass(v: String) { loginPass = v; loginError = null }
    fun toggleRemember(v: Boolean) { rememberPw = v }

    /** 账号密码登录：成功后把 token 和用户信息落盘。 */
    fun login() {
        viewModelScope.launch {
            loggingIn = true
            loginError = null
            runCatching {
                val r = withContext(Dispatchers.IO) { NasApi.login(draft.baseUrl, loginUser, loginPass) }
                repo.saveLogin(draft.baseUrl, r, if (rememberPw) loginPass else "")
                draft = repo.current()
                UploadScheduler.apply(getApplication(), draft)
                savedTick++
                "已登录：${r.displayName}（${if (r.isAdmin) "管理员" else "普通用户"}）"
            }.onSuccess { testResult = it; refreshTrash() }
                .onFailure { loginError = it.message ?: "登录失败" }
            loggingIn = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            draft = repo.current()
            loginPass = ""
            savedTick++
            testResult = "已退出登录"
        }
    }

    fun test() {
        viewModelScope.launch {
            testing = true
            testResult = runCatching {
                if (draft.baseUrl.isBlank()) throw IllegalArgumentException("请先填写 NAS 地址")
                withContext(Dispatchers.IO) { NasApi(draft).ping() }
                "连接成功"
            }.getOrElse { "连接失败：${it.message}" }
            testing = false
        }
    }

    fun save() {
        viewModelScope.launch {
            repo.save(draft)
            UploadScheduler.apply(getApplication(), draft)
            savedTick++
        }
    }

    fun resetSyncPoint() {
        viewModelScope.launch {
            repo.resetSync()
            savedTick++
        }
    }
}
